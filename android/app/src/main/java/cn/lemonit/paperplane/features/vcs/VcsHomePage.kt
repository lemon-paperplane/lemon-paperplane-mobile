package cn.lemonit.paperplane.features.vcs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.PluginsRoute
import cn.lemonit.paperplane.kit.ApiException
import cn.lemonit.paperplane.kit.model.VCSFetchArgs
import cn.lemonit.paperplane.kit.model.VCSPullArgs
import cn.lemonit.paperplane.kit.model.VCSPushArgs
import cn.lemonit.paperplane.kit.model.VCSStatus
import cn.lemonit.paperplane.kit.vcsFetch
import cn.lemonit.paperplane.kit.vcsOverview
import cn.lemonit.paperplane.kit.vcsPull
import cn.lemonit.paperplane.kit.vcsPush
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperBadge
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperCheckbox
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperInfoButton
import cn.lemonit.paperplane.ui.components.PaperLoadingPage
import cn.lemonit.paperplane.ui.components.PaperLongPressArea
import cn.lemonit.paperplane.ui.components.PaperMoreButton
import cn.lemonit.paperplane.ui.components.PaperSearchField
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.components.ToastKind
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.shell.PageScaffold
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 「更多 → 版本控制」：全部工作区里全部工程的全部仓库（vcs.overview），按工作区分组；分支、改动数、领先 / 落后、冲突、
// 进行中的操作一眼看到；点进去是工程详情的版本控制标签；长按单个拉取 / 推送；右上角批量获取 / 拉取 / 推送。
// 对照 Mac VCSOverviewModel / VCSOverviewView（桌面在工作区页里，手机放在「更多」）。仓库状态和工程页共用同一份 VcsRepo。

/** 总览里的一个仓库 */
data class VcsHomeEntry(
    val root: String,
    val projectId: String,
    val projectName: String,
    val relPath: String,
    val name: String,
    val workspaceId: String,
    val error: String? = null,
) {
    /** 仓库名之外的说明：工程名（与仓库名不同时）+ 相对路径 */
    val subtitle: String?
        get() {
            val parts = ArrayList<String>()
            if (projectName != name) parts.add(projectName)
            if (relPath.isNotEmpty() && relPath != "." && relPath != name) parts.add(relPath)
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" / ")
        }
}

/** 批量获取 / 拉取 / 推送的进度 */
data class VcsBatch(val kind: Kind, val total: Int, val done: Int = 0, val failed: Int = 0, val running: Boolean = true) {
    enum class Kind { FETCH, PULL, PUSH }
}

object VcsHome {
    var entries by mutableStateOf<List<VcsHomeEntry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var batch by mutableStateOf<VcsBatch?>(null)
        private set
    var visible = false

    private var serverId: String? = null
    private var reloadJob: Job? = null
    private var subscribed = false

    fun repo(e: VcsHomeEntry): VcsRepo = VcsStore.repo(e.projectId, e.root)

    fun hasChanges(s: VCSStatus?): Boolean = s != null && (s.files.isNotEmpty() || s.operation != null || s.conflicts > 0)

    private fun subscribe() {
        if (subscribed) return
        subscribed = true
        AppModel.scope.launch {
            AppModel.bus.vcsChanged.collect { e ->
                if (!loaded || serverId != AppModel.currentServerId) return@collect
                val entry = e.root?.let { r -> entries.firstOrNull { it.root == r } }
                if (entry != null) repo(entry).scheduleRefresh() else if (entries.any { it.projectId == e.projectId } || visible) scheduleReload()
            }
        }
        AppModel.scope.launch { AppModel.bus.resumed.collect { if (visible) scheduleReload() } }
    }

    fun scheduleReload() {
        reloadJob?.cancel()
        reloadJob = AppModel.scope.launch {
            delay(300)
            reload()
        }
    }

    suspend fun reload() {
        subscribe()
        if (serverId != AppModel.currentServerId) {
            serverId = AppModel.currentServerId
            entries = emptyList()
            loaded = false
            error = null
        }
        if (loading) return
        loading = true
        try {
            val list = AppModel.api().vcsOverview(null)
            val next = list.map { o ->
                val e = VcsHomeEntry(
                    o.root, o.projectId, o.projectName, o.relPath, o.name.ifEmpty { VcsPath.name(o.root) },
                    AppModel.data.projects[o.projectId]?.workspaceId.orEmpty(),
                    if (o.status == null) (o.error ?: "") else null,
                )
                o.status?.let { repo(e).applyOverviewStatus(it) }
                e
            }
            entries = next
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: tr("common.failed")
        } finally {
            loading = false
            loaded = true
        }
    }

    // ---------------------------------------------------------------- 批量

