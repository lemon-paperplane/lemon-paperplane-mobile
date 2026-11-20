package cn.lemonit.paperplane.net

import cn.lemonit.paperplane.kit.ApiException
import cn.lemonit.paperplane.kit.OpCaller
import cn.lemonit.paperplane.kit.PaperplaneJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager

/**
 * 一台服务器的连接参数（来自服务器条目或连接码）。用 [of] 构造，地址会规范化、去重。
 */
data class ServerTarget(
    /** 全部地址（https://主机:端口），按顺序试 */
    val urls: List<String>,
    /** 证书指纹 "sha256:…"；trusted 时可以为空 */
    val fingerprint: String = "",
    /** 证书由系统信任的 CA 签发：按证书链 + 主机名校验（指纹对得上时同样放行） */
    val trusted: Boolean = false,
    /** 上次能用的地址，先试它 */
    val preferredUrl: String? = null,
) {
    companion object {
        fun of(urls: Iterable<String>, fingerprint: String, trusted: Boolean = false, preferredUrl: String? = null): ServerTarget {
            val pref = preferredUrl?.let { ServerAddress.normalize(it) }
            val list = ServerAddress.normalizeAll(listOfNotNull(pref) + urls)
            return ServerTarget(list, fingerprint, trusted, pref)
        }
    }
}

/** 一条已打开的流式响应（SSE）：Call 用来随时取消。 */
class StreamResponse(val call: Call, val response: Response)

/**
 * 直连一台远程服务器（对照 Mac APIClient + 桌面网关的客户端一半）：OkHttp 直连 `https://服务器`，
 * 证书按 [ServerTarget] 固定指纹或按 CA 校验，每个请求带 `Authorization: Bearer <访问密钥>`，不带 Origin，不走系统代理。
 *
 * 多地址：[connect] 用 hello 同时探测所有地址（上次能用的先试，其余每隔几百毫秒错开加入），记住最先连上的那个；
 * 之后的请求都发往它。请求还没发出去就失败（连不上、证书不对）时自动重新探测并在新地址上重试一次；
 * 已经发出去的请求失败不重试（操作可能已执行），直接报 network。
 *
 * 线程：所有 suspend 方法可在任意线程调用；协程取消会取消 HTTP 请求并照常抛 CancellationException。
 */
