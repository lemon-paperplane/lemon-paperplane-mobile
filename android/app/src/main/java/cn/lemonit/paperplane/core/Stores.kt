package cn.lemonit.paperplane.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.lemonit.paperplane.kit.model.AppSettings
import cn.lemonit.paperplane.kit.model.Contributions
import cn.lemonit.paperplane.kit.model.DaemonEvent
import cn.lemonit.paperplane.kit.model.PluginInfo
import cn.lemonit.paperplane.kit.model.ProjectSummary
import cn.lemonit.paperplane.kit.model.Run
import cn.lemonit.paperplane.kit.model.RunBrief
import cn.lemonit.paperplane.kit.model.TaskInfo
import cn.lemonit.paperplane.kit.model.TerminalInfo
import cn.lemonit.paperplane.kit.model.Toolchain
import cn.lemonit.paperplane.kit.model.ToolchainKind
import cn.lemonit.paperplane.kit.model.VCSChangedEvent
import cn.lemonit.paperplane.kit.model.Workspace
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.Instant

// 各 Store 用 Compose 的快照状态（mutableStateOf / mutableStateMapOf）：Composable 里直接读字段就会在变化时重组，
// 不用 collectAsState。只在主线程写（AppModel 的协程都跑在 Dispatchers.Main 上）。对照 Mac LemonPaperplane/Core/Stores.swift。

/** 工作区、工程摘要、设置、插件贡献等「较少变化」的数据。 */
class DataStore {
    var workspaces by mutableStateOf<List<Workspace>>(emptyList())
    val projects = mutableStateMapOf<String, ProjectSummary>()
    var settings by mutableStateOf<AppSettings?>(null)
    var contributions by mutableStateOf(Contributions())
    var plugins by mutableStateOf<List<PluginInfo>>(emptyList())
    var toolchainKinds by mutableStateOf<List<ToolchainKind>>(emptyList())
    var toolchains by mutableStateOf<List<Toolchain>>(emptyList())
    /** 当前服务器的数据第一次拉全了 */
    var loaded by mutableStateOf(false)

    fun workspace(id: String?): Workspace? = workspaces.firstOrNull { it.id == id }

    /** 切换服务器：清掉上一台的数据 */
    fun reset() {
        workspaces = emptyList()
        projects.clear()
        settings = null
        contributions = Contributions()
        plugins = emptyList()
        toolchainKinds = emptyList()
        toolchains = emptyList()
        loaded = false
    }

    /** 以内核的工作区列表为准：同时校正工程摘要里的 workspaceId（移动工程后摘要可能比工作区列表晚到） */
    fun applyWorkspaces(ws: List<Workspace>) {
        workspaces = ws
        for (w in ws) for (id in w.projectIds) {
            val p = projects[id] ?: continue
            if (p.workspaceId != w.id) projects[id] = p.copy(workspaceId = w.id)
        }
    }

    fun setProjects(list: List<ProjectSummary>) {
        projects.clear()
        for (p in list) if (!projects.containsKey(p.id)) projects[p.id] = p
    }

    /** 按工作区内顺序 */
    fun projects(workspaceId: String): List<ProjectSummary> {
        val ws = workspace(workspaceId) ?: return emptyList()
        val out = ws.projectIds.mapNotNull { projects[it] }.toMutableList()
        // 刚导入、工作区列表还没到的工程：只补「不在任何工作区顺序表里」的
        val listed = workspaces.flatMap { it.projectIds }.toSet()
        out += projects.values.filter { it.workspaceId == workspaceId && it.id !in listed }.sortedBy { it.name }
        return out
    }

    /** 全部工程（按工作区顺序） */
    val allProjects: List<ProjectSummary> get() = workspaces.flatMap { projects(it.id) }

    fun toolchains(kind: String): List<Toolchain> = toolchains.filter { it.kind == kind }

    fun kindName(kind: String): String = toolchainKinds.firstOrNull { it.kind == kind }?.name ?: kind

    fun plugin(id: String): PluginInfo? = plugins.firstOrNull { it.id == id }
}

/** 运行与任务（变化频繁：stats 每 2 秒一次）。 */
class RunStore {
    val runs = mutableStateMapOf<String, Run>()
    val tasks = mutableStateMapOf<String, TaskInfo>()
    /** 本界面发起的工具链下载任务：taskId → kind */
    val toolchainTaskKind = mutableMapOf<String, String>()

