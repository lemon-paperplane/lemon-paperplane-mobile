package cn.lemonit.paperplane.features.vcs

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.ProjectTab
import cn.lemonit.paperplane.kit.ApiException
import cn.lemonit.paperplane.kit.OpCaller
import cn.lemonit.paperplane.kit.PaperplaneJson
import cn.lemonit.paperplane.kit.getProject
import cn.lemonit.paperplane.kit.model.TaskInfo
import cn.lemonit.paperplane.kit.model.VCSBranches
import cn.lemonit.paperplane.kit.model.VCSCommitArgs
import cn.lemonit.paperplane.kit.model.VCSOpResult
import cn.lemonit.paperplane.kit.model.VCSReason
import cn.lemonit.paperplane.kit.model.VCSRemote
import cn.lemonit.paperplane.kit.model.VCSRoot
import cn.lemonit.paperplane.kit.model.VCSStash
import cn.lemonit.paperplane.kit.model.VCSStatus
import cn.lemonit.paperplane.kit.model.VCSTag
import cn.lemonit.paperplane.kit.vcsBranches
import cn.lemonit.paperplane.kit.vcsCommit
import cn.lemonit.paperplane.kit.vcsConfigGet
import cn.lemonit.paperplane.kit.vcsInit
import cn.lemonit.paperplane.kit.vcsRemotes
import cn.lemonit.paperplane.kit.vcsRoots
import cn.lemonit.paperplane.kit.vcsSkip
import cn.lemonit.paperplane.kit.vcsStashList
import cn.lemonit.paperplane.kit.vcsStatus
import cn.lemonit.paperplane.kit.vcsTags
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.components.ToastKind
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.theme.SharedAssets
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// 版本控制的状态（对照 Mac VCSRepoModel.swift）：按工程一个会话（仓库列表、当前仓库、当前子页），按仓库一份状态。
// 状态放在组合之外（单例按「服务器 + 工程」缓存）：手机上压栈打开差异 / 提交详情时工程页不在组合里，回来时状态还在；
// 工作区总览和工程页看到的是同一份仓库状态。全部是 Compose 快照状态，只在主线程改。

/** 工程版本控制标签里的子页 */
enum class VcsPage { CHANGES, LOG, BRANCHES, STASHES, CONFLICTS }

/** 页内提示条（操作失败或需要下一步时） */
class VcsNotice(
    val tone: Tone,
    val title: String,
    val message: String? = null,
    val output: String? = null,
    val taskId: String? = null,
    val actions: List<Action> = emptyList(),
) {
    class Action(val title: String, val primary: Boolean = false, val danger: Boolean = false, val run: () -> Unit)
}

/** 正在进行的远程 / 长操作（标题栏转圈与「查看输出」） */
data class VcsActivity(val op: String, val title: String)

/** 本机偏好（只影响界面） */
object VcsPrefs {
    private fun prefs() = SharedAssets.context()?.getSharedPreferences("paperplane_vcs", Context.MODE_PRIVATE)

    private fun bool(key: String, def: Boolean) = prefs()?.getBoolean(key, def) ?: def
    private fun setBool(key: String, v: Boolean) {
        prefs()?.edit()?.putBoolean(key, v)?.apply()
    }
    private fun string(key: String): String? = prefs()?.getString(key, null)
    private fun setString(key: String, v: String?) {
        prefs()?.edit()?.putString(key, v)?.apply()
    }

    var diffWrap: Boolean
        get() = bool("diffWrap", false)
        set(v) = setBool("diffWrap", v)

    var diffIgnoreWhitespace: Boolean
        get() = bool("diffIgnoreWS", false)
        set(v) = setBool("diffIgnoreWS", v)

    var pullMode: String
        get() = string("pullMode") ?: "merge"
        set(v) = setString("pullMode", v)

    var pullAutostash: Boolean
        get() = bool("pullAutostash", true)
        set(v) = setBool("pullAutostash", v)

    var logAllBranches: Boolean
        get() = bool("logAll", true)
        set(v) = setBool("logAll", v)

    var homeOnlyChanged: Boolean
        get() = bool("homeOnlyChanged", false)
        set(v) = setBool("homeOnlyChanged", v)

