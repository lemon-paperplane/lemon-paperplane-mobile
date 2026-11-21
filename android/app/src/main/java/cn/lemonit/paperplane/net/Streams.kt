package cn.lemonit.paperplane.net

import cn.lemonit.paperplane.kit.ApiException
import cn.lemonit.paperplane.kit.PaperplaneJson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * 三种长连接的公共部分。两种用法任选其一（不要混用）：
 * - [start] / [stop]：回调在后台线程；切后台时 stop，切回前台 start，会从记住的位置续传；
 * - [updates]：收集时连接、取消收集时断开，同样从记住的位置续传；流结束（end / closed / 不再重连的断开）时 Flow 完成。
 */
abstract class RemoteStream<U : Any> internal constructor(protected val client: RemoteClient) {
    private val lock = Any()
    private var conn: SseConnection? = null

    /** 开始（已在运行则先停掉再从当前位置重新连） */
    fun start(listener: (U) -> Unit) {
        synchronized(lock) {
            conn?.stop()
            conn = connection(listener).also { it.start() }
        }
    }

    fun stop() {
        synchronized(lock) {
            conn?.stop()
            conn = null
        }
    }

    val isRunning: Boolean get() = synchronized(lock) { conn?.isRunning == true }

    fun updates(): Flow<U> = callbackFlow {
        start { u ->
            trySend(u)
            if (isFinal(u)) channel.close()
        }
        awaitClose { stop() }
    }.buffer(Channel.UNLIMITED)

    protected abstract fun connection(listener: (U) -> Unit): SseConnection

    /** 这条更新之后不会再有更新 */
    protected abstract fun isFinal(u: U): Boolean

    protected fun parse(data: String): JsonObject? = try {
        PaperplaneJson.parseToJsonElement(data) as? JsonObject
    } catch (e: Exception) {
        null
    }
}

/**
 * 全局事件流 GET /api/v1/events。断线自动重连；每次（重）连上后先收到 type = "hello" 的事件（data 为 SystemInfo），
 * 界面借此刷新全部数据（断线期间的事件不会补发）。
 */
class EventStream(client: RemoteClient) : RemoteStream<EventStream.Update>(client) {
    sealed interface Update {
        data object Connected : Update

        /** type 见内核 model.go 的 Ev* 常量；data 没有时为 JsonNull */
        data class Event(val type: String, val data: JsonElement) : Update

        data class Disconnected(val error: ApiException, val retrying: Boolean) : Update
    }

    override fun connection(listener: (Update) -> Unit): SseConnection =
        client.sse({ "/api/v1/events" }) { ev ->
            when (ev) {
                SseConnection.Event.Open -> listener(Update.Connected)
                is SseConnection.Event.Message -> {
                    val o = parse(ev.message.data) ?: return@sse
                    val type = o.string("type") ?: return@sse
                    listener(Update.Event(type, o["data"] ?: JsonNull))
                }
                is SseConnection.Event.Disconnected -> listener(Update.Disconnected(ev.error, ev.retrying))
                SseConnection.Event.Ended -> {}
            }
        }

    override fun isFinal(u: Update) = u is Update.Disconnected && !u.retrying
}

/**
 * 控制台日志流 GET /api/v1/consoles/<consoleId>/stream?since=<seq>（consoleId = runId 或 taskId）。
 * 记住收到的最后一个已定型 seq，断线、stop 后再 start 都从它续传（不重复）；收到 event: end 后结束。
 *
 * 实时尾部（data 里的 live 键，见 [Update.Lines.live]）不推进 seq；新内核每次（重）连上后第一条消息一定带 live，
 * 所以续上后以它为准。老内核从不发 live，行为和以前一样。
 */
class ConsoleStream(client: RemoteClient, val consoleId: String, since: Long = 0) : RemoteStream<ConsoleStream.Update>(client) {
    sealed interface Update {
        data object Connected : Update

        /**
         * 同一条 SSE 消息里的已定型行与实时尾部（一次交出去，调用方先追加 lines 再替换 live，中间不要挂起）。
         *
         * @param lines 新定型的日志行：LogLine 的原始 JSON（{seq, t, s, text}），按 seq 递增，已去掉重复；可能为空（只有 live 的消息）
         * @param live 实时尾部（LogLine 的原始 JSON，seq 为 0）：null = 消息里没有 live 键，实时尾部保持不变；
         *   非 null = 整体替换（空列表 = 清空；live 键的值是 JSON null 时也按清空）
         */
        data class Lines(val lines: List<JsonObject>, val live: List<JsonObject>? = null) : Update

        /** 控制台结束（运行停止 / 任务完成），之后不会再有日志 */
        data object Ended : Update

