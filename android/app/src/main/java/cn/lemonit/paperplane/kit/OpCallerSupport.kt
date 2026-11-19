package cn.lemonit.paperplane.kit

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

// 强类型操作（Operations.kt 等）共用的调用工具。补充 OpCaller.kt 的约定，不改它的签名。

/** 常用超时（毫秒），对照 Mac 版各操作的 timeout */
object OpTimeout {
    const val SECONDS_10 = 10_000L
    const val SECONDS_30 = 30_000L
    const val MINUTE = 60_000L
    const val MINUTES_2 = 120_000L
    const val MINUTES_5 = 300_000L
    const val MINUTES_10 = 600_000L
    const val MINUTES_15 = 900_000L
    const val MINUTES_30 = 1_800_000L
    const val HOUR = 3_600_000L
}

/**
 * 调用并用指定的反序列化器解码。结果为 null（Go 的 nil 切片 / 空输出）且给了 ifNull 时返回 ifNull()；
 * 解码失败包成 ApiException(BAD_RESPONSE)，和 OpCaller.call 一致。
 */
suspend fun <T> OpCaller.callAs(
    op: String,
    deserializer: DeserializationStrategy<T>,
    input: JsonElement = JsonObject(emptyMap()),
    timeoutMs: Long? = null,
    ifNull: (() -> T)? = null,
): T {
    val raw = callRaw(op, input, timeoutMs)
    if (raw is JsonNull && ifNull != null) return ifNull()
    return decodeResult(op, deserializer, raw)
}

/** 结果是数组的操作：null 视为空数组（对照 Mac 的 NullDecodable） */
suspend fun <T> OpCaller.callList(
    op: String,
    item: KSerializer<T>,
    input: JsonElement = JsonObject(emptyMap()),
    timeoutMs: Long? = null,
): List<T> = callAs(op, ListSerializer(item), input, timeoutMs) { emptyList() }

/** 把一个结果 JsonElement 解成模型；失败抛 ApiException(BAD_RESPONSE)。 */
fun <T> decodeResult(op: String, deserializer: DeserializationStrategy<T>, element: JsonElement): T =
    try {
        PaperplaneJson.decodeFromJsonElement(deserializer, element)
    } catch (e: IllegalArgumentException) { // 含 SerializationException
        throw ApiException(ApiException.BAD_RESPONSE, "$op: ${e.message}", cause = e)
    }

/** 把参数模型编码成 JsonObject（null 字段不输出），再合并额外的键（右边覆盖左边）。 */
fun <A> argsObject(serializer: KSerializer<A>, args: A, vararg extra: Pair<String, Any?>): JsonObject {
    val base = PaperplaneJson.encodeToJsonElement(serializer, args) as? JsonObject ?: JsonObject(emptyMap())
    return base + jsonObjectOf(*extra)
}