    fun startBatch(kind: VcsBatch.Kind) {
        if (batch?.running == true) return
        val targets = entries.filter { e ->
            val st = repo(e).status
            if (e.error != null || st == null) return@filter false
            val tracking = st.upstream != null && !st.upstreamGone && st.operation == null
            when (kind) {
                VcsBatch.Kind.FETCH -> true
                VcsBatch.Kind.PULL -> tracking && st.behind > 0 && st.conflicts == 0
                VcsBatch.Kind.PUSH -> tracking && st.ahead > 0
            }
        }
        if (targets.isEmpty()) {
            Toasts.show(
                when (kind) {
                    VcsBatch.Kind.FETCH -> tr("vcs.overview.noRepos")
                    VcsBatch.Kind.PULL -> tr("vcs.overview.nothingToPull")
                    VcsBatch.Kind.PUSH -> tr("vcs.overview.nothingToPush")
                },
                ToastKind.Info,
            )
            return
        }
        val names = targets.map { e -> e.subtitle?.let { "${e.name}  ($it)" } ?: e.name }
        val title = when (kind) {
            VcsBatch.Kind.FETCH -> tr("vcs.overview.fetchAllTitle", targets.size)
            VcsBatch.Kind.PULL -> tr("vcs.overview.pullAllTitle", targets.size)
            VcsBatch.Kind.PUSH -> tr("vcs.overview.pushAllTitle", targets.size)
        }
        Dialogs.confirm(title, names.take(20).joinToString("\n") + if (names.size > 20) "\n…" else "", confirmText = batchTitle(kind)) {
            run(kind, targets)
        }
    }

    fun batchTitle(kind: VcsBatch.Kind): String = when (kind) {
        VcsBatch.Kind.FETCH -> tr("vcs.overview.fetchAll")
        VcsBatch.Kind.PULL -> tr("vcs.overview.pullAll")
        VcsBatch.Kind.PUSH -> tr("vcs.overview.pushAll")
    }

    private fun run(kind: VcsBatch.Kind, targets: List<VcsHomeEntry>) {
        batch = VcsBatch(kind, targets.size)
        AppModel.scope.launch {
            var failed = 0
            for ((i, e) in targets.withIndex()) {
                val r = repo(e)
                try {
                    val res = when (kind) {
                        VcsBatch.Kind.FETCH -> r.runThrowing(tr("vcs.fetch"), quiet = true) { api -> api.vcsFetch(r.projectId, r.root, VCSFetchArgs()) }
                        VcsBatch.Kind.PULL -> r.runThrowing(tr("vcs.pull"), quiet = true) { api ->
                            api.vcsPull(r.projectId, r.root, VCSPullArgs(mode = VcsPrefs.pullMode, autostash = VcsPrefs.pullAutostash.takeIf { it }))
                        }
                        VcsBatch.Kind.PUSH -> r.runThrowing(tr("vcs.push"), quiet = true) { api -> api.vcsPush(r.projectId, r.root, VCSPushArgs()) }
                    }
                    if (!res.ok || res.conflicts.isNotEmpty()) failed++
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    // 没配置远程的仓库不算失败
                    if ((ex as? ApiException)?.reason != "noRemote") failed++
                }
                batch = batch?.copy(done = i + 1, failed = failed)
            }
            batch = batch?.copy(running = false)
            Toasts.show(batchTitle(kind), if (failed == 0) ToastKind.Success else ToastKind.Warning, tr("vcs.overview.batchDone", targets.size - failed, failed))
            delay(1500)
            if (batch?.running == false) batch = null
        }
    }
}

