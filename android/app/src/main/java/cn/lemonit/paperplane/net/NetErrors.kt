package cn.lemonit.paperplane.net

import cn.lemonit.paperplane.kit.ApiException
import cn.lemonit.paperplane.kit.PaperplaneJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.Headers
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * 网络层抛出的 ApiException 的约定（界面按 code + reason 选文案，message 只是技术细节）：
 *
 * | code | 含义 | data |
 * |---|---|---|
 * | `network` | 连不上 / 断了 / 超时 / 不是柠檬纸飞机服务器 | `{"reason": 见 [NetErrors] 的 REASON_*}` |
 * | `fingerprint_mismatch` | 服务器证书和保存的不一样 | `{"fingerprint": 服务器现在的指纹}` |
 * | `unauthorized` | HTTP 401：访问密钥失效（被吊销、删除）；登录时 = 账号或密码不对（reason=badLogin） | 服务器给的 |
 * | `banned` | HTTP 429：失败次数太多被暂时封禁 | `{"retryAfter": 秒}`（服务器给了 Retry-After 时） |
 * | `incompatible` | 服务器协议版本不同 | `{"serverVersion", "apiVersion"}` |
 * | `password_disabled` | 服务器没开账号密码登录 | — |
 * | `bad_response` | 响应不是预期格式 | — |
 * | `not_found` 等 | 服务器信封里的错误码原样透传（`user_error` 的 message 可直接展示） | 插件给的 |
 * | `http_<状态>` | 非信封的 HTTP 错误 | — |
 */
object NetErrors {
    const val REASON_TIMEOUT = "timeout"
    const val REASON_REFUSED = "refused"
    const val REASON_NO_HOST = "noHost"
    const val REASON_UNREACHABLE = "unreachable"
    const val REASON_NOT_TLS = "notTLS"
    const val REASON_TLS = "tls"
    /** 证书不受信任或主机名不符（trusted 模式下） */
    const val REASON_CERT_UNTRUSTED = "certUntrusted"
    /** 对方不是柠檬纸飞机服务器（hello 不对） */
    const val REASON_NOT_PAPERPLANE = "notPaperplane"
    /** 服务器的 IP 白名单拒绝了本机（HTTP 403 forbidden） */
    const val REASON_IP_DENIED = "ipDenied"
    /** 连接被对方关闭（事件流等长连接） */
    const val REASON_CLOSED = "closed"
    /** 服务器条目里没有可用地址 */
    const val REASON_NO_ADDRESS = "noAddress"
    const val REASON_IO = "io"
    /** 登录：账号或密码不对（code = unauthorized） */
    const val REASON_BAD_LOGIN = "badLogin"

    /** IO / TLS 异常 → ApiException；协程取消不要传进来（调用方先 rethrow CancellationException） */
    fun map(e: Throwable): ApiException {
        if (e is ApiException) return e
        causeOf<FingerprintMismatchException>(e)?.let {
            return ApiException(
                ApiException.FINGERPRINT_MISMATCH, "the server certificate differs from the saved one",
                data = buildJsonObject { put("fingerprint", it.actual) }, cause = e,
            )
        }
        val msg = e.message ?: e.javaClass.simpleName
        val reason = when {
            causeOf<SocketTimeoutException>(e) != null -> REASON_TIMEOUT
            e is InterruptedIOException && msg == "timeout" -> REASON_TIMEOUT
            causeOf<UnknownHostException>(e) != null -> REASON_NO_HOST
            causeOf<NoRouteToHostException>(e) != null -> REASON_UNREACHABLE
            causeOf<ConnectException>(e) != null ->
                if (chainMessages(e).contains("unreachable", ignoreCase = true)) REASON_UNREACHABLE else REASON_REFUSED
            e is SSLPeerUnverifiedException -> REASON_CERT_UNTRUSTED
            e is SSLHandshakeException && causeOf<CertificateException>(e) != null -> REASON_CERT_UNTRUSTED
            e is SSLException && looksNotTls(chainMessages(e)) -> REASON_NOT_TLS
            e is SSLException -> REASON_TLS
            else -> REASON_IO
        }
        return network(reason, msg, e)
    }

