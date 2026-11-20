package cn.lemonit.paperplane.net

import cn.lemonit.paperplane.kit.ApiException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import okio.sink
import okio.source
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * App 内的回环 HTTP 服务（对照桌面 `internal/gateway` 的回环端口）：给 WebView 用，编辑器、终端页、插件网页
 * 都按桌面那样访问 `http://127.0.0.1:端口/…?token=本地令牌`，网页代码不用改。
 *
 * 规则（同桌面网关 handler）：
 * - Host 必须是 127.0.0.1 / localhost / [::1] 加本端口（防 DNS 重绑定）；
 * - `/healthz` 回 ok；`/editor/…` 用 APK 里的编辑器网页（[AssetSource]），不要令牌；
 * - 带 Origin 头时必须是本地址；
 * - 令牌：`Authorization: Bearer <令牌>`；GET 请求和 `/plugins/`、`/api/v1/plugins/` 下的静态文件也可以用查询参数 `token`；
 * - 其余请求转发给当前服务器：去掉 token 参数和 Authorization / Origin / Referer / Cookie，换成访问密钥，
 *   请求体原样流式转发，响应流式回写不缓冲（事件流、终端流立刻送达）；
 * - 服务器回 401（密钥失效）时改成 403 `remote_unauthorized`（和桌面一致，网页不会当成本地令牌失效），
 *   服务器连不上时回 502 `unreachable` / `fingerprint_mismatch`。
 *
 * 每个连接一个线程（WebView 会同时开好几个请求和长连接），支持 keep-alive；没有 WebSocket（内核没用）。
 */
