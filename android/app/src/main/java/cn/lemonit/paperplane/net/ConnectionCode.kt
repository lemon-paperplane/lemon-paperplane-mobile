package cn.lemonit.paperplane.net

import cn.lemonit.paperplane.kit.PaperplaneJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Base64

/**
 * 连接码：`ppc1_` + base64url(JSON `{"u":[地址…],"f":"sha256:…","k":"ppk_…","n":"服务器名"}`)，无填充。
 * 规则和内核 `remote.EncodeCode / DecodeCode` 一致。扫码得到的就是这段文本。
 */
data class ConnectionCode(
    /** 服务器猜到的所有地址（原样，按顺序试；连接时再规范化） */
    val urls: List<String>,
    /** 证书指纹 "sha256:…" */
    val fingerprint: String,
    /** 访问密钥 ppk_<编号>_<随机> */
    val token: String,
    /** 服务器名（可能为空） */
    val name: String = "",
) {
    /** 访问密钥编号（ppk_ 后面那 8 位），服务器的密钥列表里用它标识这条密钥 */
    val keyId: String get() = parseToken(token)?.first ?: ""

    /** 规范化、去重后的地址 */
    val normalizedUrls: List<String> get() = ServerAddress.normalizeAll(urls)

    override fun toString(): String = "ConnectionCode(urls=$urls, fingerprint=$fingerprint, token=<hidden>, name=$name)"

    /** 连接码解析失败的原因 */
    class InvalidException(val kind: Kind, message: String) : IllegalArgumentException(message) {
        enum class Kind {
            /** 不是 ppc1_ 开头 */
            PREFIX,
            /** 不完整（被截断、base64 或 JSON 不对、缺字段） */
            INCOMPLETE,
            /** 里面的访问密钥格式不对 */
            KEY,
        }
    }

    companion object {
        const val PREFIX = "ppc1_"
        private const val TOKEN_PREFIX = "ppk_"

        /** 解析连接码（容忍任意位置的空格、换行）；不合法时抛 [InvalidException] */
        fun decode(code: String): ConnectionCode {
            val c = code.filterNot { it.isWhitespace() }
            if (!c.startsWith(PREFIX)) throw InvalidException(InvalidException.Kind.PREFIX, "not a connection code")
            val bytes = try {
                Base64.getUrlDecoder().decode(c.substring(PREFIX.length).also { require('=' !in it) })
            } catch (e: IllegalArgumentException) {
                throw InvalidException(InvalidException.Kind.INCOMPLETE, "connection code is incomplete")
            }
            val obj = try {
                PaperplaneJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: throw InvalidException(InvalidException.Kind.INCOMPLETE, "connection code is incomplete")
            val urls = (obj["u"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            val fp = obj.string("f").orEmpty()
            val token = obj.string("k").orEmpty()
            val name = obj.string("n").orEmpty()
            if (urls.isNullOrEmpty() || token.isEmpty() || fp.isEmpty()) {
                throw InvalidException(InvalidException.Kind.INCOMPLETE, "connection code is incomplete")
            }
            if (parseToken(token) == null) throw InvalidException(InvalidException.Kind.KEY, "the access key in the code is invalid")
            return ConnectionCode(urls, fp, token, name)
        }

        fun decodeOrNull(code: String): ConnectionCode? = try {
            decode(code)
        } catch (e: InvalidException) {
            null
        }

        /** 粘贴板 / 扫码内容看起来像连接码（用于自动填入） */
        fun looksLikeCode(text: String): Boolean = text.trim().startsWith(PREFIX)

        /** 生成连接码（和 Go 的 json.Marshal 字段顺序一致；测试与调试用） */
        fun encode(c: ConnectionCode): String {
            val obj = buildJsonObject {
                putJsonArray("u") { c.urls.forEach { add(JsonPrimitive(it)) } }
                put("f", c.fingerprint)
                put("k", c.token)
                if (c.name.isNotEmpty()) put("n", c.name)
            }
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(obj.toString().toByteArray())
        }

        /** ppk_<8 位编号>_<40~64 位随机>，返回 (编号, 随机部分)；同内核 parseToken */
        internal fun parseToken(tok: String): Pair<String, String>? {
            if (!tok.startsWith(TOKEN_PREFIX)) return null
            val rest = tok.substring(TOKEN_PREFIX.length)
            val i = rest.indexOf('_')
            if (i < 0) return null
            val id = rest.substring(0, i)
            val secret = rest.substring(i + 1)
            if (id.length != 8 || secret.length < 40 || secret.length > 64) return null
            return Pair(id, secret)
        }

        /** 访问密钥的编号（不合法返回空串） */
        fun keyIdOf(token: String): String = parseToken(token)?.first ?: ""
    }
}
