package cn.lemonit.paperplane.core

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import cn.lemonit.paperplane.features.forms.PromptPresenter
import cn.lemonit.paperplane.features.runs.TaskLog
import cn.lemonit.paperplane.kit.ApiException
import cn.lemonit.paperplane.kit.OpCaller
import cn.lemonit.paperplane.kit.PaperplaneJson
import cn.lemonit.paperplane.kit.contributions
import cn.lemonit.paperplane.kit.getSettings
import cn.lemonit.paperplane.kit.model.DaemonEvent
import cn.lemonit.paperplane.kit.model.EventPayload
import cn.lemonit.paperplane.kit.model.PluginInfo
import cn.lemonit.paperplane.kit.model.RunBrief
import cn.lemonit.paperplane.kit.model.SystemInfo
import cn.lemonit.paperplane.kit.model.TaskInfo
import cn.lemonit.paperplane.kit.model.TaskState
import cn.lemonit.paperplane.kit.pendingPrompts
import cn.lemonit.paperplane.kit.plugins
import cn.lemonit.paperplane.kit.projects
import cn.lemonit.paperplane.kit.runs
import cn.lemonit.paperplane.kit.tasks
import cn.lemonit.paperplane.kit.terminalList
import cn.lemonit.paperplane.kit.toolchainKinds
import cn.lemonit.paperplane.kit.toolchains
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.kit.util.IconSource
import cn.lemonit.paperplane.kit.util.ServerPath
import cn.lemonit.paperplane.kit.workspaces
import cn.lemonit.paperplane.net.EventStream
import cn.lemonit.paperplane.net.LocalGateway
import cn.lemonit.paperplane.net.AndroidAssetSource
import cn.lemonit.paperplane.net.PortForwarder
import cn.lemonit.paperplane.net.RemoteClient
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.ToastKind
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.SvgRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 当前服务器的事件流状态 */
enum class Connection { CONNECTING, CONNECTED, RECONNECTING }

/** 当前服务器的连接阶段 */
sealed interface ServerPhase {
    /** 还没添加 / 没选服务器 */
    data object None : ServerPhase
    data object Connecting : ServerPhase
    data object Ready : ServerPhase
    data class Failed(val failure: ServerFailure) : ServerPhase
}

/**
 * App 的根状态：当前服务器、连接、事件分发、各 Store。对照 Mac LemonPaperplane/Core/AppModel.swift。
 * 手机上没有本机内核：直接用 [RemoteClient] 连服务器；编辑器、终端、插件网页经 [gateway]；运行地址经 [forwarder]。
 * 所有状态只在主线程改（[scope] 跑在 Dispatchers.Main 上）。
 */
object AppModel {
    lateinit var context: Context
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val data = DataStore()
    val runs = RunStore()
    val terminals = TerminalStore()
    val nav = NavStore()
    val bus = EventBus()
    val pluginIcons = PluginIconCache()

    private lateinit var serverStore: ServerStore
    private lateinit var prefs: SharedPreferences

    /** 已添加的服务器（快照状态，Composable 里直接读） */
    var serverList by mutableStateOf<List<ServerEntry>>(emptyList())
        private set
    var currentServerId by mutableStateOf<String?>(null)
        private set
    val currentServer: ServerEntry? get() = serverList.firstOrNull { it.id == currentServerId }

    var phase by mutableStateOf<ServerPhase>(ServerPhase.None)
        private set
    var connection by mutableStateOf(Connection.CONNECTING)
        private set
    /** 当前服务器的 system.info */
    var systemInfo by mutableStateOf<SystemInfo?>(null)
        private set
    /** App 在前台（切后台时停掉长连接；页面里的日志流、终端流也按它开关） */
    var foreground by mutableStateOf(true)
        private set

    /** 当前服务器的客户端（连接中、失败时也在，用于重试） */
    var client: RemoteClient? = null
        private set
    private var gatewayInstance: LocalGateway? = null
    private var forwarderInstance: PortForwarder? = null
    private var events: EventStream? = null
    private var eventsJob: Job? = null