/** 「更多 → 版本控制」 */
@Composable
fun VcsHomePage() {
    val data = AppModel.data
    val home = VcsHome
    var query by rememberSaveable { mutableStateOf("") }
    var onlyChanged by rememberSaveable { mutableStateOf(VcsPrefs.homeOnlyChanged) }
    val noPlugin = data.loaded && data.contributions.vcs.isEmpty()
    LaunchedEffect(AppModel.currentServerId, noPlugin) { if (!noPlugin) home.reload() }
    DisposableEffect(Unit) {
        home.visible = true
        onDispose { home.visible = false }
    }
    val running = home.batch?.running == true
    PageScaffold(
        title = tr("mobile.more.vcs"),
        actions = {
            if (home.loading) PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = PaperTheme.colors.iconSecondary)
            PaperIconButton("refresh-cw", tr("vcs.refresh"), onClick = { AppModel.scope.launch { home.reload() } }, enabled = !home.loading)
            PaperMoreButton({
                listOf(
                    MenuEntry.Item(tr("vcs.overview.fetchAll"), "cloud-download", enabled = !running) { home.startBatch(VcsBatch.Kind.FETCH) },
                    MenuEntry.Item(tr("vcs.overview.pullAll"), "arrow-down-to-line", enabled = !running) { home.startBatch(VcsBatch.Kind.PULL) },
                    MenuEntry.Item(tr("vcs.overview.pushAll"), "arrow-up", enabled = !running) { home.startBatch(VcsBatch.Kind.PUSH) },
                    MenuEntry.Divider,
                    MenuEntry.Item(tr("vcs.clone.ellipsis").plain(), "download") { VcsCloneFlow.start() },
                )
            }, tint = PaperTheme.colors.iconPrimary)
        },
    ) {
        when {
            noPlugin -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PaperEmptyState("puzzle", tr("vcs.mobile.noPlugin"), actionText = tr("sidebar.plugins"), actionIcon = "puzzle") { AppModel.nav.push(PluginsRoute) }
            }
            !home.loaded -> PaperLoadingPage()
            home.error != null && home.entries.isEmpty() -> Column(Modifier.padding(PaperTheme.spacing.lg)) {
                PaperAlert(Tone.Danger, tr("vcs.overview.failed"), message = home.error) {
                    PaperButton(tr("connection.retry"), onClick = { AppModel.scope.launch { home.reload() } }, size = ButtonSize.Small)
                }
            }
            home.entries.isEmpty() -> PaperEmptyState("folder-git", tr("vcs.mobile.home.empty"), Modifier.fillMaxSize(), actionText = tr("vcs.clone.ellipsis").plain(), actionIcon = "download") { VcsCloneFlow.start() }
            else -> VcsHomeList(
                // 工作区以当前的工程摘要为准（移动工程后总览可能还没重新取）
                home.entries.map { e -> data.projects[e.projectId]?.workspaceId?.takeIf { it != e.workspaceId }?.let { e.copy(workspaceId = it) } ?: e },
                query = query,
                onQuery = { query = it },
                onlyChanged = onlyChanged,
                onOnlyChanged = {
                    onlyChanged = it
                    VcsPrefs.homeOnlyChanged = it
                },
                batch = home.batch,
                status = { home.repo(it).status },
                workspaceName = { id -> data.workspace(id)?.name },
                onOpen = { e -> VcsStore.session(e.projectId).reveal(e.root) },
                menu = { e -> homeEntries(e) },
            )
        }
    }
}

private fun homeEntries(e: VcsHomeEntry): List<MenuEntry> {
    val r = VcsHome.repo(e)
    val busy = r.activity != null
    return listOf(
        MenuEntry.Item(tr("vcs.overview.openInProject"), "git-branch") { VcsStore.session(e.projectId).reveal(e.root) },
        MenuEntry.Divider,
        MenuEntry.Item(tr("vcs.fetch"), "cloud-download", enabled = !busy) { VcsActions.fetch(r) },
        MenuEntry.Item(tr("vcs.pull"), "arrow-down-to-line", enabled = !busy) { VcsActions.quickPull(r) },
        MenuEntry.Item(tr("vcs.push"), "arrow-up", enabled = !busy) { VcsActions.quickPush(r) },
        MenuEntry.Divider,
        MenuEntry.Item(tr("vcs.copyRepoPath"), "copy") { Actions.copy(e.root) },
    )
}

