package cn.lemonit.paperplane.net

import cn.lemonit.paperplane.kit.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import java.io.ByteArrayOutputStream

/** 一条 Server-Sent Events 消息 */
data class SseMessage(val event: String? = null, val data: String, val id: String? = null)

/**
 * 按行喂入的 SSE 解析器（行不含换行符）。空行结束一条消息；以 ":" 开头的是注释（如 ": ping"）。
 * 移植自 Mac SSEParser。
 */
class SseParser {
    private var event: String? = null
    private val data = ArrayList<String>()
    private var id: String? = null
    private var hasField = false

    /** 遇到空行时返回完整消息，否则 null */
    fun feed(line: String): SseMessage? {
        if (line.isEmpty()) {
            val out = if (hasField && !(data.isEmpty() && event == null)) SseMessage(event, data.joinToString("\n"), id) else null
            event = null
            data.clear()
            hasField = false
            return out
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field: String
        var value: String
        if (colon >= 0) {
            field = line.substring(0, colon)
            value = line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
        } else {
            field = line
            value = ""
        }
        when (field) {
            "data" -> {
                data += value
                hasField = true
            }
            "event" -> {
                event = value
                hasField = true
            }
            "id" -> id = value
        }
        return null
    }
}

/** 按字节块切行（处理 \r\n 与跨块的半行，UTF-8 多字节字符跨块也不会切坏）。移植自 Mac LineSplitter。 */
class LineSplitter {
    private val buffer = ByteArrayOutputStream()

    fun append(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size): List<String> {
        val lines = ArrayList<String>()
        var start = offset
        val end = offset + length
        for (i in offset until end) {
            if (chunk[i] != '\n'.code.toByte()) continue
            buffer.write(chunk, start, i - start)
            val bytes = buffer.toByteArray()
            buffer.reset()
            var n = bytes.size
            if (n > 0 && bytes[n - 1] == '\r'.code.toByte()) n--
            lines += String(bytes, 0, n, Charsets.UTF_8)
            start = i + 1
        }
        if (start < end) buffer.write(chunk, start, end - start)
        return lines
    }
}

/**
 * 自动重连的 SSE 连接（移植自 Mac SSEConnection）：断线后指数退避重连（0.5 秒起，最长 15 秒），
 * [RemoteClient.Options.streamIdleTimeoutMs]（默认 45 秒）没有任何字节视为断线。
 *
 * - 每次（重）连调用 [open]（可带上最新的续传位置）；
 * - 收到 [endEvent] 后发 [Event.Ended] 并停止，不再重连（控制台流是 "end"，终端流是 "closed"）；
 * - HTTP 4xx（密钥失效、被封禁、IP 被拒、流不存在）、证书不符、版本不兼容不重连，发 retrying=false 的 [Event.Disconnected]；
 *   其余（网络断开、服务器重启、5xx）发 retrying=true 后重连。
 *
 * 回调在后台线程（每条连接串行）执行，界面层自己切回主线程。[stop] 之后不会再有回调；之后可以再 [start]。
 */
class SseConnection(
    private val open: suspend () -> StreamResponse,
    private val reconnect: Boolean = true,
    private val endEvent: String? = null,
    private val logger: NetLogger = NetLogger.NONE,
    private val backoffMs: (attempt: Int) -> Long = ::defaultBackoff,
    private val listener: (Event) -> Unit,
) {
    sealed interface Event {
        /** 连上了（HTTP 200） */
        data object Open : Event

        data class Message(val message: SseMessage) : Event

        /** 断开了；retrying=false 表示不会再重连（见类说明） */
        data class Disconnected(val error: ApiException, val retrying: Boolean) : Event

        /** 收到结束事件，已停止 */
        data object Ended : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var job: Job? = null

    @Volatile
    private var activeCall: Call? = null

    val isRunning: Boolean get() = synchronized(lock) { job?.isActive == true }

    /** 开始（已在运行则先停掉旧的再开） */
    fun start() {
        synchronized(lock) {
            job?.cancel()
            activeCall?.cancel()
            job = scope.launch { loop() }
        }
    }

    fun stop() {
        synchronized(lock) {
            job?.cancel()
            job = null
            activeCall?.cancel()
            activeCall = null
        }
    }

    private suspend fun loop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val error: ApiException = try {
                val sr = open()
                activeCall = sr.call
                if (!currentCoroutineContext().isActive) {
                    sr.call.cancel()
                    sr.response.close()
                    return
                }
                attempt = 0
                emit(Event.Open)
                val ended = sr.response.use { readAll(it) }
                if (ended) {
                    emit(Event.Ended)
                    clearJob()
                    return
                }
                NetErrors.network(NetErrors.REASON_CLOSED, "connection closed")
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                e
            } catch (e: Exception) {
                NetErrors.map(e)
            } finally {
                activeCall = null
            }
            if (!currentCoroutineContext().isActive) return
            val retry = reconnect && !isFatal(error)
            logger.log("sse: disconnected (${error.code} ${error.message}), retry=$retry")
            emit(Event.Disconnected(error, retry))
            if (!retry) {
                clearJob()
                return
            }
            delay(backoffMs(attempt++))
        }
    }

    private suspend fun clearJob() {
        val me = currentCoroutineContext()[Job]
        synchronized(lock) { if (job === me) job = null }
    }

    /** 读到结束事件返回 true，连接被关闭返回 false；被 stop 时抛 IOException */
    private suspend fun readAll(response: okhttp3.Response): Boolean {
        val source = response.body?.source() ?: return false
        val splitter = LineSplitter()
        val parser = SseParser()
        val buf = ByteArray(8192)
        while (true) {
            val n = source.read(buf, 0, buf.size)
            if (n == -1) return false
            for (line in splitter.append(buf, 0, n)) {
                val msg = parser.feed(line) ?: continue
                emit(Event.Message(msg))
                if (endEvent != null && msg.event == endEvent) return true
            }
        }
    }

    private suspend fun emit(e: Event) {
        if (!currentCoroutineContext().isActive) throw CancellationException("stopped")
        try {
            listener(e)
        } catch (x: Exception) {
            logger.log("sse: listener failed: $x")
        }
    }

    companion object {
        fun defaultBackoff(attempt: Int): Long = minOf(15_000L, 500L shl minOf(attempt, 6))

        /** 不该重连的错误 */
        fun isFatal(e: ApiException): Boolean =
            (e.status ?: 0) in 400..499 || e.code == ApiException.FINGERPRINT_MISMATCH ||
                e.code == ApiException.INCOMPATIBLE || e.code == ApiException.UNAUTHORIZED || e.code == ApiException.BANNED
    }
}
