@file:UseSerializers(InstantSerializer::class)

package cn.lemonit.paperplane.kit.model

import cn.lemonit.paperplane.kit.PaperplaneJson
import cn.lemonit.paperplane.kit.util.ServerURL
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.builtins.ListSerializer

// 远程服务器（docs/remote.md）：服务器端 remote.*、fs.browse 的输入输出；以及连接码、hello 等客户端要用的结构。
// 对照 Mac PaperplaneKit/Models/RemoteModels.swift。gateway.* 是桌面内核的网关，手机直连服务器用不到这些操作，
// 模型（GatewayOpen 等）保留给对照与测试。

/** 连接远程服务器时运行地址的打开方式：转发到本机（forward）和/或用服务器地址直接打开（direct）。缺字段按 true。 */
@Serializable
data class RemoteLinks(
    val forward: Boolean = true,
    val direct: Boolean = true,
) {
    companion object {
        /** 缺省：两种都要 */
        val BOTH = RemoteLinks()

        /** 没有记录 = 两种都要；两种都关的脏数据也按两种都要 */
        fun resolve(l: RemoteLinks?): RemoteLinks = if (l == null || (!l.forward && !l.direct)) BOTH else l
    }
}

// ---------------------------------------------------------------- 服务器端（remote.*）

/** remote.status / remote.configure */
@Serializable
data class RemoteStatus(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val error: String = "",
    val listen: String = "",
    val port: Int = 0,
    val addresses: List<String> = emptyList(),
    val fingerprint: String = "",
    val fingerprintDisplay: String = "",
    val customCert: Boolean = false,
    val passwordLogin: Boolean = false,
    val autostart: Boolean = false,
    val autostartSupported: Boolean = false,
    val allowCIDRs: List<String> = emptyList(),
    val keys: Int = 0,
    val users: Int = 0,
    val hostname: String = "",
)

/** 访问密钥（kind = key：手动生成；device：账号密码登录时发的） */
@Serializable
data class RemoteKey(
    val id: String = "",
    val name: String = "",
    val kind: String = "",
    val user: String = "",
    val createdAt: Instant? = null,
    val lastUsedAt: Instant? = null,
    val lastIP: String = "",
    /** 是不是当前请求用的这条（= 本设备） */
    val current: Boolean = false,
) {
    val isDevice: Boolean get() = kind == "device"
}

/** remote.keyCreate：token、code、qrSvg 只返回这一次 */
@Serializable
data class RemoteKeyCreated(
    val key: RemoteKey = RemoteKey(),
    val token: String = "",
    /** 连接码 ppc1_… */
    val code: String = "",
    /** 连接码的二维码 SVG（手机扫码添加服务器） */
    val qrSvg: String = "",
)

@Serializable
data class RemoteUser(
    val name: String = "",
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    val id: String get() = name
}

/** remote.audit 的一条（新的在前） */
@Serializable
data class RemoteAuditEntry(
    val time: Instant? = null,
    /** 见 RemoteAuditEvent */
    val event: String = "",
    /** 远程来源；本机操作为空 */
    val ip: String = "",
    /** 密钥名或账号 */
    val who: String = "",
    /** 操作名、端口等（不记参数） */
    val detail: String = "",
)

/** RemoteAuditEntry.event 的取值 */
object RemoteAuditEvent {
    const val LOGIN_OK = "login.ok"
    const val LOGIN_FAIL = "login.fail"
    const val AUTH_FAIL = "auth.fail"
    const val BAN = "ban"
    const val KEY_CREATE = "key.create"
    const val KEY_REVOKE = "key.revoke"
    const val USER_SET = "user.set"
    const val USER_REMOVE = "user.remove"
    const val CONFIG = "config"
    const val OP = "op"
    const val TUNNEL = "tunnel"
}

// ---------------------------------------------------------------- 连接码、hello 与网关

/** 连接码解出来的内容（gateway.decode 的输出，手机上由 net.ConnectionCode 自己解） */
@Serializable
data class GatewayCode(
    val urls: List<String> = emptyList(),
    val fingerprint: String = "",
    val token: String = "",
    val name: String = "",
)

/** 服务器 GET /api/v1/auth/hello */
@Serializable
data class ServerHello(
    val name: String = "",
    val hostname: String = "",
    val version: String = "",
    val apiVersion: Int = 0,
    val os: String = "",
    val arch: String = "",
    val passwordLogin: Boolean = false,
)

/** gateway.probe（桌面内核） */
@Serializable
data class GatewayProbe(
    val url: String = "",
    val fingerprint: String = "",
    val fingerprintDisplay: String = "",
    val trusted: Boolean = false,
    val hello: ServerHello? = null,
)

/** gateway.open（桌面内核）：port / token 用法和本机 runtime.json 一样；info 是服务器的 system.info */
@Serializable
data class GatewayOpen(
    val id: String = "",
    val port: Int = 0,
    val token: String = "",
    val info: SystemInfo? = null,
) {
    /** 经网关访问服务器的地址（回环端口） */
    val baseUrl: String get() = "http://127.0.0.1:$port"
}

/** gateway.forward（桌面内核） */
@Serializable
data class GatewayForward(
    val localPort: Int = 0,
    val url: String = "",
)

// ---------------------------------------------------------------- fs.browse

@Serializable
data class FSBrowseEntry(
    val name: String = "",
    val path: String = "",
    val dir: Boolean = false,
    val hidden: Boolean = false,
    val size: Long? = null,
    val modTime: Instant? = null,
) {
    val id: String get() = path
}

@Serializable
data class FSBrowseResult(
    val path: String = "",
    /** 已在根目录时为空 */
    val parent: String = "",
    val home: String = "",
    val sep: String = "",
    /** Windows 盘符；其他系统为 ["/"] */
    val roots: List<String> = emptyList(),
    val entries: List<FSBrowseEntry> = emptyList(),
)

// ---------------------------------------------------------------- 客户端保存的服务器

/** 保存的一台服务器（访问密钥另存，不在这里） */
@Serializable
data class SavedServer(
    val id: String = UUID.randomUUID().toString().lowercase(),
    val name: String = "",
    val url: String = "",
    val fingerprint: String = "",
    val trusted: Boolean = false,
    val os: String = "",
    val hostname: String = "",
) {
    /** 地址里的主机名（运行地址换成服务器地址时用；IPv6 带方括号） */
    val host: String get() = ServerURL.host(url)

    /** 地址显示：去掉 https:// 与末尾斜杠 */
    val displayAddress: String get() = url.removePrefix("https://").removeSuffix("/")

    /** 指纹前 8 位（去掉 sha256: 前缀） */
    val shortFingerprint: String get() = shortFingerprint(fingerprint)

    /** 同一台服务器：地址相同且指纹相同（CA 证书的不比指纹） */
    fun isSameServer(url: String, fingerprint: String): Boolean =
        ServerURL.normalize(this.url) == ServerURL.normalize(url) &&
            (trusted || fingerprint.isEmpty() || this.fingerprint.equals(fingerprint, ignoreCase = true))

    companion object {
        fun shortFingerprint(fp: String): String = fp.substringAfter(':').take(8).uppercase()
    }
}

/** 服务器列表的 JSON 编解码 */
object SavedServerList {
    private val serializer = ListSerializer(SavedServer.serializer())

    fun decode(json: String?): List<SavedServer> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { PaperplaneJson.decodeFromString(serializer, json) }.getOrDefault(emptyList())
    }

    fun encode(list: List<SavedServer>): String = PaperplaneJson.encodeToString(serializer, list)
}
