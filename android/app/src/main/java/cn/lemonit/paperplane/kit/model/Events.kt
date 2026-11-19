package cn.lemonit.paperplane.kit.model

import cn.lemonit.paperplane.kit.PaperplaneJson
import java.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

// 三条 SSE 流里的数据（docs/api.md 第 3 节）。网络层（net）只负责连接、断线续传，把每条消息的
// event 名与 data 原文交给上层，由这里的函数解码：
//   GET /api/v1/events                           → DaemonEvent.parse(data)，再 event.payload()
//   GET /api/v1/consoles/<id>/stream?since=<seq>  → ConsoleStreamMessage.parse(event, data)
//   GET /api/v1/terminals/<id>/stream?since=<偏移> → TerminalStreamMessage.parse(event, data)
// 解析不了的消息返回 null（跳过，不断流）。

/** /api/v1/events 的事件类型（model.go 的 Ev* 常量） */
object EventType {
    /** data: SystemInfo（每次连上后第一条） */
    const val HELLO = "hello"

    /** data: [Workspace] */
    const val WORKSPACES = "workspaces.changed"

    /** data: ProjectSummary */
    const val PROJECT = "project.changed"

    /** data: {id} */
    const val PROJECT_REMOVED = "project.removed"

    /** data: Run */
    const val RUN = "run.changed"

    /** data: TaskInfo */
    const val TASK = "task.changed"

    /** data: null */
    const val TOOLCHAINS = "toolchains.changed"

    /** data: null */
    const val PLUGINS = "plugins.changed"

    /** data: AppSettings */
    const val SETTINGS = "settings.changed"

    /** data: NotifyParams */
    const val NOTIFY = "notify"

    /** data: Prompt */
    const val PROMPT = "prompt"

    /** data: {id} */
    const val PROMPT_DONE = "prompt.done"

    /** data: {projectId, root} */
    const val VCS = "vcs.changed"

    /** data: [RunBrief]（每 2 秒，只含运行中的） */
    const val STATS = "stats"

    /** data: [TerminalInfo]（全部终端） */
    const val TERMINALS = "terminals.changed"
}

/** 一条全局事件。data 先保留为 JsonElement，用 payload() 或 decode() 按类型解码。 */
@Serializable
data class DaemonEvent(
    val type: String = "",
    val data: JsonElement? = null,
) {
    /** 按指定类型解码 data（data 缺失时按 JSON null 解码） */
    fun <T> decode(serializer: KSerializer<T>): T = PaperplaneJson.decodeFromJsonElement(serializer, data ?: JsonNull)

    /** 按 type 解码成强类型；未知类型或 data 不合法时为 EventPayload.Unknown（不抛异常） */
    fun payload(): EventPayload = try {
        when (type) {
            EventType.HELLO -> EventPayload.Hello(decode(SystemInfo.serializer()))
            EventType.WORKSPACES -> EventPayload.Workspaces(decodeList(Workspace.serializer()))
            EventType.PROJECT -> EventPayload.ProjectChanged(decode(ProjectSummary.serializer()))
            EventType.PROJECT_REMOVED -> EventPayload.ProjectRemoved(decode(IdData.serializer()).id)
            EventType.RUN -> EventPayload.RunChanged(decode(Run.serializer()))
            EventType.TASK -> EventPayload.TaskChanged(decode(TaskInfo.serializer()))
            EventType.TOOLCHAINS -> EventPayload.ToolchainsChanged
            EventType.PLUGINS -> EventPayload.PluginsChanged
            EventType.SETTINGS -> EventPayload.SettingsChanged(decode(AppSettings.serializer()))
            EventType.NOTIFY -> EventPayload.Notify(decode(NotifyParams.serializer()))
            EventType.PROMPT -> EventPayload.PromptShown(decode(Prompt.serializer()))
            EventType.PROMPT_DONE -> EventPayload.PromptDone(decode(IdData.serializer()).id)
            EventType.VCS -> EventPayload.VcsChanged(decode(VCSChangedEvent.serializer()))
            EventType.STATS -> EventPayload.Stats(decodeList(RunBrief.serializer()))
            EventType.TERMINALS -> EventPayload.Terminals(decodeList(TerminalInfo.serializer()))
            else -> EventPayload.Unknown(type, data)
        }
    } catch (e: IllegalArgumentException) { // SerializationException 是它的子类
        EventPayload.Unknown(type, data)
    }

    private fun <T> decodeList(item: KSerializer<T>): List<T> =
        if (data == null || data is JsonNull) emptyList() else decode(ListSerializer(item))

    companion object {
        /** 解析 SSE 一条消息的 data 原文；不是合法事件时返回 null */
        fun parse(data: String): DaemonEvent? =
            runCatching { PaperplaneJson.decodeFromString(serializer(), data) }.getOrNull()?.takeIf { it.type.isNotEmpty() }
    }
}