    fun selectedRoot(serverId: String, projectId: String): String? = string("root:$serverId:$projectId")

    fun setSelectedRoot(serverId: String, projectId: String, root: String) = setString("root:$serverId:$projectId", root)

    /** 克隆时上次用的父目录（按服务器） */
    fun cloneParent(serverId: String): String? = string("cloneDir:$serverId")

    fun setCloneParent(serverId: String, dir: String) = setString("cloneDir:$serverId", dir)

    /** 最近的提交信息（按仓库，最多 20 条，新的在前） */
    fun messageHistory(root: String): List<String> = string("messages:$root")?.let {
        runCatching { PaperplaneJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull()
    }.orEmpty()

    fun remember(message: String, root: String) {
        val m = message.trim()
        if (m.isEmpty()) return
        val list = (listOf(m) + messageHistory(root).filter { it != m }).take(20)
        setString("messages:$root", PaperplaneJson.encodeToString(ListSerializer(String.serializer()), list))
    }
}

/** 全部会话（按当前服务器 + 工程缓存；切换服务器后旧服务器的会话不再收事件） */
object VcsStore {
    private val sessions = HashMap<String, VcsSession>()

    private val serverId: String get() = AppModel.currentServerId.orEmpty()

    fun session(projectId: String): VcsSession {
        val key = "$serverId|$projectId"
        return sessions.getOrPut(key) { VcsSession(projectId, serverId) }
    }

    fun repo(projectId: String, root: String): VcsRepo = session(projectId).repo(root)
}

/** 一个工程的版本控制会话 */
class VcsSession(val projectId: String, val serverId: String, val preview: Boolean = false) {
    var roots by mutableStateOf<List<VCSRoot>>(emptyList())
        private set
    var rootsLoaded by mutableStateOf(false)
        private set
    var rootsError by mutableStateOf<String?>(null)
    var selectedRoot by mutableStateOf<String?>(null)
        private set
    var page by mutableStateOf(VcsPage.CHANGES)

    /** 工程页正在显示（不显示时不跟着工程摘要刷新） */
    var visible = false

    private val repos = HashMap<String, VcsRepo>()
    private var lastExternalRefresh = 0L

    init {
        if (!preview) {
            val bus = AppModel.bus
            AppModel.scope.launch {
                bus.vcsChanged.collect { e -> if (live && e.projectId == projectId) vcsChanged(e.root) }
            }
            AppModel.scope.launch {
                // 工程摘要变化（内核每 30 秒对一次）：可见时顺带刷新，能发现编辑器、终端里做的修改
                bus.projectChanged.collect { id -> if (live && id == projectId) externalRefresh(false) }
            }
            AppModel.scope.launch {
                bus.resumed.collect { if (live) externalRefresh(true) }
            }
        }
    }

    private val live: Boolean get() = AppModel.currentServerId.orEmpty() == serverId

    val repo: VcsRepo? get() = selectedRoot?.let { repo(it) }

    fun repo(root: String): VcsRepo = repos.getOrPut(root) { VcsRepo(this, root, rootName(root)) }

    fun rootName(root: String): String {
        val r = roots.firstOrNull { it.path == root }
        return if (r == null || r.relPath == "." || r.relPath.isEmpty()) VcsPath.name(root) else r.relPath
    }

    fun select(root: String) {
        selectedRoot = root
        if (!preview) VcsPrefs.setSelectedRoot(serverId, projectId, root)
    }

    /** 预览 / 截图测试用 */
    internal fun setRootsForPreview(list: List<VCSRoot>) {
        roots = list
        rootsLoaded = true
        selectedRoot = list.firstOrNull()?.path
    }

