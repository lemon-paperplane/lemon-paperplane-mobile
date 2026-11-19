package cn.lemonit.paperplane.kit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

// kit 与 net 之间的约定（两边并行开发，改这里要同时改两边）：
//   net.RemoteClient 实现 OpCaller（POST /api/v1/call/<op>，解信封）；
//   kit 里所有强类型操作都写成 OpCaller 的扩展函数（对照 Mac PaperplaneKit/API/Operations.swift）。

/** 服务器返回的错误，或网络错误（code = "network"）。对照 Mac APIError。 */
class ApiException(
    val code: String,
    override val message: String,
    val status: Int? = null,
    /** 插件给出的结构化信息，如 {"reason": "identityMissing", "missing": ["user.email"]} */
    val data: JsonElement? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** data.reason 的快捷访问 */
    val reason: String?
        get() = (data as? JsonObject)?.get("reason")?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    companion object {
        const val NETWORK = "network"
        const val BAD_RESPONSE = "bad_response"
        const val NOT_FOUND = "not_found"
        const val CANCELLED = "cancelled"
        const val USER_ERROR = "user_error"
        const val NOT_SUPPORTED = "not_supported"
        /** 访问密钥失效（被撤销、过期）：HTTP 401 */
        const val UNAUTHORIZED = "unauthorized"
        /** 失败次数太多被暂时封禁：HTTP 429 */
        const val BANNED = "banned"
        /** 服务器证书指纹和保存的不一致 */
        const val FINGERPRINT_MISMATCH = "fingerprint_mismatch"
    }
}

/** 调用一个操作。 */
interface OpCaller {
    /**
     * POST /api/v1/call/<op>，body = input。成功返回 result（结果为 null 时返回 JsonNull），
     * 失败抛 ApiException。timeoutMs 为空用客户端默认（5 分钟）。
     */
    suspend fun callRaw(op: String, input: JsonElement = JsonObject(emptyMap()), timeoutMs: Long? = null): JsonElement
}

/** 全 App 共用的 JSON 配置：未知字段忽略、缺字段用默认值、null 遇到非空字段用默认值。 */
val PaperplaneJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
    isLenient = true
}

/** 调用并解码结果。 */
suspend inline fun <reified T> OpCaller.call(op: String, input: JsonElement = JsonObject(emptyMap()), timeoutMs: Long? = null): T =
    try {
        PaperplaneJson.decodeFromJsonElement<T>(callRaw(op, input, timeoutMs))
    } catch (e: ApiException) {
        throw e
    } catch (e: kotlinx.serialization.SerializationException) {
        throw ApiException(ApiException.BAD_RESPONSE, "$op: ${e.message}", cause = e)
    } catch (e: IllegalArgumentException) {
        throw ApiException(ApiException.BAD_RESPONSE, "$op: ${e.message}", cause = e)
    }

/** 调用但不关心结果。 */
suspend fun OpCaller.send(op: String, input: JsonElement = JsonObject(emptyMap()), timeoutMs: Long? = null) {
    callRaw(op, input, timeoutMs)
}

/** 小工具：去掉值为 null 的键（对照 Mac jsonObject）。 */
fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.mapNotNull { (k, v) -> toJsonElement(v)?.let { k to it } }.toMap()
)

fun toJsonElement(v: Any?): JsonElement? = when (v) {
    null -> null
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.mapNotNull { (k, x) -> toJsonElement(x)?.let { k.toString() to it } }.toMap())
    is Iterable<*> -> kotlinx.serialization.json.JsonArray(v.mapNotNull { toJsonElement(it) })
    is Array<*> -> kotlinx.serialization.json.JsonArray(v.mapNotNull { toJsonElement(it) })
    else -> error("unsupported json value: ${v::class}")
}

/** JsonObject 合并（右边覆盖左边） */
operator fun JsonObject.plus(other: JsonObject): JsonObject = JsonObject(this.jsonObject.toMap() + other.toMap())