@Serializable
internal data class IdData(val id: String = "")

/** 各类事件的强类型 data */
sealed interface EventPayload {
    data class Hello(val info: SystemInfo) : EventPayload
    data class Workspaces(val workspaces: List<Workspace>) : EventPayload
    data class ProjectChanged(val project: ProjectSummary) : EventPayload
    data class ProjectRemoved(val projectId: String) : EventPayload
    data class RunChanged(val run: Run) : EventPayload
    data class TaskChanged(val task: TaskInfo) : EventPayload
    data object ToolchainsChanged : EventPayload
    data object PluginsChanged : EventPayload
    data class SettingsChanged(val settings: AppSettings) : EventPayload
    data class Notify(val params: NotifyParams) : EventPayload
    data class PromptShown(val prompt: Prompt) : EventPayload
    data class PromptDone(val promptId: String) : EventPayload
    data class VcsChanged(val change: VCSChangedEvent) : EventPayload
    data class Stats(val runs: List<RunBrief>) : EventPayload
    data class Terminals(val terminals: List<TerminalInfo>) : EventPayload

    /** 内核新加的、这里还不认识的事件，或 data 不合法 */
    data class Unknown(val type: String, val data: JsonElement?) : EventPayload
}

// ---------------------------------------------------------------- 控制台日志流

/** 控制台日志流的一条 data：{"lines": [LogLine...]}（批量，最多 100ms 合并一次，每批最多 500 行） */
@Serializable
data class LogBatch(val lines: List<LogLine> = emptyList())

sealed interface ConsoleStreamMessage {
    /** 一批新行（seq 递增；断线后用最后一行的 seq 作为 since 续传） */
    data class Lines(val lines: List<LogLine>) : ConsoleStreamMessage

    /** 控制台结束（event: end），之后不会再有输出，不用重连 */
    data object End : ConsoleStreamMessage

    companion object {
        const val EVENT_END = "end"

        /** event 为 SSE 的 event 名（没有时传 null 或 "message"），data 为原文；空批次与不合法的返回 null */
        fun parse(event: String?, data: String): ConsoleStreamMessage? {
            if (event == EVENT_END) return End
            val batch = runCatching { PaperplaneJson.decodeFromString(LogBatch.serializer(), data) }.getOrNull() ?: return null
            return if (batch.lines.isEmpty()) null else Lines(batch.lines)
        }
    }
}

// ---------------------------------------------------------------- 终端输出流

/** 终端输出流的一条 data：{"o": 起始字节偏移, "d": base64 原始字节} */
@Serializable
data class TerminalChunk(
    val o: Long = 0,
    val d: String = "",
) {
    /** 原始字节（UTF-8 可能跨条切开，按字节写进终端模拟器） */
    fun bytes(): ByteArray = runCatching { Base64.getDecoder().decode(d) }.getOrDefault(ByteArray(0))
}

sealed interface TerminalStreamMessage {
    /** 一段输出：offset 为起始字节偏移，断线后用 offset + bytes.size 作为 since 续传 */
    class Data(val offset: Long, val bytes: ByteArray) : TerminalStreamMessage {
        /** 这段之后的偏移（续传用） */
        val end: Long get() = offset + bytes.size
    }

    /** event: state（连上时第一条；终端退出或重新启动时） */
    data class State(val terminal: TerminalInfo) : TerminalStreamMessage

    /** event: closed：终端已关闭，不用重连 */
    data object Closed : TerminalStreamMessage

    companion object {
        const val EVENT_STATE = "state"
        const val EVENT_CLOSED = "closed"

        fun parse(event: String?, data: String): TerminalStreamMessage? = when (event) {
            EVENT_CLOSED -> Closed
            EVENT_STATE -> runCatching { PaperplaneJson.decodeFromString(TerminalInfo.serializer(), data) }.getOrNull()?.let { State(it) }
            else -> runCatching { PaperplaneJson.decodeFromString(TerminalChunk.serializer(), data) }.getOrNull()
                ?.let { Data(it.o, it.bytes()) }
        }
    }
}