class LocalGateway(
    private val client: RemoteClient,
    private val editor: AssetSource? = null,
    private val logger: NetLogger = NetLogger.NONE,
) : Closeable {
    /** 本地令牌（随机 32 字节十六进制） */
    val token: String = randomHex(32)

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var closed = false

    private val threadNo = AtomicInteger()
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "paperplane-gateway-${threadNo.incrementAndGet()}").apply { isDaemon = true }
    }
    private val sockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    /** 监听端口（start 之前为 0） */
    val port: Int get() = server?.localPort ?: 0

    /** http://127.0.0.1:端口 */
    val baseUrl: String get() = "http://127.0.0.1:$port"

    /** 开始监听 127.0.0.1 的随机端口（只绑定端口，不连服务器；重复调用无副作用） */
    fun start(): LocalGateway {
        synchronized(this) {
            check(!closed) { "gateway closed" }
            if (server != null) return this
            val ss = ServerSocket(0, 64, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            server = ss
            pool.execute { acceptLoop(ss) }
            logger.log("gateway: listening on 127.0.0.1:${ss.localPort}")
        }
        return this
    }

    /** 关掉监听、全部连接和进行中的转发 */
    override fun close() {
        closed = true
        try {
            server?.close()
        } catch (e: IOException) {
        }
        sockets.forEach { closeQuietly(it) }
        calls.forEach { it.cancel() }
        pool.shutdownNow()
    }

    /**
     * 编辑器页面地址：`http://127.0.0.1:端口/editor/<page>?<params>&token=…#<fragment>`（编辑器网页从地址参数里取令牌）。
     */
    fun editorUrl(page: String = "index.html", params: Map<String, String> = emptyMap(), fragment: String? = null): String {
        val q = (params + ("token" to token)).entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        return "$baseUrl/editor/${page.removePrefix("/")}?$q" + (fragment?.let { "#$it" } ?: "")
    }

    /**
     * 把服务器给的网页地址（`view.render` 的 webviewUrl：相对路径 `/plugins/<id>/…?projectId=…`，
     * 或带服务器自己端口和令牌的绝对地址）换成本地网关地址，并带上本地令牌（去掉原来的 token）。
     */
    fun url(pathOrUrl: String): String {
        var s = pathOrUrl.trim()
        val scheme = s.indexOf("://")
        if (scheme > 0) {
            val slash = s.indexOf('/', scheme + 3)
            s = if (slash < 0) "/" else s.substring(slash)
        }
        if (!s.startsWith("/")) s = "/$s"
        var fragment = ""
        s.indexOf('#').takeIf { it >= 0 }?.let {
            fragment = s.substring(it)
            s = s.substring(0, it)
        }
        val path = s.substringBefore('?')
        val query = if ('?' in s) stripToken(s.substringAfter('?')) else null
        val q = listOfNotNull(query?.takeIf { it.isNotEmpty() }, "token=${enc(token)}").joinToString("&")
        return "$baseUrl$path?$q$fragment"
    }

    // ---------------------------------------------------------------- 连接

    private fun acceptLoop(ss: ServerSocket) {
        while (!closed) {
            val s = try {
                ss.accept()
            } catch (e: IOException) {
                break
            }
            if (closed) {
                closeQuietly(s)
                break
            }
            sockets += s
            try {
                pool.execute { handleConnection(s) }
            } catch (e: Exception) {
                sockets -= s
                closeQuietly(s)
            }
        }
    }

    private fun handleConnection(s: Socket) {
        try {
            s.tcpNoDelay = true
            s.soTimeout = IDLE_TIMEOUT_MS
            val input = s.getInputStream().source().buffer()
            val output = s.getOutputStream().sink().buffer()
            while (!closed) {
                val req = try {
                    readRequest(input) ?: break
                } catch (e: BadRequest) {
                    writeSimple(output, 400, "text/plain; charset=utf-8", (e.message ?: "bad request").toByteArray(), keepAlive = false)
                    output.flush()
                    break
                }
                val keep = serve(s, req, input, output)
                output.flush()
                if (!keep) break
            }
        } catch (e: IOException) {
            // 对方断开、空闲超时、写不进去
        } catch (e: Exception) {
            logger.log("gateway: $e")
        } finally {
            sockets -= s
            closeQuietly(s)
        }
    }

    private class BadRequest(message: String) : Exception(message)

    private class Req(val method: String, target: String, val http10: Boolean, val headers: List<Pair<String, String>>) {
        val path: String
        val rawQuery: String?

        init {
            var t = target
            if (t.startsWith("http://", true) || t.startsWith("https://", true)) {
                val slash = t.indexOf('/', t.indexOf("://") + 3)
                t = if (slash < 0) "/" else t.substring(slash)
            }
            path = t.substringBefore('?')
            rawQuery = if ('?' in t) t.substringAfter('?') else null
        }

        fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, true) }?.second

        val keepAlive: Boolean
            get() {
                val c = header("Connection")?.lowercase()
                return if (http10) c?.contains("keep-alive") == true else c?.contains("close") != true
            }
    }

    private fun readRequest(input: BufferedSource): Req? {
        var line: String
        do {
            if (input.exhausted()) return null
            line = input.readUtf8LineStrict(MAX_LINE.toLong())
        } while (line.isEmpty())
        val parts = line.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.") || !parts[1].startsWith("/") && !parts[1].contains("://")) {
            throw BadRequest("bad request line")
        }
        val headers = ArrayList<Pair<String, String>>()
        var total = line.length
        while (true) {
            val h = input.readUtf8LineStrict(MAX_LINE.toLong())
            if (h.isEmpty()) break
            total += h.length
            if (total > MAX_HEADER_BYTES || headers.size > 200) throw BadRequest("request header too large")
            val i = h.indexOf(':')
            if (i <= 0) throw BadRequest("bad header")
            headers += h.substring(0, i).trim() to h.substring(i + 1).trim()
        }
        return Req(parts[0].uppercase(), parts[1], parts[2] == "HTTP/1.0", headers)
    }

    /** 处理一个请求，返回连接能否继续用 */
    private fun serve(s: Socket, req: Req, input: BufferedSource, output: BufferedSink): Boolean {
        var keep = req.keepAlive
        val body: BodySource? = try {
            bodyOf(req, input)
        } catch (e: BadRequest) {
            writeSimple(output, 400, "text/plain; charset=utf-8", (e.message ?: "").toByteArray(), keepAlive = false)
            return false
        }
        val p = port.toString()
        val host = req.header("Host")
        keep = when {
            host != "127.0.0.1:$p" && host != "localhost:$p" && host != "[::1]:$p" ->
                writeSimple(output, 403, "text/plain; charset=utf-8", "forbidden host".toByteArray(), keep)
            req.path == "/healthz" -> writeSimple(output, 200, "text/plain; charset=utf-8", "ok".toByteArray(), keep)
            req.path.startsWith("/editor/") -> serveEditor(req, output, keep)
            else -> {
                val origin = req.header("Origin")
                val static = req.path.startsWith("/plugins/") || req.path.startsWith("/api/v1/plugins/")
                var tok = req.header("Authorization")?.removePrefix("Bearer ").orEmpty()
                if (tok.isEmpty() && (static || req.method == "GET")) tok = queryParam(req.rawQuery, "token").orEmpty()
                when {
                    origin != null && origin != "http://127.0.0.1:$p" && origin != "http://localhost:$p" ->
                        writeSimple(output, 403, "text/plain; charset=utf-8", "forbidden origin".toByteArray(), keep)
                    !MessageDigest.isEqual(tok.toByteArray(), token.toByteArray()) ->
                        writeError(output, 401, "unauthorized", "invalid token", keep)
                    else -> forward(s, req, body, input, output, keep)
                }
            }
        }
        // 没被读完的请求体要读掉才能复用连接
        if (keep && body != null && !body.exhausted) {
            keep = try {
                Buffer().let { b ->
                    var left = MAX_DRAIN
                    while (left > 0) {
                        val n = body.read(b, minOf(left, 64 * 1024L))
                        if (n == -1L) break
                        b.clear()
                        left -= n
                    }
                }
                body.exhausted
            } catch (e: IOException) {
                false
            }
        }
        return keep
    }

    // ---------------------------------------------------------------- 编辑器网页

    private fun serveEditor(req: Req, out: BufferedSink, keep: Boolean): Boolean {
        if (req.method != "GET" && req.method != "HEAD") {
            return writeSimple(out, 405, "text/plain; charset=utf-8", "method not allowed".toByteArray(), keep)
        }
        val src = editor ?: return writeSimple(out, 404, "text/plain; charset=utf-8", "404 page not found".toByteArray(), keep)
        val rel = cleanRelative(req.path.removePrefix("/editor/"))
            ?: return writeSimple(out, 404, "text/plain; charset=utf-8", "404 page not found".toByteArray(), keep)
        val bytes = src.read(rel) ?: return writeSimple(out, 404, "text/plain; charset=utf-8", "404 page not found".toByteArray(), keep)
        val cache = if (rel.endsWith(".html")) "no-cache" else "max-age=86400"
        return writeSimple(out, 200, mimeType(rel), bytes, keep, listOf("Cache-Control" to cache), head = req.method == "HEAD")
    }

    // ---------------------------------------------------------------- 转发

    private fun forward(s: Socket, req: Req, body: BodySource?, input: BufferedSource, output: BufferedSink, keepAlive: Boolean): Boolean {
        var base = try {
            client.baseBlocking()
        } catch (e: ApiException) {
            client.reportProblem(e)
            return writeError(output, 502, upstreamCode(e), e.message, keepAlive)
        }
        val query = req.rawQuery?.let { stripToken(it) }?.takeIf { it.isNotEmpty() }
        val headers = okhttp3.Headers.Builder()
        val connectionTokens = req.header("Connection")?.split(',')?.map { it.trim().lowercase() }.orEmpty().toSet()
        for ((n, v) in req.headers) {
            val ln = n.lowercase()
            if (ln in HOP_BY_HOP || ln in DROPPED_REQUEST || ln in connectionTokens) continue
            try {
                headers.addUnsafeNonAscii(n, v)
            } catch (e: IllegalArgumentException) {
                // 非法头名：丢掉
            }
        }
        headers.set("Authorization", "Bearer ${client.token}")
        if (body != null && req.header("Expect")?.equals("100-continue", true) == true) {
            output.writeUtf8("HTTP/1.1 100 Continue\r\n\r\n")
            output.flush()
        }
        val reqBody: RequestBody? = when {
            body != null -> StreamBody(req.header("Content-Type")?.toMediaTypeOrNull(), body.length, body)
            req.method in METHODS_WITH_BODY -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        var retried = false
        while (true) {
            val url = (base + req.path + (query?.let { "?$it" } ?: "")).toHttpUrlOrNull()
                ?: return writeSimple(output, 400, "text/plain; charset=utf-8", "bad url".toByteArray(), false)
            val trace = CallTrace()
            val request = try {
                Request.Builder().url(url).headers(headers.build()).method(req.method, reqBody)
                    .tag(CallTrace::class.java, trace).build()
            } catch (e: IllegalArgumentException) {
                return writeSimple(output, 400, "text/plain; charset=utf-8", (e.message ?: "bad request").toByteArray(), false)
            }
            val call = client.proxyHttp.newCall(request)
            calls += call
            val resp = try {
                call.execute()
            } catch (e: IOException) {
                calls -= call
                if (closed) throw e
                if (!trace.requestSent && !retried) {
                    retried = true
                    base = try {
                        client.failoverBlocking(base)
                    } catch (x: ApiException) {
                        client.reportProblem(x)
                        return writeError(output, 502, upstreamCode(x), x.message, keepAlive && body?.exhausted != false)
                    }
                    continue
                }
                val err = NetErrors.map(e)
                client.reportProblem(err)
                return writeError(output, 502, upstreamCode(err), err.message, keepAlive && body?.exhausted != false)
            }
            try {
                return writeUpstream(s, req, resp, call, input, output, keepAlive)
            } finally {
                calls -= call
                resp.close()
            }
        }
    }

    private fun writeUpstream(
        s: Socket, req: Req, resp: okhttp3.Response, call: Call,
        input: BufferedSource, out: BufferedSink, keepAlive: Boolean,
    ): Boolean {
        val code = resp.code
        if (code == 401) {
            // 服务器拒绝了访问密钥：改成 403 remote_unauthorized（同桌面网关）
            client.reportProblem(ApiException(ApiException.UNAUTHORIZED, "the access key is no longer valid", 401))
            return writeError(out, 403, "remote_unauthorized", "the access key is no longer valid (revoked or deleted)", keepAlive)
        }
        if (code == 429) client.reportProblem(NetErrors.http(429, ByteArray(0), resp.headers))
        val stream = resp.header("Content-Type")?.startsWith("text/event-stream", true) == true
        val noBody = req.method == "HEAD" || code in 100..199 || code == 204 || code == 304
        val length = if (noBody) -1L else resp.body?.contentLength() ?: -1L
        val chunked = !noBody && length < 0 && !req.http10
        val keep = keepAlive && !stream && (noBody || length >= 0 || chunked)
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(code).append(' ').append(reasonPhrase(code, resp.message)).append("\r\n")
        for ((n, v) in resp.headers) {
            val ln = n.lowercase()
            if (ln in HOP_BY_HOP || ln == "content-length") continue
            head.append(n).append(": ").append(v).append("\r\n")
        }
        when {
            noBody -> resp.header("Content-Length")?.let { head.append("Content-Length: ").append(it).append("\r\n") }
            length >= 0 -> head.append("Content-Length: ").append(length).append("\r\n")
            chunked -> head.append("Transfer-Encoding: chunked\r\n")
        }
        if (!keep) head.append("Connection: close\r\n")
        head.append("\r\n")
        out.writeUtf8(head.toString())
        out.flush()
        if (noBody) return keep
        val src = resp.body?.source() ?: return keep
        // 事件流：网页关掉 EventSource 时立刻取消上游，不等下一次 ping 写失败
        val done = AtomicBoolean(false)
        if (stream) {
            s.soTimeout = 0
            pool.execute {
                try {
                    while (!done.get() && !input.exhausted()) input.skip(input.buffer.size)
                } catch (e: IOException) {
                }
                if (!done.get()) call.cancel()
            }
        }
        try {
            val buf = Buffer()
            while (true) {
                val n = src.read(buf, 64 * 1024L)
                if (n == -1L) break
                if (chunked) {
                    out.writeHexadecimalUnsignedLong(n).writeUtf8("\r\n")
                    out.write(buf, n)
                    out.writeUtf8("\r\n")
                } else {
                    out.write(buf, n)
                }
                if (src.buffer.size == 0L) out.flush()
            }
            if (chunked) out.writeUtf8("0\r\n\r\n")
            out.flush()
        } finally {
            done.set(true)
        }
        return keep
    }

    private fun upstreamCode(e: ApiException) =
        if (e.code == ApiException.FINGERPRINT_MISMATCH) "fingerprint_mismatch" else if (e.code == ApiException.NETWORK) "unreachable" else e.code

    // ---------------------------------------------------------------- 请求体

    private abstract class BodySource : Source {
        abstract val length: Long
        abstract val exhausted: Boolean
    }

    private fun bodyOf(req: Req, input: BufferedSource): BodySource? {
        val te = req.header("Transfer-Encoding")
        if (te != null) {
            if (!te.equals("chunked", true)) throw BadRequest("unsupported transfer encoding")
            return ChunkedSource(input)
        }
        val cl = req.header("Content-Length") ?: return null
        val n = cl.toLongOrNull()?.takeIf { it >= 0 } ?: throw BadRequest("bad content length")
        if (n == 0L) return null
        return FixedLengthSource(input, n)
    }

    private class FixedLengthSource(private val input: BufferedSource, override val length: Long) : BodySource() {
        private var remaining = length
        override val exhausted get() = remaining == 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (remaining == 0L) return -1
            val n = input.read(sink, minOf(byteCount, remaining))
            if (n == -1L) throw EOFException("unexpected end of request body")
            remaining -= n
            return n
        }

        override fun timeout(): Timeout = input.timeout()
        override fun close() {}
    }

    private class ChunkedSource(private val input: BufferedSource) : BodySource() {
        private var inChunk = 0L
        private var done = false
        override val length = -1L
        override val exhausted get() = done

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (done) return -1
            if (inChunk == 0L) {
                val size = input.readUtf8LineStrict(MAX_LINE.toLong()).substringBefore(';').trim().toLongOrNull(16)
                    ?: throw IOException("bad chunk size")
                if (size == 0L) {
                    while (input.readUtf8LineStrict(MAX_LINE.toLong()).isNotEmpty()) {
                        // 跳过 trailer
                    }
                    done = true
                    return -1
                }
                inChunk = size
            }
            val n = input.read(sink, minOf(byteCount, inChunk))
            if (n == -1L) throw EOFException("unexpected end of chunked body")
            inChunk -= n
            if (inChunk == 0L) input.readUtf8LineStrict(2)
            return n
        }

        override fun timeout(): Timeout = input.timeout()
        override fun close() {}
    }

    /** 只能写一次的流式请求体（OkHttp 不会重试已经开始发送的一次性请求体） */
    private class StreamBody(private val type: MediaType?, private val length: Long, private val source: Source) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = length
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            sink.writeAll(source)
        }
    }

    // ---------------------------------------------------------------- 响应

    private fun writeSimple(
        out: BufferedSink, code: Int, contentType: String, body: ByteArray, keepAlive: Boolean,
        extra: List<Pair<String, String>> = emptyList(), head: Boolean = false,
    ): Boolean {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reasonPhrase(code, "")).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        for ((n, v) in extra) sb.append(n).append(": ").append(v).append("\r\n")
        if (!keepAlive) sb.append("Connection: close\r\n")
        sb.append("\r\n")
        out.writeUtf8(sb.toString())
        if (!head) out.write(body)
        return keepAlive
    }

    private fun writeError(out: BufferedSink, code: Int, errCode: String, message: String, keepAlive: Boolean): Boolean {
        val json = buildJsonObject {
            put("error", buildJsonObject {
                put("code", errCode)
                put("message", message)
            })
        }.toString()
        return writeSimple(out, code, "application/json", json.toByteArray(), keepAlive)
    }

    companion object {
        private const val IDLE_TIMEOUT_MS = 120_000
        private const val MAX_LINE = 16 * 1024
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_DRAIN = 4L * 1024 * 1024

        private val HOP_BY_HOP = setOf(
            "connection", "keep-alive", "proxy-connection", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade",
        )
        private val DROPPED_REQUEST = setOf("host", "content-length", "expect", "authorization", "origin", "referer", "cookie")
        private val METHODS_WITH_BODY = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")

        private fun randomHex(n: Int): String {
            val b = ByteArray(n)
            SecureRandom().nextBytes(b)
            return Fingerprints.hex(b)
        }

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        private fun closeQuietly(s: Socket) {
            try {
                s.close()
            } catch (e: IOException) {
            }
        }

        /** 去掉查询串里所有 token 参数，其余原样保留（不重新编码） */
        internal fun stripToken(rawQuery: String): String =
            rawQuery.split('&').filter { it.isNotEmpty() && decodeComponent(it.substringBefore('=')) != "token" }.joinToString("&")

        internal fun queryParam(rawQuery: String?, name: String): String? {
            if (rawQuery == null) return null
            for (kv in rawQuery.split('&')) {
                if (kv.isEmpty()) continue
                if (decodeComponent(kv.substringBefore('=')) == name) {
                    return decodeComponent(if ('=' in kv) kv.substringAfter('=') else "")
                }
            }
            return null
        }

        private fun decodeComponent(s: String): String? = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: IllegalArgumentException) {
            null
        }

        /** `/editor/` 之后的路径：百分号解码后按 path.Clean 规则清理成相对路径；空则 index.html；非法返回 null */
        internal fun cleanRelative(raw: String): String? {
            val decoded = try {
                URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
            } catch (e: IllegalArgumentException) {
                return null
            }
            if ('\u0000' in decoded) return null
            val parts = ArrayList<String>()
            for (seg in decoded.split('/')) {
                when (seg) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts += seg
                }
            }
            return parts.joinToString("/").ifEmpty { "index.html" }
        }

        internal fun mimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json", "map" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "wasm" -> "application/wasm"
            "txt" -> "text/plain; charset=utf-8"
            "xml" -> "application/xml"
            else -> "application/octet-stream"
        }

        private fun reasonPhrase(code: Int, given: String): String = given.ifEmpty {
            when (code) {
                100 -> "Continue"
                101 -> "Switching Protocols"
                200 -> "OK"
                201 -> "Created"
                204 -> "No Content"
                206 -> "Partial Content"
                301 -> "Moved Permanently"
                302 -> "Found"
                304 -> "Not Modified"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                403 -> "Forbidden"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                409 -> "Conflict"
                429 -> "Too Many Requests"
                500 -> "Internal Server Error"
                502 -> "Bad Gateway"
                503 -> "Service Unavailable"
                else -> "Status"
            }
        }
    }
}
