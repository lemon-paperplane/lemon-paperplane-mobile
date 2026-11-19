package cn.lemonit.paperplane.kit

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

// 测试共用：读夹具（src/test/resources/fixtures，Mac 版的 Fixtures 原样拷来，kernel/ 是按内核 internal/ops 手写的）、
// 解 {"result"}/{"error"} 信封（真实环境里由 net.RemoteClient 做）、假的 OpCaller、简易 SSE 分帧。

object Fixtures {
    fun text(name: String): String {
        val url = Fixtures::class.java.classLoader!!.getResource("fixtures/$name") ?: error("找不到夹具 $name")
        return url.readText(Charsets.UTF_8)
    }

    /** 解信封：有 error 时抛 ApiException，否则返回 result（没有 result 时为 JsonNull） */
    fun result(name: String, status: Int = 200): JsonElement = envelope(text(name), status)

    fun envelope(body: String, status: Int = 200): JsonElement {
        val obj = PaperplaneJson.parseToJsonElement(body).jsonObject
        val err = obj["error"] as? JsonObject
        if (err != null) {
            val code = (err["code"] as? JsonPrimitive)?.content ?: "internal"
            val message = (err["message"] as? JsonPrimitive)?.content ?: ""
            throw ApiException(code, message, status, err["data"])
        }
        return obj["result"] ?: JsonNull
    }

    fun <T> decode(name: String, d: DeserializationStrategy<T>): T = decodeResult(name, d, result(name))
}

/** 记录调用并返回预设结果的 OpCaller */
class FakeOpCaller(private val responder: (op: String, input: JsonObject) -> JsonElement = { _, _ -> JsonObject(emptyMap()) }) : OpCaller {
    data class Call(val op: String, val input: JsonObject, val timeoutMs: Long?)

    val calls = mutableListOf<Call>()
    val last: Call get() = calls.last()

    override suspend fun callRaw(op: String, input: JsonElement, timeoutMs: Long?): JsonElement {
        val obj = input as? JsonObject ?: error("输入应是对象：$input")
        calls.add(Call(op, obj, timeoutMs))
        return responder(op, obj)
    }

    companion object {
        /** 按 op 返回夹具：map 里 op → 夹具文件名；其他操作返回 {}（对象类结果全取默认值） */
        fun fixtures(map: Map<String, String>): FakeOpCaller =
            FakeOpCaller { op, _ -> map[op]?.let { Fixtures.result(it) } ?: JsonObject(emptyMap()) }
    }
}

fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

/** 极简 SSE 分帧：返回 (event 名, data) 列表，注释行（: ping）忽略，多行 data 用 \n 连接 */
fun sseMessages(text: String): List<Pair<String?, String>> {
    val out = mutableListOf<Pair<String?, String>>()
    var event: String? = null
    val data = StringBuilder()
    var hasData = false
    for (raw in (text + "\n\n").split("\n")) {
        val line = raw.removeSuffix("\r")
        if (line.isEmpty()) {
            if (hasData) out.add(event to data.toString())
            event = null
            data.setLength(0)
            hasData = false
            continue
        }
        if (line.startsWith(":")) continue
        val name = line.substringBefore(':')
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (name) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
    }
    return out
}