    fun reset() {
        runs.clear()
        tasks.clear()
        toolchainTaskKind.clear()
    }

    fun setRuns(list: List<Run>) {
        runs.clear()
        for (r in list) if (!runs.containsKey(r.id)) runs[r.id] = r
    }

    fun setTasks(list: List<TaskInfo>) {
        tasks.clear()
        for (t in list) if (!tasks.containsKey(t.id)) tasks[t.id] = t
    }

    val activeRuns: List<Run>
        get() = runs.values.filter { it.isActive }.sortedByDescending { it.startedAt ?: Instant.EPOCH }

    val activeTasks: List<TaskInfo>
        get() = tasks.values.filter { it.isRunning }.sortedByDescending { it.startedAt ?: Instant.EPOCH }

    /** 最近的任务（运行中在前，其后最近结束的若干个） */
    val recentTasks: List<TaskInfo>
        get() = activeTasks + tasks.values.filter { !it.isRunning }.sortedByDescending { it.endedAt ?: Instant.EPOCH }.take(8)

    fun latestRun(projectId: String, configId: String): Run? =
        runs.values.filter { it.projectId == projectId && it.configId == configId }.maxByOrNull { it.startedAt ?: Instant.EPOCH }

    fun activeRuns(projectId: String): List<Run> = activeRuns.filter { it.projectId == projectId }

    fun activeRun(projectId: String, configId: String): Run? =
        activeRuns.firstOrNull { it.projectId == projectId && it.configId == configId }

    fun tasks(projectId: String): List<TaskInfo> = activeTasks.filter { it.projectId == projectId }

    /** stats 事件：CPU/内存进 ResourceStore（各运行各自刷新），这里只在地址或状态变了时才更新 */
    fun applyStats(briefs: List<RunBrief>) {
        for (b in briefs) {
            val r = runs[b.id] ?: continue
            val urlsChanged = b.urls.isNotEmpty() && r.urls != b.urls
            val stateChanged = b.state.isNotEmpty() && r.state != b.state
            if (!urlsChanged && !stateChanged) continue
            runs[b.id] = r.copy(
                urls = if (urlsChanged) b.urls else r.urls,
                state = if (stateChanged) b.state else r.state,
                cpu = b.cpu,
                rssMB = b.rssMB,
            )
        }
    }

    /** 只保留进行中的和最近 50 个结束的运行、30 个结束的任务，防止长期运行后无限增长 */
    fun prune() {
        runs.values.filter { !it.isActive }.sortedByDescending { it.startedAt ?: Instant.EPOCH }.drop(50).forEach { runs.remove(it.id) }
        tasks.values.filter { !it.isRunning }.sortedByDescending { it.endedAt ?: Instant.EPOCH }.drop(30).forEach { tasks.remove(it.id) }
    }
}

/** 交互终端（terminals.changed 事件整体替换）。终端在服务器内核里，App 关掉再打开还在。 */
class TerminalStore {
    var list by mutableStateOf<List<TerminalInfo>>(emptyList())
    /** 每个工作区当前显示的终端 */
    val selected = mutableStateMapOf<String, String>()

    fun terminals(workspaceId: String): List<TerminalInfo> = list.filter { it.workspaceId == workspaceId }

    /** 工作区当前显示的终端：记住的那个还在就用它，否则第一个 */
    fun current(workspaceId: String): TerminalInfo? {
        val l = terminals(workspaceId)
        return selected[workspaceId]?.let { id -> l.firstOrNull { it.id == id } } ?: l.firstOrNull()
    }

    fun reset() {
        list = emptyList()
        selected.clear()
    }

    fun upsert(t: TerminalInfo) {
        val i = list.indexOfFirst { it.id == t.id }
        list = if (i >= 0) list.toMutableList().also { it[i] = t } else list + t
    }
}

/** 事件总线：给只关心某类事件的页面（版本控制、工程详情、工具链页）用。在 LaunchedEffect 里 collect。 */
class EventBus {
    private fun <T> flow() = MutableSharedFlow<T>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** project.changed（工程 id） */
    val projectChanged = flow<String>()
    val vcsChanged = flow<VCSChangedEvent>()
    val toolchainsChanged = flow<Unit>()
    val pluginsChanged = flow<Unit>()
    /** 任何原始事件 */
    val raw = flow<DaemonEvent>()
    /** 切回前台、重连成功后：各页面重新拉自己的数据（事件流断开期间可能漏了变化） */
    val resumed = flow<Unit>()
}
