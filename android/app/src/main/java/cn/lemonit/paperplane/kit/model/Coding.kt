package cn.lemonit.paperplane.kit.model

import cn.lemonit.paperplane.kit.PaperplaneJson
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// 与内核（Go encoding/json）对接的编解码细节，对照 Mac PaperplaneKit/Util/Coding.swift、JSONValue.swift。
//
// - 时间：Go 的 time.Time 是 RFC 3339（可能带 1–9 位小数秒、带时区），零值 0001-01-01T00:00:00Z 表示「没有」。
//   模型里的时间字段一律是 java.time.Instant?，文件头用 @file:UseSerializers(InstantSerializer::class)。
// - 缺字段 / null：PaperplaneJson 开了 coerceInputValues + explicitNulls=false，非空字段遇到 null 或缺失时取默认值
//   （对应 Mac 的 @DefString/@DefBool/@DefArray…）。
// - 字典：Go 的 map[string][]T 里 nil 切片输出为 null，用 LenientMap（见下）。
// - 「枚举」字段（状态、种类、严重度等）一律是 String，常量放在同名 object 里（RunState、Severity…），
//   内核以后加新值时整条照样能解析，界面按未知值兜底显示。

/** Go 时间的解析与格式化。 */
object GoTime {
    private val output: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneOffset.UTC)

    /** 解析 RFC 3339（带或不带小数秒，小数秒 1–9 位，时区 Z 或 ±hh:mm）；解析不了返回 null。 */
    fun parse(raw: String?): Instant? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        return try {
            OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** 毫秒精度、UTC：2026-11-18T01:39:12.123Z */
    fun format(d: Instant): String = output.format(d)

    /** Go 零值时间（0001-01-01）或 null 视为「没有」。 */
    fun isZero(d: Instant?): Boolean = d == null || d.epochSecond < -62_000_000_000L
}

/** Instant ⇄ Go 时间字符串。解析不了时报错（和 Mac 一致）。 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("cn.lemonit.paperplane.kit.GoTime", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant {
        val s = decoder.decodeString()
        return GoTime.parse(s) ?: throw SerializationException("无法解析时间：$s")
    }

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(GoTime.format(value))
    }
}

/**
 * 容错字典（对应 Mac @DefDict）：整个字典是 null 时为空字典；某一项是 null 时，
 * 值是数组 / 字典的换成空值，其他类型丢掉这一项。键只支持字符串（JSON 对象的键）。
 */
class LenientMapSerializer<K, V>(
    private val keySerializer: KSerializer<K>,
    private val valueSerializer: KSerializer<V>,
) : KSerializer<Map<K, V>> {
    private val delegate = MapSerializer(keySerializer, valueSerializer)
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): Map<K, V> {
        val jd = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val obj = jd.decodeJsonElement() as? JsonObject ?: return emptyMap()
        val kind = valueSerializer.descriptor.kind
        val out = LinkedHashMap<K, V>(obj.size)
        for ((k, v) in obj) {
            val value: JsonElement = if (v is JsonNull) {
                when (kind) {
                    StructureKind.LIST -> JsonArray(emptyList())
                    StructureKind.MAP -> JsonObject(emptyMap())
                    else -> continue
                }
            } else {
                v
            }
            out[jd.json.decodeFromJsonElement(keySerializer, JsonPrimitive(k))] =
                jd.json.decodeFromJsonElement(valueSerializer, value)
        }
        return out
    }

    override fun serialize(encoder: Encoder, value: Map<K, V>) = delegate.serialize(encoder, value)
}

/** 字符串键的容错字典，模型字段写 `val env: LenientMap<String> = emptyMap()`。 */
typealias LenientMap<V> = @Serializable(with = LenientMapSerializer::class) Map<String, V>

// ---------------------------------------------------------------- JsonElement 小工具（对应 Mac JSONValue 的扩展）

/** null 或 JsonNull */
val JsonElement?.isJsonNull: Boolean get() = this == null || this is JsonNull

/** 对象取键；不是对象时为 null（JsonObject 自带的 get 优先）。 */
operator fun JsonElement.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/** 数组取下标；不是数组或越界时为 null。 */
operator fun JsonElement.get(index: Int): JsonElement? =
    (this as? JsonArray)?.let { if (index in it.indices) it[index] else null }

/** 字符串；数字与布尔也转成字符串（整数不带 .0）。 */
val JsonElement.stringValue: String?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (p.isString) return p.content
        val d = p.content.toDoubleOrNull()
        if (d != null && d == Math.rint(d) && kotlin.math.abs(d) < 9.007e15) {
            return d.toLong().toString()
        }
        return p.content
    }

/** 数字；字符串数字与布尔（1/0）也转。 */
val JsonElement.doubleValue: Double?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (!p.isString) {
            when (p.content) {
                "true" -> return 1.0
                "false" -> return 0.0
            }
        }
        return p.content.toDoubleOrNull()
    }

val JsonElement.intValue: Int? get() = doubleValue?.toInt()
val JsonElement.longValue: Long? get() = doubleValue?.toLong()

/** 布尔；数字非 0、字符串 "true"/"1" 也算真。 */
val JsonElement.boolValue: Boolean?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (p.isString) return p.content == "true" || p.content == "1"
        return when (p.content) {
            "true" -> true
            "false" -> false
            else -> p.content.toDoubleOrNull()?.let { it != 0.0 }
        }
    }

val JsonElement.arrayValue: List<JsonElement>? get() = this as? JsonArray
val JsonElement.objectValue: Map<String, JsonElement>? get() = this as? JsonObject

/** 字符串数组（multiselect / list 字段） */
val JsonElement.stringArray: List<String>? get() = arrayValue?.mapNotNull { it.stringValue }

/** 字符串字典（keyvalue 字段） */
val JsonElement.stringMap: Map<String, String>?
    get() = objectValue?.mapNotNull { (k, v) -> v.stringValue?.let { k to it } }?.toMap()

/** 是否「空」：null、空串、空数组、空对象 */
val JsonElement?.isEmptyValue: Boolean
    get() = when (val v = this) {
        null, is JsonNull -> true
        is JsonPrimitive -> v.isString && v.content.isEmpty()
        is JsonArray -> v.isEmpty()
        is JsonObject -> v.isEmpty()
    }

/** 紧凑 JSON 文本 */
val JsonElement.jsonString: String get() = PaperplaneJson.encodeToString(JsonElement.serializer(), this)

/** 把模型编码成 JsonElement（null 字段不输出）。 */
fun <T> jsonOf(serializer: KSerializer<T>, value: T): JsonElement = PaperplaneJson.encodeToJsonElement(serializer, value)

/** 把 JsonElement 解码成模型。 */
fun <T> JsonElement.decodeAs(serializer: KSerializer<T>): T = PaperplaneJson.decodeFromJsonElement(serializer, this)