/** 总览列表（无状态：截图测试直接喂数据） */
@Composable
fun VcsHomeList(
    entries: List<VcsHomeEntry>,
    query: String,
    onQuery: (String) -> Unit,
    onlyChanged: Boolean,
    onOnlyChanged: (Boolean) -> Unit,
    status: (VcsHomeEntry) -> VCSStatus?,
    workspaceName: (String) -> String?,
    modifier: Modifier = Modifier,
    batch: VcsBatch? = null,
    onOpen: (VcsHomeEntry) -> Unit = {},
    menu: ((VcsHomeEntry) -> List<MenuEntry>)? = null,
) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val changed = entries.count { VcsHome.hasChanges(status(it)) }
    val toPush = entries.count { (status(it)?.ahead ?: 0) > 0 }
    val toPull = entries.count { (status(it)?.behind ?: 0) > 0 }
    val q = query.trim()
    val shown = entries.filter { e ->
        val st = status(e)
        if (onlyChanged && !(VcsHome.hasChanges(st) || (st?.ahead ?: 0) > 0 || (st?.behind ?: 0) > 0 || e.error != null)) return@filter false
        q.isEmpty() || e.name.contains(q, true) || e.projectName.contains(q, true) || e.relPath.contains(q, true)
    }
    Column(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = sp.lg, vertical = sp.sm), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.md)) {
                Text(tr("vcs.overview.stats", entries.size, changed), style = PaperTheme.typography.bodyStrong, color = c.textPrimary, modifier = Modifier.weight(1f, fill = false))
                if (toPush > 0) Text(tr("vcs.overview.toPush", toPush), style = PaperTheme.typography.small, color = c.success)
                if (toPull > 0) Text(tr("vcs.overview.toPull", toPull), style = PaperTheme.typography.small, color = c.info)
                PaperInfoButton(tr("vcs.overview.batchInfo"))
            }
            PaperSearchField(query, onQuery, placeholder = tr("vcs.overview.search"))
            PaperCheckbox(onlyChanged, onOnlyChanged, label = tr("vcs.overview.onlyChanged"))
            if (batch != null) {
                Row(Modifier.height(32.dp), horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalAlignment = Alignment.CenterVertically) {
                    if (batch.running) PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = c.accent)
                    else PaperIcon(if (batch.failed == 0) "circle-check" else "triangle-alert", size = PaperTheme.sizes.iconSmall, tint = if (batch.failed == 0) c.success else c.warning)
                    Text(
                        VcsHome.batchTitle(batch.kind) + "  ${batch.done}/${batch.total}",
                        style = PaperTheme.typography.small,
                        color = c.textSecondary,
                    )
                }
            }
        }
        PaperDivider(subtle = true)
        if (shown.isEmpty()) {
            PaperEmptyState("circle-check", if (onlyChanged) tr("vcs.overview.allClean") else tr("vcs.overview.noMatch"), Modifier.fillMaxSize())
            return@Column
        }
        val groups = shown.groupBy { it.workspaceId }
        LazyColumn(Modifier.fillMaxSize()) {
            for ((ws, list) in groups) {
                if (groups.size > 1 || ws.isEmpty()) {
                    item(key = "ws:$ws") { VcsGroupHeader(workspaceName(ws) ?: tr("vcs.mobile.home.otherWorkspace"), list.size) }
                }
                items(list, key = { it.root }) { e ->
                    HomeRow(e, status(e), onOpen, menu)
                    PaperDivider(subtle = true, modifier = Modifier.padding(start = 48.dp))
                }
            }
            item { Box(Modifier.height(sp.huge)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeRow(e: VcsHomeEntry, s: VCSStatus?, onOpen: (VcsHomeEntry) -> Unit, menu: ((VcsHomeEntry) -> List<MenuEntry>)?) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    PaperLongPressArea(onClick = { onOpen(e) }, menu = menu?.let { m -> { m(e) } }) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(start = sp.lg, end = sp.md, top = sp.md, bottom = sp.md),
            horizontalArrangement = Arrangement.spacedBy(sp.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaperIcon("folder-git", tint = c.iconSecondary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                Row(horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalAlignment = Alignment.CenterVertically) {
                    Text(e.name, style = PaperTheme.typography.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    e.subtitle?.let { Text(it, style = PaperTheme.typography.small, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false)) }
                }
                if (s != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalArrangement = Arrangement.spacedBy(sp.xs), itemVerticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalAlignment = Alignment.CenterVertically) {
                            PaperIcon(if (s.detached) "circle-dot" else "git-branch", size = 14.dp, tint = c.iconSecondary)
                            Text(s.branch.ifEmpty { VcsStyle.shortHash(s.headHash ?: s.head) }, style = PaperTheme.typography.small, color = c.textSecondary, maxLines = 1)
                        }
                        VcsAheadBehind(s.ahead, s.behind)
                        if (s.upstreamGone) {
                            PaperIcon("triangle-alert", size = 14.dp, tint = c.warning, contentDescription = tr("vcs.branch.upstreamGone"))
                        } else if (s.upstream == null && !s.detached && !s.unborn) {
                            PaperIcon("cloud-off", size = 14.dp, tint = c.textTertiary, contentDescription = tr("vcs.overview.noUpstream"))
                        }
                        s.operation?.let { PaperBadge(tr("vcs.overview.inProgress", VcsStyle.operationTitle(it)), tone = Tone.Warning) }
                        if (s.conflicts > 0) PaperBadge(tr("vcs.overview.conflicts", s.conflicts), tone = Tone.Danger)
                        val n = s.files.count { !it.conflicted }
                        if (n > 0) PaperBadge(tr("vcs.overview.changes", n), tone = Tone.Info)
                    }
                } else if (e.error != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalAlignment = Alignment.CenterVertically) {
                        PaperIcon("circle-alert", size = 14.dp, tint = c.danger)
                        Text(e.error.ifEmpty { tr("vcs.status.failed") }, style = PaperTheme.typography.small, color = c.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            PaperIcon("chevron-right", size = 16.dp, tint = c.iconSecondary)
        }
    }
}
