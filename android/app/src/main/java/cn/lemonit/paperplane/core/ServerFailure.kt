package cn.lemonit.paperplane.core

import cn.lemonit.paperplane.kit.ApiException
import cn.lemonit.paperplane.net.NetErrors
import cn.lemonit.paperplane.ui.i18n.tr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 连不上服务器的原因（docs/remote.md 的错误码）。对照 Mac ServerFailure。 */
data class ServerFailure(val kind: Kind, val message: String = "", val reason: String? = null, val versions: Pair<String, String>? = null) {
    enum class Kind {
        /** 连不上 / 不是柠檬纸飞机服务器 */
        UNREACHABLE,
        /** 证书指纹和保存的不一样 */
        FINGERPRINT,
        /** 访问密钥失效（被吊销）或本机没有保存密钥 */
        UNAUTHORIZED,
        /** 失败次数太多被暂时封禁 */
        RATE_LIMITED,
        INCOMPATIBLE,
        OTHER,
    }

    /** 界面上显示的原因 */
    val text: String
        get() = when (kind) {
            Kind.UNREACHABLE -> tr("server.err.unreachable", networkReasonText(reason, message))
            Kind.FINGERPRINT -> tr("server.err.fingerprint")
            Kind.UNAUTHORIZED -> tr("server.err.unauthorized")
            Kind.RATE_LIMITED -> tr("server.err.rateLimited")
            Kind.INCOMPATIBLE -> tr("server.err.incompatible", versions?.first ?: "?", versions?.second ?: "?")
            Kind.OTHER -> message
        }

    /** 需要重新登录（换密钥）才能恢复 */
    val needsLogin: Boolean get() = kind == Kind.UNAUTHORIZED || kind == Kind.FINGERPRINT

    companion object {
        fun of(e: Throwable): ServerFailure {
            val a = e as? ApiException ?: return ServerFailure(Kind.OTHER, e.message ?: e.toString())
            val reason = a.reason
            return when (a.code) {
                ApiException.NETWORK, "unreachable" -> ServerFailure(Kind.UNREACHABLE, a.message, reason)
                ApiException.FINGERPRINT_MISMATCH -> ServerFailure(Kind.FINGERPRINT, a.message)
                ApiException.UNAUTHORIZED, "remote_unauthorized" -> ServerFailure(Kind.UNAUTHORIZED, a.message, reason)
                ApiException.BANNED, "rate_limited" -> ServerFailure(Kind.RATE_LIMITED, a.message)
                ApiException.INCOMPATIBLE -> {
                    val d = a.data as? JsonObject
                    fun s(k: String) = d?.get(k)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } ?: "?"
                    ServerFailure(Kind.INCOMPATIBLE, a.message, versions = s("serverVersion") to s("clientVersion"))
                }
                else -> ServerFailure(Kind.OTHER, a.message)
            }
        }
    }
}

/** 网络错误的原因（NetErrors.REASON_*）换成界面文案；没有对应文案时用技术细节 */
fun networkReasonText(reason: String?, fallback: String): String {
    val key = when (reason) {
        NetErrors.REASON_TIMEOUT -> "net.reason.timeout"
        NetErrors.REASON_REFUSED -> "net.reason.refused"
        NetErrors.REASON_NO_HOST -> "net.reason.noHost"
        NetErrors.REASON_UNREACHABLE -> "net.reason.unreachable"
        NetErrors.REASON_NOT_TLS -> "net.reason.notTLS"
        NetErrors.REASON_TLS -> "net.reason.tls"
        NetErrors.REASON_CERT_UNTRUSTED -> "net.reason.certUntrusted"
        NetErrors.REASON_NOT_PAPERPLANE -> "net.reason.notPaperplane"
        NetErrors.REASON_IP_DENIED -> "net.reason.ipDenied"
        NetErrors.REASON_CLOSED -> "net.reason.closed"
        NetErrors.REASON_NO_ADDRESS -> "net.reason.noAddress"
        else -> null
    }
    return key?.let { tr(it) } ?: fallback
}

/** 添加服务器、登录时的错误文案 */
fun serverErrorText(e: Throwable): String {
    val a = e as? ApiException
    if (a?.code == ApiException.PASSWORD_DISABLED) return tr("server.err.passwordDisabled")
    if (a?.code == ApiException.UNAUTHORIZED && a.reason == NetErrors.REASON_BAD_LOGIN) return tr("server.err.badLogin")
    return ServerFailure.of(e).text
}
