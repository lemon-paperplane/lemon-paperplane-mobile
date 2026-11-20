package cn.lemonit.paperplane.net

import cn.lemonit.paperplane.kit.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

/** 远程协议版本：和服务器 hello.apiVersion 不同就拒绝连接（同内核 remote.APIVersion） */
const val REMOTE_API_VERSION = 1

/** GET /api/v1/auth/hello 的结果（不需要认证，不含敏感信息；字段同内核 remote.Hello） */
data class ServerHello(
    val name: String,
    val hostname: String,
    val version: String,
    val apiVersion: Int,
    val os: String,
    val arch: String,
    /** 服务器开了账号密码登录且至少有一个账号 */
    val passwordLogin: Boolean,
) {
    companion object {
        /** 校验 hello 响应：429 封禁、403 IP 被拒、不是柠檬纸飞机、协议版本不同都抛 ApiException */
        internal fun check(status: Int, body: ByteArray, headers: Headers?): ServerHello {
            when {
                status == 429 -> throw NetErrors.http(status, body, headers)
                status == 403 -> throw NetErrors.http(status, body, headers)
                status != 200 -> throw NetErrors.network(NetErrors.REASON_NOT_PAPERPLANE, "not a Lemon Paperplane server (HTTP $status)")
            }
            val o = NetErrors.parseObject(body)
            val api = o?.int("apiVersion") ?: 0
            if (o == null || api == 0) throw NetErrors.network(NetErrors.REASON_NOT_PAPERPLANE, "not a Lemon Paperplane server")
            val h = ServerHello(
                name = o.string("name").orEmpty(),
                hostname = o.string("hostname").orEmpty(),
                version = o.string("version").orEmpty(),
                apiVersion = api,
                os = o.string("os").orEmpty(),
                arch = o.string("arch").orEmpty(),
                passwordLogin = o.bool("passwordLogin") ?: false,
            )
            if (h.apiVersion != REMOTE_API_VERSION) {
                throw ApiException(
                    ApiException.INCOMPATIBLE, "server version ${h.version} is incompatible with this app", status,
                    buildJsonObject {
                        put("serverVersion", h.version)
                        put("apiVersion", h.apiVersion)
                    },
                )
            }
            return h
        }
    }
}

/** [RemoteAuth.probe] 的结果：界面拿 fingerprintDisplay 给用户核对（trusted 时不用核对） */
data class ProbeResult(
    /** 规范化后的地址 */
    val url: String,
    val fingerprint: String,
    val fingerprintDisplay: String,
    /** 证书由系统信任的 CA 签发且主机名相符 */
    val trusted: Boolean,
    val hello: ServerHello,
)

/** 账号密码登录换来的设备专用访问密钥（密码不保存） */
data class LoginResult(val token: String, val keyId: String) {
    override fun toString(): String = "LoginResult(token=<hidden>, keyId=$keyId)"
}

/** 用连接码连上后要保存的东西 */
data class CodeConnection(
    /** 地址列表（用户改过的地址在最前）、指纹、trusted=false */
    val target: ServerTarget,
    /** 实际连上的地址 */
    val url: String,
    val token: String,
    val keyId: String,
    /** 服务器的 system.info（os、hostname、pathSeparator、home…） */
    val info: JsonElement,
) {
    override fun toString(): String = "CodeConnection(target=$target, url=$url, token=<hidden>, keyId=$keyId)"
}

/**
 * 添加服务器的认证流程（对照桌面 gateway.probe / gateway.login 与 Mac AddServerDialog）：
 *
 * - 连接码：[connectWithCode]：依次（错开并行）试连接码里的地址，固定指纹，调 system.info 验证密钥。
 * - 账号密码：[probe] 拿 hello 和证书指纹（不是 CA 证书时交给界面让用户核对，和 SSH 第一次连接一样）→
 *   用户确认后 [login]（按确认的指纹固定）换访问密钥 → 用 [RemoteClient] 连接。
 *
 * 错误（ApiException.code）：账号或密码不对 = unauthorized（reason=badLogin）；被封禁 = banned；
 * 没开账号密码登录 = password_disabled；连不上 / 不是柠檬纸飞机 = network；证书变了 = fingerprint_mismatch；
 * 版本不兼容 = incompatible。
 */
class RemoteAuth(private val options: Options = Options()) {