        data class Disconnected(val error: ApiException, val retrying: Boolean) : Update
    }

    private val last = AtomicLong(since)

    /** 收到的最后一个 seq（下次连接的 since） */
    val lastSeq: Long get() = last.get()

    override fun connection(listener: (Update) -> Unit): SseConnection =
        client.sse({ "/api/v1/consoles/${encodeSegment(consoleId)}/stream?since=${last.get()}" }, endEvent = "end") { ev ->
            when (ev) {
                SseConnection.Event.Open -> listener(Update.Connected)
                is SseConnection.Event.Message -> {
                    if (ev.message.event != null) return@sse
                    val o = parse(ev.message.data) ?: return@sse
                    val arr = o["lines"] as? JsonArray
                    val out = ArrayList<JsonObject>(arr?.size ?: 0)
                    if (arr != null) {
                        for (el in arr) {
                            val line = el as? JsonObject ?: continue
                            val seq = (line["seq"] as? JsonPrimitive)?.longOrNull ?: continue
                            if (seq <= last.get()) continue
                            last.set(seq)
                            out += line
                        }
                    }
                    // 实时尾部：键存在就整体替换（不是数组时按清空），键缺省时保持不变（null）
                    val live = if (o.containsKey("live")) (o["live"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty() else null
                    if (out.isNotEmpty() || live != null) listener(Update.Lines(out, live))
                }
                is SseConnection.Event.Disconnected -> listener(Update.Disconnected(ev.error, ev.retrying))
                SseConnection.Event.Ended -> listener(Update.Ended)
            }
        }

    override fun isFinal(u: Update) = u is Update.Ended || (u is Update.Disconnected && !u.retrying)
}

/**
 * 终端输出流 GET /api/v1/terminals/<terminalId>/stream?since=<字节偏移>[&full=1]。
 * 记住收到的输出末尾偏移，断线、stop 后再 start 都从它续传（重叠部分去掉，不重复）；
 * full=1 时内存缓冲（最后 1 MB）之前的输出从服务器磁盘补齐（完整回放）。收到 event: closed（终端已关闭）后结束。
 */
class TerminalStream(
    client: RemoteClient,
    val terminalId: String,
    since: Long = 0,
    val full: Boolean = false,
) : RemoteStream<TerminalStream.Update>(client) {
    sealed interface Update {
        data object Connected : Update

        /** 终端状态（Terminal 的原始 JSON）：每次连上先来一条，退出或重新启动时再来 */
        data class State(val terminal: JsonObject) : Update

        /** 输出字节（UTF-8 可能跨条切开，按字节写进终端模拟器）；offset 是这段的起始偏移，可能大于上次末尾（中间的已被丢弃） */
        class Output(val offset: Long, val data: ByteArray) : Update {
            val end: Long get() = offset + data.size
            override fun toString() = "Output(offset=$offset, ${data.size} bytes)"
        }

        /** 终端已关闭 */
        data object Closed : Update

        data class Disconnected(val error: ApiException, val retrying: Boolean) : Update
    }

    private val end = AtomicLong(since)

    /** 已收到输出的末尾偏移（下次连接的 since） */
    val offset: Long get() = end.get()

    override fun connection(listener: (Update) -> Unit): SseConnection =
        client.sse({
            "/api/v1/terminals/${encodeSegment(terminalId)}/stream?since=${end.get()}" + if (full) "&full=1" else ""
        }, endEvent = "closed") { ev ->
            when (ev) {
                SseConnection.Event.Open -> listener(Update.Connected)
                is SseConnection.Event.Message -> {
                    val m = ev.message
                    when (m.event) {
                        "state" -> parse(m.data)?.let { listener(Update.State(it)) }
                        null -> {
                            val o = parse(m.data) ?: return@sse
                            val off = (o["o"] as? JsonPrimitive)?.longOrNull ?: return@sse
                            val d = try {
                                Base64.getDecoder().decode(o.string("d").orEmpty())
                            } catch (e: IllegalArgumentException) {
                                return@sse
                            }
                            val cur = end.get()
                            if (off + d.size <= cur) return@sse
                            val out = if (off < cur) Update.Output(cur, d.copyOfRange((cur - off).toInt(), d.size)) else Update.Output(off, d)
                            end.set(out.end)
                            listener(out)
                        }
                    }
                }
                is SseConnection.Event.Disconnected -> listener(Update.Disconnected(ev.error, ev.retrying))
                SseConnection.Event.Ended -> listener(Update.Closed)
            }
        }

    override fun isFinal(u: Update) = u is Update.Closed || (u is Update.Disconnected && !u.retrying)
}