    /** 每次切换服务器 +1：切换前发出的请求回来时已过期，丢弃结果 */
    private var generation = 0
    /** 本界面发起的任务：结束时给出提示 */
    private val trackedTasks = mutableSetOf<String>()

    fun init(ctx: Context) {
        if (::serverStore.isInitialized) return
        context = ctx.applicationContext
        prefs = context.getSharedPreferences("paperplane", Context.MODE_PRIVATE)
        serverStore = ServerStore.create(context)
        scope.launch { serverStore.servers.collect { serverList = it } }
        scope.launch { serverStore.currentId.collect { currentServerId = it } }
        serverList = serverStore.servers.value
        currentServerId = serverStore.currentId.value ?: serverList.firstOrNull()?.id?.also { serverStore.setCurrent(it) }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = onForeground()
            override fun onStop(owner: LifecycleOwner) = onBackground()
        })
        activateCurrent()
    }

    // ---------------------------------------------------------------- 调用

    /** 需要已连接的接口；没连上时抛出可展示的错误 */
    fun api(): OpCaller {
        val c = client
        if (c != null && phase == ServerPhase.Ready) return c
        throw ApiException(ApiException.NETWORK, tr("connection.notConnected"))
    }

    /** 执行一个调用，失败时弹提示 */
    fun perform(title: String? = null, op: suspend (OpCaller) -> Unit): Job = scope.launch {
        try {
            op(api())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Toasts.error(e, title)
        }
    }

    /** 当前服务器的本地网关（编辑器、终端页、插件网页用；第一次用时启动） */
    fun gateway(): LocalGateway {
        gatewayInstance?.let { return it }
        val c = client ?: throw ApiException(ApiException.NETWORK, tr("connection.notConnected"))
        return LocalGateway(c, AndroidAssetSource(context.assets, "editor")).also {
            it.start()
            gatewayInstance = it
        }
    }

    /** 当前服务器的端口转发（运行地址在 App 内置浏览器里打开时用） */
    fun forwarder(): PortForwarder {
        forwarderInstance?.let { return it }
        val c = client ?: throw ApiException(ApiException.NETWORK, tr("connection.notConnected"))
        return PortForwarder(c).also { forwarderInstance = it }
    }

    /** 当前服务器的路径规则（连 Windows 服务器时是 C:\…） */
    val serverPath: ServerPath get() = Format.serverPath

    // ---------------------------------------------------------------- 服务器

    fun serverToken(id: String): String? = serverStore.token(id)

    /** 保存（新增或更新）服务器；token 为空表示不改密钥 */
    fun saveServer(entry: ServerEntry, token: String?): ServerEntry = serverStore.save(entry, token)

    fun renameServer(id: String, name: String) = serverStore.rename(id, name)

    fun existingServer(urls: List<String>, fingerprint: String, excluding: String? = null) =
        serverStore.existing(urls, fingerprint, excluding)

    /** 删除本机保存的服务器（不影响服务器本身） */
    fun removeServer(id: String) {
        val wasCurrent = id == currentServerId
        serverStore.remove(id)
        prefs.edit().remove(workspaceKey(id)).apply()
        if (wasCurrent) {
            serverStore.setCurrent(serverStore.servers.value.firstOrNull()?.id)
            currentServerId = serverStore.currentId.value
            resetForServerSwitch()
            activateCurrent()
        }
    }

    /** 切换到某台服务器：所有数据重新加载 */
    fun switchServer(id: String) {
        if (id == currentServerId && phase == ServerPhase.Ready) return
        serverStore.setCurrent(id)
        currentServerId = id
        resetForServerSwitch()
        activateCurrent()
    }

    /** 重试 / 重新登录后重连当前服务器 */
    fun reconnectServer() {
        resetClient()
        activateCurrent()
    }

    private fun resetForServerSwitch() {
        Dialogs.dismissAll()
        data.reset()
        runs.reset()
        terminals.reset()
        trackedTasks.clear()
        pluginIcons.clear()
        nav.resetForServerSwitch()
        resetClient()
    }

    private fun resetClient() {
        generation++
        stopEvents()
        gatewayInstance?.close()
        gatewayInstance = null
        forwarderInstance?.close()
        forwarderInstance = null
        client?.close()
        client = null
        systemInfo = null
    }

    private fun activateCurrent() {
        val s = currentServer
        if (s == null) {
            phase = ServerPhase.None
            return
        }
        val token = serverStore.token(s.id)
        if (token.isNullOrEmpty()) {
            phase = ServerPhase.Failed(ServerFailure(ServerFailure.Kind.UNAUTHORIZED))
            return
        }
        generation++
        val gen = generation
        phase = ServerPhase.Connecting
        connection = Connection.CONNECTING
        val c = RemoteClient(s.target(), token, listener = ClientListener(s.id, gen))
        client = c
        scope.launch {
            try {
                val info = PaperplaneJson.decodeFromJsonElement(SystemInfo.serializer(), c.connect())
                if (gen != generation) return@launch
                serverStore.updateInfo(s.id, info.os, info.hostname)
                applySystemInfo(info)
                phase = ServerPhase.Ready
                startEvents(c, gen)
                refreshAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                phase = ServerPhase.Failed(ServerFailure.of(e))
            }
        }
    }

    private class ClientListener(private val serverId: String, private val gen: Int) : RemoteClient.Listener {
        override fun onActiveUrlChanged(url: String) {
            scope.launch { serverStore.markUsed(serverId, url) }
        }

        override fun onAuthProblem(error: ApiException) {
            scope.launch {
                if (gen != generation || phase is ServerPhase.Failed) return@launch
                phase = ServerPhase.Failed(ServerFailure.of(error))
                stopEvents()
            }
        }
    }

    private fun applySystemInfo(info: SystemInfo) {
        systemInfo = info
        Format.serverHome = info.home
        Format.serverPath = ServerPath(info.pathSeparator.ifEmpty { "/" })
    }

    // ---------------------------------------------------------------- 前后台

    private fun onForeground() {
        if (foreground) return
        foreground = true
        when {
            currentServer == null -> {}
            phase is ServerPhase.Failed && (phase as ServerPhase.Failed).failure.kind == ServerFailure.Kind.UNREACHABLE -> reconnectServer()
            phase == ServerPhase.Ready -> {
                // 回到前台：重新探测地址（可能换了网络），再接上事件流；hello 后整体刷新
                val c = client ?: return
                val gen = generation
                scope.launch {
                    try {
                        val info = PaperplaneJson.decodeFromJsonElement(SystemInfo.serializer(), c.connect())
                        if (gen != generation) return@launch
                        applySystemInfo(info)
                        startEvents(c, gen)
                        bus.resumed.tryEmit(Unit)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (gen != generation) return@launch
                        phase = ServerPhase.Failed(ServerFailure.of(e))
                    }
                }
            }
            else -> {}
        }
    }

    private fun onBackground() {
        foreground = false
        // 后台不保持长连接（省电，系统也会断）；回来后从记住的位置续上
        stopEvents()
        forwarderInstance?.close()
        forwarderInstance = null
    }

    // ---------------------------------------------------------------- 事件流

    private fun startEvents(c: RemoteClient, gen: Int) {
        stopEvents()
        val stream = EventStream(c)
        events = stream
        eventsJob = scope.launch {
            stream.updates().collect { u ->
                if (gen != generation) return@collect
                when (u) {
                    is EventStream.Update.Connected -> connection = Connection.CONNECTED
                    is EventStream.Update.Disconnected -> {
                        if (u.retrying) {
                            if (connection == Connection.CONNECTED) connection = Connection.RECONNECTING
                        } else if (u.error.code != ApiException.NETWORK) {
                            phase = ServerPhase.Failed(ServerFailure.of(u.error))
                        } else {
                            connection = Connection.RECONNECTING
                        }
                    }
                    is EventStream.Update.Event -> handle(DaemonEvent(u.type, u.data))
                }
            }
        }
    }

    private fun stopEvents() {
        eventsJob?.cancel()
        eventsJob = null
        events?.stop()
        events = null
    }

    private fun handle(e: DaemonEvent) {
        bus.raw.tryEmit(e)
        when (val p = e.payload()) {
            is EventPayload.Hello -> {
                applySystemInfo(p.info)
                connection = Connection.CONNECTED
                scope.launch { refreshAll() }
            }
            is EventPayload.Workspaces -> data.applyWorkspaces(p.workspaces)
            is EventPayload.ProjectChanged -> {
                data.projects[p.project.id] = p.project
                bus.projectChanged.tryEmit(p.project.id)
            }
            is EventPayload.ProjectRemoved -> {
                data.projects.remove(p.projectId)
                nav.removeWhere { it is ProjectRoute && it.projectId == p.projectId }
            }
            is EventPayload.RunChanged -> {
                runs.runs[p.run.id] = p.run
                bus.runChanged.tryEmit(p.run)
                if (!p.run.isActive) runs.prune()
            }
            is EventPayload.TaskChanged -> taskChanged(p.task)
            EventPayload.ToolchainsChanged -> {
                bus.toolchainsChanged.tryEmit(Unit)
                scope.launch { reloadToolchains() }
            }
            EventPayload.PluginsChanged -> {
                pluginIcons.clear()
                bus.pluginsChanged.tryEmit(Unit)
                scope.launch { reloadPlugins() }
            }
            is EventPayload.SettingsChanged -> data.settings = p.settings
            is EventPayload.Notify -> {
                val kind = when (p.params.level) {
                    "error" -> ToastKind.Error
                    "warning" -> ToastKind.Warning
                    "success" -> ToastKind.Success
                    else -> ToastKind.Info
                }
                Toasts.show(p.params.title, kind, p.params.message)
            }
            is EventPayload.PromptShown -> PromptPresenter.present(p.prompt)
            is EventPayload.PromptDone -> PromptPresenter.dismiss(p.promptId)
            is EventPayload.VcsChanged -> bus.vcsChanged.tryEmit(p.change)
            is EventPayload.Stats -> applyStats(p.runs)
            is EventPayload.Terminals -> terminals.list = p.terminals
            is EventPayload.Unknown -> {}
        }
    }

    /** stats 每 2 秒一次：数值走 bus.stats（资源曲线各自订阅），这里只在地址变化时更新工程摘要 */
    private fun applyStats(briefs: List<RunBrief>) {
        bus.stats.tryEmit(briefs)
        runs.applyStats(briefs)
        val byId = briefs.associateBy { it.id }
        for ((pid, p) in data.projects.toMap()) {
            if (p.runs.none { r -> byId[r.id]?.let { it.urls.isNotEmpty() && it.urls != r.urls } == true }) continue
            data.projects[pid] = p.copy(runs = p.runs.map { r ->
                val b = byId[r.id]
                if (b == null || b.urls.isEmpty()) r else r.copy(urls = b.urls)
            })
        }
    }

    // ---------------------------------------------------------------- 数据

    /** 重新拉取全部状态（连上、重连后的 hello） */
    suspend fun refreshAll() {
        val c = client ?: return
        val gen = generation
        coroutineScope {
            val ws = async { runCatching { c.workspaces() }.getOrNull() }
            val ps = async { runCatching { c.projects() }.getOrNull() }
            val rs = async { runCatching { c.runs() }.getOrNull() }
            val ts = async { runCatching { c.tasks() }.getOrNull() }
            val st = async { runCatching { c.getSettings() }.getOrNull() }
            val cs = async { runCatching { c.contributions() }.getOrNull() }
            val pl = async { runCatching { c.plugins() }.getOrNull() }
            val kinds = async { runCatching { c.toolchainKinds() }.getOrNull() }
            val tcs = async { runCatching { c.toolchains() }.getOrNull() }
            val prompts = async { runCatching { c.pendingPrompts() }.getOrNull() }
            val terms = async { runCatching { c.terminalList() }.getOrNull() }
            val wsR = ws.await()
            val psR = ps.await()
            val rsR = rs.await()
            val tsR = ts.await()
            val stR = st.await()
            val csR = cs.await()
            val plR = pl.await()
            val kindsR = kinds.await()
            val tcsR = tcs.await()
            val promptsR = prompts.await()
            val termsR = terms.await()
            if (gen != generation) return@coroutineScope
            psR?.let { data.setProjects(it) }
            wsR?.let { data.applyWorkspaces(it) }
            rsR?.let { runs.setRuns(it) }
            tsR?.let { runs.setTasks(it) }
            stR?.let { data.settings = it }
            csR?.let { data.contributions = it }
            plR?.let { setPlugins(it) }
            kindsR?.let { data.toolchainKinds = it }
            tcsR?.let { data.toolchains = it }
            promptsR?.forEach { PromptPresenter.present(it) }
            termsR?.let { terminals.list = it }
            data.loaded = true
            fixWorkspaceSelection()
        }
    }

    private fun setPlugins(list: List<PluginInfo>) {
        data.plugins = list
    }

    suspend fun reloadToolchains() {
        val c = client ?: return
        runCatching { c.toolchains() }.getOrNull()?.let { data.toolchains = it }
        runCatching { c.toolchainKinds() }.getOrNull()?.let { data.toolchainKinds = it }
    }

    suspend fun reloadPlugins() {
        val c = client ?: return
        runCatching { c.plugins() }.getOrNull()?.let { setPlugins(it) }
        runCatching { c.contributions() }.getOrNull()?.let { data.contributions = it }
        runCatching { c.toolchainKinds() }.getOrNull()?.let { data.toolchainKinds = it }
    }

    // ---------------------------------------------------------------- 工作区选择

    private fun workspaceKey(serverId: String) = "lastWorkspace.$serverId"

    /** 选中工作区（按服务器记住） */
    fun selectWorkspace(id: String?) {
        nav.workspaceId = id
        val sid = currentServerId ?: return
        prefs.edit().putString(workspaceKey(sid), id).apply()
    }

    /** 加载后：记住的工作区还在就用它，否则第一个 */
    private fun fixWorkspaceSelection() {
        if (data.workspace(nav.workspaceId) != null) return
        val remembered = currentServerId?.let { prefs.getString(workspaceKey(it), null) }
        val id = data.workspace(remembered)?.id ?: data.workspaces.firstOrNull()?.id
        nav.workspaceId = id
    }

    // ---------------------------------------------------------------- 任务

    /** 记下本界面发起的任务，结束时提示 */
    fun track(task: TaskInfo) {
        runs.tasks[task.id] = task
        trackedTasks.add(task.id)
    }

    private fun taskChanged(t: TaskInfo) {
        runs.tasks[t.id] = t
        if (t.isRunning || !trackedTasks.remove(t.id)) return
        val result = (t.result as? kotlinx.serialization.json.JsonObject)
        fun str(k: String) = result?.get(k)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        fun bool(k: String) = result?.get(k)?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } == true
        when (t.state) {
            TaskState.SUCCEEDED -> {
                val msg = str("message")
                if (!msg.isNullOrEmpty()) Toasts.success(t.title, msg)
                val pid = t.projectId
                if (bool("reimport") && pid != null) Actions.reimport(pid)
                else if (bool("sync") && pid != null) Actions.sync(pid, t.moduleId)
            }
            TaskState.FAILED -> Toasts.show(t.title, ToastKind.Error, t.error, actionText = tr("task.viewLog")) { TaskLog.show(t.id) }
        }
        runs.prune()
    }
}

/** 插件图标缓存（按当前服务器；插件变化、切换服务器时清空） */
class PluginIconCache {
    val icons = mutableStateMapOf<String, ByteArray>()
    private val loading = mutableSetOf<String>()

    suspend fun load(src: IconSource.Plugin) {
        val key = src.key
        if (icons.containsKey(key) || !loading.add(key)) return
        try {
            val c = AppModel.client ?: return
            icons[key] = runCatching { c.pluginFile(src.id, src.path) }.getOrDefault(ByteArray(0))
        } finally {
            loading.remove(key)
        }
    }

    fun clear() {
        icons.clear()
        loading.clear()
        SvgRenderer.clearDataIcons()
    }
}