    class Options(
        val connectTimeoutMs: Long = 8_000,
        val timeoutMs: Long = 20_000,
        val logger: NetLogger = NetLogger.NONE,
        /** 测试用：替换系统信任库 */
        internal val systemTrust: X509TrustManager? = null,
    )

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(options.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(options.timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(options.timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * 连上地址取证书指纹和 hello（不校验证书，只发不带凭据的 hello）。证书能被系统信任（CA 签发、主机名相符）时 trusted=true。
     * [address] 可以是「主机」「主机:端口」「https://…」。
     */
    suspend fun probe(address: String): ProbeResult {
        val url = ServerAddress.normalize(address)
            ?: throw ApiException(ApiException.USER_ERROR, "invalid address: $address")
        // 1. 按系统信任库 + 主机名校验试一次：通过就是 CA 证书，不用用户核对
        val sys = options.systemTrust
        val systemClient = baseBuilder().let { b ->
            if (sys != null) b.sslSocketFactory(Tls.socketFactory(sys), sys) else b
        }.build()
        try {
            val raw = systemClient.newCall(helloRequest(url)).awaitRaw()
            val hello = ServerHello.check(raw.code, raw.body, raw.headers)
            val fp = raw.leaf?.let { Fingerprints.of(it) }.orEmpty()
            return ProbeResult(url, fp, Fingerprints.display(fp), true, hello)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw e
        } catch (e: SSLException) {
            // 证书不受信任 / 主机名不符：下面用记录型再连一次
        } catch (e: Exception) {
            throw NetErrors.map(e)
        } finally {
            systemClient.connectionPool.evictAll()
        }
        // 2. 记录型：接受任何证书，记下叶子证书的指纹交给用户核对（这条连接只发 hello，不复用）
        val rec = RecordingTrustManager()
        val recClient = baseBuilder()
            .sslSocketFactory(Tls.socketFactory(rec), rec)
            .hostnameVerifier { _, _ -> true }
            .build()
        try {
            val raw = call(recClient, helloRequest(url))
            val leaf = raw.leaf ?: rec.leaf ?: throw NetErrors.network(NetErrors.REASON_TLS, "the server presented no certificate")
            val fp = Fingerprints.of(leaf)
            val hello = ServerHello.check(raw.code, raw.body, raw.headers)
            return ProbeResult(url, fp, Fingerprints.display(fp), false, hello)
        } finally {
            recClient.connectionPool.evictAll()
        }
    }

    /** 按固定指纹（或 CA）取 hello：确认证书没变、是柠檬纸飞机、协议版本相同 */
    suspend fun hello(url: String, fingerprint: String, trusted: Boolean = false): ServerHello {
        val u = ServerAddress.normalize(url) ?: throw ApiException(ApiException.USER_ERROR, "invalid address: $url")
        val client = pinnedClient(fingerprint, trusted)
        try {
            val raw = call(client, helloRequest(u))
            return ServerHello.check(raw.code, raw.body, raw.headers)
        } finally {
            client.connectionPool.evictAll()
        }
    }

    /**
     * 账号密码换访问密钥（device 是设备名，服务器的「已登录的设备」里显示）。按 [fingerprint] 固定证书（trusted 时按 CA）。
     */
    suspend fun login(url: String, fingerprint: String, trusted: Boolean, user: String, password: String, device: String): LoginResult {
        val u = ServerAddress.normalize(url) ?: throw ApiException(ApiException.USER_ERROR, "invalid address: $url")
        val client = pinnedClient(fingerprint, trusted)
        try {
            val h = call(client, helloRequest(u))
            ServerHello.check(h.code, h.body, h.headers)
            val body = buildJsonObject {
                put("user", user)
                put("password", password)
                put("device", device)
            }.toString().toRequestBody("application/json".toMediaType())
            val raw = call(client, Request.Builder().url("$u/api/v1/auth/login").post(body).build())
            when (raw.code) {
                200 -> {}
                401 -> throw ApiException(
                    ApiException.UNAUTHORIZED, "wrong account or password", 401,
                    buildJsonObject { put("reason", NetErrors.REASON_BAD_LOGIN) },
                )
                403 -> {
                    val code = (NetErrors.parseObject(raw.body)?.get("error") as? JsonObject)?.string("code")
                    if (code == ApiException.PASSWORD_DISABLED) {
                        throw ApiException(ApiException.PASSWORD_DISABLED, "password sign-in is not enabled on this server", 403)
                    }
                    throw NetErrors.http(raw.code, raw.body, raw.headers)
                }
                else -> throw NetErrors.http(raw.code, raw.body, raw.headers)
            }
            val o = NetErrors.parseObject(raw.body)
            val token = o?.string("token").orEmpty()
            if (token.isEmpty()) throw ApiException(ApiException.BAD_RESPONSE, "unexpected sign-in response", raw.code)
            return LoginResult(token, o?.string("keyId").takeUnless { it.isNullOrEmpty() } ?: ConnectionCode.keyIdOf(token))
        } finally {
            client.connectionPool.evictAll()
        }
    }

    /**
     * 用连接码连接：[firstAddress] 是用户改过的地址（放在最前），其余按连接码里的顺序。成功返回要保存的内容。
     */
    suspend fun connectWithCode(code: ConnectionCode, firstAddress: String? = null): CodeConnection {
        val target = ServerTarget.of(listOfNotNull(firstAddress?.takeIf { it.isNotBlank() }) + code.urls, code.fingerprint, trusted = false)
        val client = RemoteClient(
            target, code.token,
            RemoteClient.Options(connectTimeoutMs = options.connectTimeoutMs, logger = options.logger, systemTrust = options.systemTrust),
        )
        try {
            val info = client.connect()
            return CodeConnection(target, client.activeUrl ?: target.urls.first(), code.token, code.keyId, info)
        } finally {
            client.close()
        }
    }

    private fun pinnedClient(fingerprint: String, trusted: Boolean): OkHttpClient =
        TlsPolicy(fingerprint, trusted, options.systemTrust).apply(baseBuilder()).build()

    private fun helloRequest(url: String) = Request.Builder().url("$url/api/v1/auth/hello").get().build()

    private suspend fun call(client: OkHttpClient, req: Request): RawResponse = try {
        client.newCall(req).awaitRaw()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw NetErrors.map(e)
    }
}