    fun network(reason: String, message: String, cause: Throwable? = null): ApiException =
        ApiException(ApiException.NETWORK, message, data = buildJsonObject { put("reason", reason) }, cause = cause)

    /** 把 HTTP 状态与响应体换成错误（2xx 以外，或 2xx 但内容不对时由调用方另行处理）。 */
    fun http(status: Int, body: ByteArray, headers: Headers? = null): ApiException {
        val obj = parseObject(body)
        val err = obj?.get("error") as? JsonObject
        val code = err?.string("code")
        val message = err?.string("message")?.takeIf { it.isNotEmpty() }
        val data = err?.get("data")?.takeUnless { it is JsonNull }
        return when {
            status == 401 -> ApiException(ApiException.UNAUTHORIZED, message ?: "invalid or revoked access key", status, data)
            status == 429 -> ApiException(ApiException.BANNED, message ?: "too many failed attempts", status, retryAfterData(headers))
            status == 403 && (code == null || code == "forbidden") ->
                ApiException(ApiException.NETWORK, message ?: "forbidden", status, buildJsonObject { put("reason", REASON_IP_DENIED) })
            code != null -> ApiException(code, message ?: "", status, data)
            status == 404 -> ApiException(ApiException.NOT_FOUND, body.preview().ifEmpty { "not found" }, status)
            else -> ApiException("http_$status", body.preview().ifEmpty { "HTTP $status" }, status)
        }
    }

    private fun retryAfterData(headers: Headers?): JsonElement? {
        val s = headers?.get("Retry-After")?.trim()?.toIntOrNull() ?: return null
        return buildJsonObject { put("retryAfter", s) }
    }

    internal fun parseObject(body: ByteArray): JsonObject? = try {
        PaperplaneJson.parseToJsonElement(body.decodeToString()) as? JsonObject
    } catch (e: Exception) {
        null
    }

    private fun ByteArray.preview(): String = decodeToString(0, minOf(size, 500)).trim()

    private fun looksNotTls(m: String): Boolean =
        m.contains("not an SSL/TLS record", true) || m.contains("Unrecognized SSL message", true) ||
            m.contains("plaintext connection", true) || m.contains("WRONG_VERSION_NUMBER", true) ||
            m.contains("http_request", true)

    private fun chainMessages(e: Throwable): String {
        val sb = StringBuilder()
        var c: Throwable? = e
        var depth = 0
        while (c != null && depth++ < 10) {
            sb.append(c.message ?: "").append(' ')
            c = c.cause
        }
        return sb.toString()
    }

    private inline fun <reified T : Throwable> causeOf(e: Throwable): T? {
        var c: Throwable? = e
        var depth = 0
        while (c != null && depth++ < 10) {
            if (c is T) return c
            c = c.cause
        }
        return null
    }
}

/** 封禁剩余秒数（code = banned 且服务器给了 Retry-After 时） */
val ApiException.retryAfterSeconds: Int?
    get() = ((data as? JsonObject)?.get("retryAfter") as? JsonPrimitive)?.intOrNull

/** 证书不符时服务器现在的指纹 */
val ApiException.actualFingerprint: String?
    get() = (data as? JsonObject)?.string("fingerprint")

/** 密钥失效、被封禁、证书变了、版本不兼容：换地址或重试都没用，要用户处理（重新登录 / 稍后再试 / 升级） */
val ApiException.needsUserAction: Boolean
    get() = code == ApiException.UNAUTHORIZED || code == ApiException.BANNED ||
        code == ApiException.FINGERPRINT_MISMATCH || code == ApiException.INCOMPATIBLE

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let {
    it.intOrNull ?: it.contentOrNull?.toIntOrNull()
}

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let {
    when (it.contentOrNull) {
        "true" -> true
        "false" -> false
        else -> null
    }
}