class RemoteClient(
    val target: ServerTarget,
    val token: String,
    private val options: Options = Options(),
    private val listener: Listener? = null,
) : OpCaller, Closeable {

    class Options(
        /** TCP + TLS 建连超时 */
        val connectTimeoutMs: Long = 8_000,
        /** 操作调用的默认超时（长操作，如导入、安装依赖） */
        val defaultTimeoutMs: Long = 300_000,
        /** 探测单个地址的超时 */
        val probeTimeoutMs: Long = 10_000,
        /** 探测时下一个地址错开加入的间隔 */
        val probeStaggerMs: Long = 400,
        /** 长连接多久收不到任何字节算断线（服务器每 15 秒发一次 ping） */
        val streamIdleTimeoutMs: Long = 45_000,
        val logger: NetLogger = NetLogger.NONE,
        /** 测试用：替换系统信任库 */
        internal val systemTrust: X509TrustManager? = null,
    )

    /** 回调可能在任意线程、多次调用（每个失败的请求都会通知），界面层自己去重并切回主线程。 */
    interface Listener {
        /** 改用了另一个地址（第一次连上也会通知）：存进服务器条目的「上次能用的地址」 */
        fun onActiveUrlChanged(url: String) {}

        /** 密钥失效、被封禁、证书变了、版本不兼容：任何请求、流、本地网关转发遇到都会通知，界面进入「连不上」状态 */
        fun onAuthProblem(error: ApiException) {}
    }

    private val log = options.logger
    private val urls: List<String> = ServerAddress.normalizeAll(target.urls)
    private val preferred: String? = target.preferredUrl?.let { ServerAddress.normalize(it) }
    internal val tls = TlsPolicy(target.fingerprint, target.trusted, options.systemTrust)

    /** 已配好证书校验、不走代理的 OkHttpClient（不会自动加密钥；自己发请求时用 [authorizedRequest]） */
    val http: OkHttpClient = tls.apply(OkHttpClient.Builder())
        .proxy(Proxy.NO_PROXY) // 内网地址经系统代理会连不上（同桌面网关）
        .connectTimeout(options.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(options.defaultTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(options.defaultTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(options.defaultTimeoutMs, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // HTTP/2 心跳：手机换网后尽快发现死连接
        .eventListenerFactory(CallTrace.FACTORY)
        .dispatcher(Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        })
        .build()

    private val probeHttp: OkHttpClient = http.newBuilder()
        .callTimeout(options.probeTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(options.probeTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    /** 长连接（事件流、日志流、终端流）：不限总时长，按空闲超时判断断线 */
    internal val streamHttp: OkHttpClient = http.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(options.streamIdleTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    /** 本地网关转发：不限总时长（网页里的事件流、终端流也走它） */
    internal val proxyHttp: OkHttpClient = http.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val timeoutClients = ConcurrentHashMap<Long, OkHttpClient>()

    /** 当前使用的地址（探测前为 null） */
    @Volatile
    var activeUrl: String? = null
        private set

    private val resolveLock = Mutex()

    // ---------------------------------------------------------------- 连接与地址

    /**
     * 重新探测全部地址并调一次 system.info（顺便验证密钥），返回 system.info 的结果。
     * 切到前台、网络变化、用户点「重试」时调用。错误：network / fingerprint_mismatch / unauthorized / banned / incompatible。
     */
    suspend fun connect(): JsonElement {
        guard { resolve(failed = null, force = true) }
        return callRaw("system.info")
    }

    /** 当前地址（还没探测时用上次能用的或第一个；[authorizedRequest] 用它拼地址） */
    fun currentBase(): String = activeUrl ?: preferred ?: urls.firstOrNull()
        ?: throw IllegalStateException("server has no address")

    /**
     * 选一个能用的地址。[failed] 是刚失败的地址（放到最后再试）；[force] 时即使已有地址也重新探测。
     * 并发调用共用一次探测。
     */
    internal suspend fun resolve(failed: String?, force: Boolean = false): String = resolveLock.withLock {
        val cur = activeUrl
        if (!force && cur != null && cur != failed) return@withLock cur
        val order = LinkedHashSet<String>()
        if (cur != null && cur != failed) order += cur
        preferred?.let { order += it }
        order += urls
        if (failed != null && order.remove(failed)) order += failed
        val url = race(order.toList())
        setActive(url)
        url
    }

    /** 阻塞版本（本地网关、端口转发的工作线程用） */
    internal fun baseBlocking(): String = activeUrl ?: runBlocking { resolve(null) }

    internal fun failoverBlocking(failed: String): String = runBlocking { resolve(failed) }

    private fun setActive(url: String) {
        val old = activeUrl
        activeUrl = url
        if (old != url) {
            log.log("remote: using $url")
            try {
                listener?.onActiveUrlChanged(url)
            } catch (e: Exception) {
                log.log("remote: listener failed: $e")
            }
        }
    }

    private sealed interface RaceMsg {
        class Done(val index: Int, val error: ApiException?) : RaceMsg
        class Tick(val generation: Int) : RaceMsg
    }

    /**
     * 探测：第一个地址先跑，[Options.probeStaggerMs] 后（或前一个失败时立刻）加入下一个，最先成功的胜出。
     * 密钥失效 / 封禁 / 版本不兼容说明已经连到了这台服务器，直接报错不再试别的地址；
     * 全部失败时优先报证书不符，其次 IP 被拒，否则报第一个地址的错误。
     */
    private suspend fun race(order: List<String>): String {
        if (order.isEmpty()) throw NetErrors.network(NetErrors.REASON_NO_ADDRESS, "the server has no address")
        if (order.size == 1) {
            probe(order[0])
            return order[0]
        }
        return coroutineScope {
            val ch = Channel<RaceMsg>(Channel.UNLIMITED)
            val jobs = ArrayList<Job>()
            val errors = arrayOfNulls<ApiException>(order.size)
            var next = 0
            var running = 0
            var generation = 0
            fun launchNext() {
                val i = next++
                running++
                val g = ++generation
                jobs += launch {
                    val err = try {
                        probe(order[i])
                        null
                    } catch (e: ApiException) {
                        e
                    }
                    ch.send(RaceMsg.Done(i, err))
                }
                if (next < order.size) {
                    jobs += launch {
                        delay(options.probeStaggerMs)
                        ch.send(RaceMsg.Tick(g))
                    }
                }
            }
            try {
                launchNext()
                var winner: String? = null
                while (winner == null) {
                    when (val m = ch.receive()) {
                        is RaceMsg.Tick -> if (m.generation == generation && next < order.size) launchNext()
                        is RaceMsg.Done -> {
                            running--
                            val e = m.error
                            if (e == null) {
                                winner = order[m.index]
                            } else {
                                log.log("remote: ${order[m.index]}: ${e.code} ${e.message}")
                                if (e.code in DEFINITIVE) throw e
                                errors[m.index] = e
                                if (next < order.size) launchNext() else if (running == 0) throw pickError(errors)
                            }
                        }
                    }
                }
                winner
            } finally {
                jobs.forEach { it.cancel() }
            }
        }
    }

    private fun pickError(errors: Array<ApiException?>): ApiException {
        val list = errors.filterNotNull()
        return list.firstOrNull { it.code == ApiException.FINGERPRINT_MISMATCH }
            ?: list.firstOrNull { it.reason == NetErrors.REASON_IP_DENIED }
            ?: list.first()
    }

    /** GET /api/v1/auth/hello（不带密钥，不计入失败次数）：证书、是否柠檬纸飞机、协议版本 */
    private suspend fun probe(url: String) {
        val req = Request.Builder().url("$url/api/v1/auth/hello").get().build()
        val raw = try {
            probeHttp.newCall(req).awaitRaw()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw NetErrors.map(e)
        }
        ServerHello.check(raw.code, raw.body, raw.headers)
    }

    // ---------------------------------------------------------------- 调用

    override suspend fun callRaw(op: String, input: JsonElement, timeoutMs: Long?): JsonElement = guard {
        val body = input.toString().toRequestBody(JSON)
        val client = clientFor(timeoutMs)
        withFailover { base, trace ->
            val req = Request.Builder()
                .url("$base/api/v1/call/${encodeSegment(op)}")
                .post(body)
                .header("Authorization", "Bearer $token")
                .tag(CallTrace::class.java, trace)
                .build()
            val raw = client.newCall(req).awaitRaw()
            decodeEnvelope(raw)
        }
    }

    /** GET /api/v1/ops：全部操作（含插件贡献的） */
    suspend fun ops(): JsonElement {
        val b = getBytes("/api/v1/ops")
        val el = try {
            PaperplaneJson.parseToJsonElement(b.decodeToString())
        } catch (e: Exception) {
            throw ApiException(ApiException.BAD_RESPONSE, "ops: ${e.message}", 200, cause = e)
        }
        return (el as? JsonObject)?.get("result") ?: el
    }

    /** GET /api/v1/plugins/<pluginId>/files/<path>（插件图标、网页资源） */
    suspend fun pluginFile(pluginId: String, path: String): ByteArray =
        getBytes("/api/v1/plugins/${encodeSegment(pluginId)}/files/" +
            path.split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) })

    /** 带密钥 GET 任意路径（如 `/plugins/<id>/icons/x.svg`），非 2xx 抛 ApiException */
    suspend fun getBytes(pathAndQuery: String): ByteArray = guard {
        withFailover { base, trace ->
            val req = Request.Builder().url(base + slash(pathAndQuery)).get()
                .header("Authorization", "Bearer $token")
                .tag(CallTrace::class.java, trace)
                .build()
            val raw = http.newCall(req).awaitRaw()
            if (raw.code in 200..299) raw.body else throw NetErrors.http(raw.code, raw.body, raw.headers)
        }
    }

    /**
     * 带密钥的请求（插件文件、图标等自己用 [http] 发）。地址用 [currentBase]，需要先 [connect] 过才是探测后的地址。
     */
    fun authorizedRequest(pathAndQuery: String, method: String = "GET", body: RequestBody? = null): Request =
        Request.Builder().url(currentBase() + slash(pathAndQuery))
            .method(method, body)
            .header("Authorization", "Bearer $token")
            .build()

    // ---------------------------------------------------------------- 长连接

    /** 打开一条 SSE（GET，带密钥）；非 200 抛 ApiException（401 / 404 等），连不上时换地址重试一次 */
    suspend fun openStream(pathAndQuery: String): StreamResponse = guard {
        withFailover { base, trace ->
            val req = Request.Builder().url(base + slash(pathAndQuery)).get()
                .header("Authorization", "Bearer $token")
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .tag(CallTrace::class.java, trace)
                .build()
            val call = streamHttp.newCall(req)
            val resp = call.awaitResponse()
            if (resp.code != 200) {
                val body = resp.use { it.body?.bytes() ?: ByteArray(0) }
                throw NetErrors.http(if (resp.code in 200..299) 502 else resp.code, body, resp.headers)
            }
            StreamResponse(call, resp)
        }
    }

    /** 通用 SSE 连接：每次（重）连时调用 [path] 取路径（可带最新的续传位置） */
    fun sse(
        path: () -> String,
        endEvent: String? = null,
        reconnect: Boolean = true,
        listener: (SseConnection.Event) -> Unit,
    ): SseConnection = SseConnection(open = { openStream(path()) }, reconnect = reconnect, endEvent = endEvent, logger = log, listener = listener)

    // ---------------------------------------------------------------- 内部

    private suspend fun <T> withFailover(block: suspend (base: String, trace: CallTrace) -> T): T {
        var base = activeUrl ?: resolve(null)
        var retried = false
        while (true) {
            val trace = CallTrace()
            try {
                return block(base, trace)
            } catch (e: IOException) {
                if (!trace.requestSent && !retried) {
                    retried = true
                    log.log("remote: $base failed before sending (${e.message}); probing addresses")
                    base = resolve(failed = base)
                    continue
                }
                throw NetErrors.map(e)
            }
        }
    }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: ApiException) {
        reportProblem(e)
        throw e
    }

    internal fun reportProblem(e: ApiException) {
        if (!e.needsUserAction) return
        try {
            listener?.onAuthProblem(e)
        } catch (x: Exception) {
            log.log("remote: listener failed: $x")
        }
    }

    private fun clientFor(timeoutMs: Long?): OkHttpClient {
        if (timeoutMs == null || timeoutMs == options.defaultTimeoutMs) return http
        return timeoutClients.getOrPut(timeoutMs) {
            http.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(maxOf(timeoutMs, 60_000), TimeUnit.MILLISECONDS)
                .build()
        }
    }

    private fun decodeEnvelope(raw: RawResponse): JsonElement {
        if (raw.code !in 200..299) throw NetErrors.http(raw.code, raw.body, raw.headers)
        val obj = NetErrors.parseObject(raw.body)
            ?: throw ApiException(ApiException.BAD_RESPONSE, "response is not a JSON object", raw.code)
        if (obj["error"] is JsonObject) throw NetErrors.http(raw.code, raw.body, raw.headers)
        return obj["result"] ?: throw ApiException(ApiException.BAD_RESPONSE, "empty result", raw.code)
    }

    private fun slash(p: String) = if (p.startsWith("/")) p else "/$p"

    /** 关掉连接池、取消进行中的请求（流和本地网关由各自的所有者关闭） */
    override fun close() {
        http.dispatcher.cancelAll()
        // 关 TLS 连接要发 close_notify（算网络操作），安卓主线程上不允许：放到后台线程
        offCallerThread("paperplane-client-close") { http.connectionPool.evictAll() }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val DEFINITIVE = setOf(ApiException.UNAUTHORIZED, ApiException.BANNED, ApiException.INCOMPATIBLE)
    }
}