    /** 重新扫描工程内的仓库 */
    suspend fun loadRoots() {
        if (preview) return
        try {
            val list = AppModel.api().vcsRoots(projectId)
            roots = list
            rootsError = null
            val sel = selectedRoot
            if (sel == null || list.none { it.path == sel }) {
                val saved = VcsPrefs.selectedRoot(serverId, projectId)
                (list.firstOrNull { it.path == saved } ?: list.firstOrNull())?.let { select(it.path) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            rootsError = e.message
        }
        rootsLoaded = true
    }

    private fun vcsChanged(root: String?) {
        if (root != null && repos.containsKey(root)) repos[root]?.invalidate() else repos.values.forEach { it.invalidate() }
    }

    private fun externalRefresh(force: Boolean) {
        if (!visible) return
        val now = System.currentTimeMillis()
        if (!force && now - lastExternalRefresh < 5000) return
        lastExternalRefresh = now
        repo?.scheduleRefresh()
    }

    /** 打开这个工程的版本控制标签并切到指定仓库（从总览跳过来时用） */
    fun reveal(root: String, page: VcsPage? = null) {
        if (!rootsLoaded || roots.any { it.path == root }) select(root)
        if (page != null) this.page = page
        AppModel.nav.openProject(projectId, ProjectTab.VCS)
    }

    /** 在工程文件夹初始化 Git */
    fun initRepository() {
        AppModel.perform(tr("vcs.init.failed")) { api ->
            val r = api.vcsInit(projectId)
            if (!r.ok) throw ApiException(ApiException.USER_ERROR, r.message ?: tr("vcs.init.failed"))
            loadRoots()
        }
    }
}

/** 提交草稿 */
class VcsCommitDraft {
    var message by mutableStateOf("")
    var amend by mutableStateOf(false)
    var signOff by mutableStateOf(false)
    var noVerify by mutableStateOf(false)
    var author by mutableStateOf("")
    var committing by mutableStateOf(false)
}

/** 一个仓库：状态、分支、标签、储藏、远程；写操作经 [perform] / [runThrowing] 统一处理结果。 */
class VcsRepo(val session: VcsSession, val root: String, val name: String) {
    val projectId: String get() = session.projectId
    private val preview: Boolean get() = session.preview

    var status by mutableStateOf<VCSStatus?>(null)
        private set
    var statusError by mutableStateOf<String?>(null)
        private set
    var branches by mutableStateOf<VCSBranches?>(null)
        private set
    var tags by mutableStateOf<List<VCSTag>>(emptyList())
        private set
    var stashes by mutableStateOf<List<VCSStash>>(emptyList())
        private set
    var remotes by mutableStateOf<List<VCSRemote>>(emptyList())
        private set
    var remotesLoaded by mutableStateOf(false)
        private set
    var notice by mutableStateOf<VcsNotice?>(null)
    var activity by mutableStateOf<VcsActivity?>(null)
        private set

    /** 每次仓库变化加一：日志、储藏等子页据此重新加载 */
    var revision by mutableIntStateOf(0)
        private set

    val draft = VcsCommitDraft()
    val log = VcsLogState(this)

    /** 变更列表里折叠的分组 */
    var collapsed by mutableStateOf<Set<String>>(emptySet())

    /** 平板双栏：右侧显示的差异 / 储藏 */
    var selectedChange by mutableStateOf<VcsDiffTarget?>(null)
    var selectedStash by mutableStateOf<Int?>(null)

    private var refreshJob: Job? = null
    private var identityChecked = false
    private var cachedModuleId: String? = null

    fun api(): OpCaller = AppModel.api()

    // ---------------------------------------------------------------- 预览

    internal fun setPreview(
        status: VCSStatus? = this.status,
        branches: VCSBranches? = this.branches,
        stashes: List<VCSStash> = this.stashes,
        tags: List<VCSTag> = this.tags,
        remotes: List<VCSRemote> = this.remotes,
    ) {
        this.status = status
        this.branches = branches
        this.stashes = stashes
        this.tags = tags
        this.remotes = remotes
        remotesLoaded = true
    }

    // ---------------------------------------------------------------- 读

    /** 合并短时间内的多次刷新 */
    fun scheduleRefresh() {
        if (preview) return
        refreshJob?.cancel()
        refreshJob = AppModel.scope.launch {
            delay(150)
            refresh()
        }
    }

    /** 仓库被改动（vcs.changed）：刷新状态，通知子页 */
    fun invalidate() {
        revision++
        scheduleRefresh()
    }

    suspend fun refresh() {
        loadStatus()
        if (branches != null) loadBranches()
        if (stashes.isNotEmpty() || (status?.stashCount ?: 0) > 0) loadStashes()
    }

    suspend fun loadStatus() {
        if (preview) return
        try {
            val s = api().vcsStatus(projectId, root)
            applyStatus(s)
            statusError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            statusError = e.message ?: tr("common.failed")
        }
    }

    private fun applyStatus(s: VCSStatus) {
        val old = status
        if (s == old) return
        // HEAD 或分支变了（终端里提交、切分支等外部操作）：日志等子页也要刷新
        val moved = old != null && (old.headHash != s.headHash || old.branch != s.branch)
        status = s
        if (moved) revision++
    }

    /** 用总览（vcs.overview）取到的状态更新：总览不统计增删行数，沿用已有的 */
    fun applyOverviewStatus(s: VCSStatus) {
        val old = status
        val merged = if (old == null) s else {
            val byPath = old.files.associateBy { it.path }
            s.copy(files = s.files.map { f ->
                val o = byPath[f.path]
                if (o != null && o.status == f.status) f.copy(additions = o.additions, deletions = o.deletions, binary = o.binary) else f
            })
        }
        applyStatus(merged)
        statusError = null
    }

    suspend fun loadBranches() {
        if (preview) return
        runCatching { api().vcsBranches(projectId, root) }.getOrNull()?.let { if (it != branches) branches = it }
    }

    suspend fun loadTags() {
        if (preview) return
        runCatching { api().vcsTags(projectId, root) }.getOrNull()?.let { tags = it.tags }
    }

    suspend fun loadStashes() {
        if (preview) return
        runCatching { api().vcsStashList(projectId, root) }.getOrNull()?.let { stashes = it.stashes }
    }

    suspend fun loadRemotes() {
        if (preview) return
        runCatching { api().vcsRemotes(projectId, root) }.getOrNull()?.let {
            remotes = it.remotes
            remotesLoaded = true
        }
    }

    /** 当前分支名（分离头时为短哈希） */
    val headTitle: String
        get() {
            val s = status ?: return ""
            if (s.branch.isNotEmpty()) return s.branch
            return VcsStyle.shortHash(s.headHash ?: s.head)
        }

    val currentBranch: String?
        get() {
            val s = status ?: return null
            return s.branch.takeIf { it.isNotEmpty() && !s.detached }
        }

    /** project.action 需要模块：取工程第一个模块（vcs.* 插件动作与模块无关） */
    suspend fun firstModuleId(): String {
        cachedModuleId?.let { return it }
        val id = api().getProject(projectId).modules.firstOrNull()?.id.orEmpty()
        cachedModuleId = id
        return id
    }

    /** 本仓库正在运行的远程任务（push / pull / fetch 的控制台） */
    val runningTask: TaskInfo?
        get() = AppModel.runs.activeTasks.firstOrNull { it.kind == "vcs" && it.projectId == projectId }

    // ---------------------------------------------------------------- 写

    /**
     * 后台执行一个写操作（按钮直接触发的），按结果给出提示或下一步按钮；API 错误弹提示。
     * @param op 操作名（标题栏按钮据此显示转圈），[long] 为 true 时生效
     * @param quiet 成功时不提示
     * @param extra 失败 / 需要下一步时追加的按钮
     */
    fun perform(
        title: String,
        op: String = "",
        long: Boolean = false,
        quiet: Boolean = false,
        extra: ((VCSOpResult) -> List<VcsNotice.Action>)? = null,
        then: ((VCSOpResult) -> Unit)? = null,
        call: suspend (OpCaller) -> VCSOpResult,
    ): Job = AppModel.scope.launch {
        val r = try {
            runThrowing(title, op, long, quiet, extra, call)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showError(e, title)
            null
        }
        if (r != null) then?.invoke(r)
    }

    /** 同上，但 API 错误抛出（面板里调用，错误显示在面板内） */
    suspend fun runThrowing(
        title: String,
        op: String = "",
        long: Boolean = false,
        quiet: Boolean = false,
        extra: ((VCSOpResult) -> List<VcsNotice.Action>)? = null,
        call: suspend (OpCaller) -> VCSOpResult,
    ): VCSOpResult {
        if (long) activity = VcsActivity(op, title)
        try {
            val r = call(api())
            present(r, title, quiet, extra) { perform(title, op, long, quiet, extra, null, call) }
            scheduleRefresh()
            return r
        } finally {
            if (long) activity = null
        }
    }

    /** API 错误：插件带回 reason 时给下一步按钮，否则轻提示 */
    fun showError(e: Exception, title: String) {
        val reason = (e as? ApiException)?.reason
        when (reason) {
            "noRemote" -> notice = VcsNotice(
                Tone.Warning, e.message ?: title, title,
                actions = listOf(VcsNotice.Action(tr("vcs.remote.add").plain(), primary = true) {
                    notice = null
                    VcsSheets.remoteEdit(this, null)
                }),
            )
            VCSReason.IDENTITY_MISSING -> notice = VcsNotice(
                Tone.Danger, e.message ?: title, title,
                actions = listOf(VcsNotice.Action(tr("vcs.identity.set").plain(), primary = true) {
                    notice = null
                    VcsSheets.identity(this, emptyList()) {}
                }),
            )
            else -> Toasts.error(e, title)
        }
    }

    /** 结果 → 提示。成功：轻提示；失败或需要下一步：页内提示条。 */
    fun present(r: VCSOpResult, title: String, quiet: Boolean = false, extra: ((VCSOpResult) -> List<VcsNotice.Action>)? = null, retry: (() -> Unit)? = null) {
        val reason = r.reason ?: when {
            r.needsAuth -> VCSReason.NEEDS_AUTH
            r.rejected -> VCSReason.REJECTED
            else -> null
        }
        if (r.ok && r.conflicts.isEmpty()) {
            notice = null
            if (quiet) return
            val msg = r.message?.takeIf { it.isNotEmpty() }
            if (reason == VCSReason.UP_TO_DATE || reason == VCSReason.NO_CHANGES) {
                Toasts.show(msg ?: title, ToastKind.Info)
            } else {
                Toasts.show(title, ToastKind.Success, msg)
            }
            return
        }
        var tone = if (r.ok) Tone.Warning else Tone.Danger
        val head = r.message?.takeIf { it.isNotEmpty() } ?: title
        var message: String? = if (head != title) title else null
        val acts = (extra?.invoke(r) ?: emptyList()).toMutableList()
        fun dismissAnd(block: () -> Unit): () -> Unit = {
            notice = null
            block()
        }
        when (reason) {
            VCSReason.CONFLICTS, VCSReason.AUTOSTASH_CONFLICT, VCSReason.UNRESOLVED_CONFLICTS -> {
                tone = Tone.Warning
                if (r.conflicts.isNotEmpty()) message = r.conflicts.take(8).joinToString("\n")
                acts.add(0, VcsNotice.Action(tr("vcs.action.resolveConflicts"), primary = true, run = dismissAnd { session.page = VcsPage.CONFLICTS }))
            }
            VCSReason.REJECTED -> {
                tone = Tone.Warning
                acts.add(VcsNotice.Action(tr("vcs.pull").plain(), primary = acts.isEmpty(), run = dismissAnd { VcsSheets.pull(this) }))
            }
            VCSReason.NEEDS_AUTH, VCSReason.HOST_KEY, VCSReason.NETWORK, VCSReason.LOCKED, VCSReason.REMOTE_REJECTED -> {
                if (retry != null) acts.add(VcsNotice.Action(tr("vcs.retry"), primary = acts.isEmpty(), run = dismissAnd(retry)))
            }
            VCSReason.NO_UPSTREAM -> acts.add(
                VcsNotice.Action(tr("vcs.branch.setUpstream").plain(), primary = acts.isEmpty(), run = dismissAnd {
                    VcsSheets.setUpstream(this, currentBranch.orEmpty())
                }),
            )
            VCSReason.IDENTITY_MISSING -> acts.add(
                VcsNotice.Action(tr("vcs.identity.set").plain(), primary = true, run = dismissAnd { VcsSheets.identity(this, emptyList()) {} }),
            )
            VCSReason.EMPTY_COMMIT -> {
                tone = Tone.Warning
                acts.add(VcsNotice.Action(tr("vcs.op.skip"), primary = acts.isEmpty(), run = dismissAnd {
                    perform(tr("vcs.op.skip")) { api -> api.vcsSkip(projectId, root) }
                }))
            }
            VCSReason.LOCAL_CHANGES, VCSReason.UNTRACKED_OVERWRITTEN -> {
                if (r.files.isNotEmpty()) message = r.files.take(8).joinToString("\n") + if (r.files.size > 8) "\n…" else ""
            }
        }
        notice = VcsNotice(tone, head, message, r.output, r.taskId, acts)
    }

    // ---------------------------------------------------------------- 提交

    /** 提交前确认已设置 user.name / user.email */
    fun ensureIdentity(then: () -> Unit) {
        if (identityChecked) {
            then()
            return
        }
        AppModel.scope.launch {
            try {
                val cfg = api().vcsConfigGet(projectId, root, listOf("user.name", "user.email"))
                val missing = listOf("user.name", "user.email").filter { cfg.values[it].isNullOrBlank() }
                if (missing.isEmpty()) {
                    identityChecked = true
                    then()
                } else {
                    VcsSheets.identity(this@VcsRepo, missing) {
                        identityChecked = true
                        then()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                then() // 读不到配置就交给插件判断
            }
        }
    }

    /** 已暂存的文件数 */
    fun stagedCount(s: VCSStatus): Int = s.files.count { !it.conflicted && !it.indexStatus.isNullOrEmpty() }

    /** 没有暂存任何文件时，提交全部已修改的已跟踪文件（与桌面非暂存区模式的默认勾选一致；不含未跟踪文件） */
    fun fallbackPaths(s: VCSStatus): List<String> = s.files.filter { !it.conflicted && !it.isUntracked }.map { it.path }

    fun canCommit(s: VCSStatus?): Boolean {
        if (s == null || draft.committing || s.conflicts > 0) return false
        val merging = s.operation == "merge"
        if (draft.message.isBlank() && !draft.amend && !merging) return false
        val n = stagedCount(s).takeIf { it > 0 } ?: if (draft.amend) 0 else fallbackPaths(s).size
        return n > 0 || draft.amend || merging
    }

    /** 提交。andPush：提交后打开推送面板 */
    fun commit(andPush: Boolean) {
        val st = status ?: return
        if (draft.committing) return
        ensureIdentity { doCommit(st, andPush) }
    }

    private fun doCommit(st: VCSStatus, andPush: Boolean) {
        val d = draft
        val msg = d.message.trim()
        var args = VCSCommitArgs(
            message = msg,
            amend = d.amend.takeIf { it },
            signOff = d.signOff.takeIf { it },
            noVerify = d.noVerify.takeIf { it },
            author = d.author.takeIf { it.isNotEmpty() },
        )
        if (stagedCount(st) == 0 && !d.amend) {
            val paths = fallbackPaths(st)
            if (paths.isEmpty()) {
                if (st.operation != "merge") {
                    Toasts.show(tr("vcs.commit.nothingSelected"), ToastKind.Info)
                    return
                }
            } else {
                args = args.copy(paths = paths)
            }
        }
        d.committing = true
        AppModel.scope.launch {
            try {
                val r = api().vcsCommit(projectId, root, args)
                VcsPrefs.remember(msg, root)
                d.message = ""
                d.amend = false
                d.author = ""
                notice = null
                Toasts.show(tr("vcs.commit.done", r.short ?: VcsStyle.shortHash(r.hash)), ToastKind.Success)
                scheduleRefresh()
                if (andPush) VcsSheets.push(this@VcsRepo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if ((e as? ApiException)?.reason == VCSReason.IDENTITY_MISSING) {
                    VcsSheets.identity(this@VcsRepo, emptyList()) { commit(andPush) }
                } else {
                    val out = ((e as? ApiException)?.data as? JsonObject)?.get("output")?.let { (it as? JsonPrimitive)?.content }
                    notice = VcsNotice(Tone.Danger, tr("vcs.commit.failed"), e.message, output = out)
                }
            } finally {
                d.committing = false
            }
        }
    }
}

/** 去掉菜单文字末尾的省略号（面板标题、按钮上用） */
fun String.plain(): String = trimEnd('…', '.', ' ')
