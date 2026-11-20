package cn.lemonit.paperplane.features.vcs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.Route
import cn.lemonit.paperplane.kit.model.VCSCommit
import cn.lemonit.paperplane.kit.model.VCSFileHistoryArgs
import cn.lemonit.paperplane.kit.model.VCSLogArgs
import cn.lemonit.paperplane.kit.model.VCSOp
import cn.lemonit.paperplane.kit.model.VCSRebasePlan
import cn.lemonit.paperplane.kit.model.VCSRebaseStep
import cn.lemonit.paperplane.kit.model.VCSReason
import cn.lemonit.paperplane.kit.model.VCSRemote
import cn.lemonit.paperplane.kit.vcsFileHistory
import cn.lemonit.paperplane.kit.vcsLog
import cn.lemonit.paperplane.kit.vcsRebaseInteractive
import cn.lemonit.paperplane.kit.vcsRemoteRemove
import cn.lemonit.paperplane.kit.vcsShow
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperInfoButton
import cn.lemonit.paperplane.ui.components.PaperLoadingPage
import cn.lemonit.paperplane.ui.components.PaperLongPressArea
import cn.lemonit.paperplane.ui.components.PaperMoreButton
import cn.lemonit.paperplane.ui.components.PaperSelect
import cn.lemonit.paperplane.ui.components.PaperTextArea
import cn.lemonit.paperplane.ui.components.SelectOption
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.shell.PageScaffold
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// 版本控制的二级页面（手机上一屏一件事）：差异、提交详情、储藏详情、远程仓库、文件历史、交互式变基。

// ---------------------------------------------------------------- 差异

/** 差异页。targets 是同一组文件（变更列表 / 提交的文件），页内可前后切换。 */
data class VcsDiffRoute(
    val projectId: String,
    val root: String,
    val targets: List<VcsDiffTarget>,
    val index: Int,
    val title: String? = null,
) : Route {
    override val key get() = "vcs-diff:$root:${targets.getOrNull(index)}"

    @Composable
    override fun Content() = VcsDiffPage(this)
}

@Composable
private fun VcsDiffPage(route: VcsDiffRoute) {
    val repo = remember(route.projectId, route.root) { VcsStore.repo(route.projectId, route.root) }
    var index by rememberSaveable { mutableStateOf(route.index) }
    var wrap by remember { mutableStateOf(VcsPrefs.diffWrap) }
    val scope = rememberCoroutineScope()
    val state = remember(repo) { VcsDiffState(repo, scope) }
    val t = route.targets.getOrNull(index) ?: route.targets.first()
    // 工作区差异：仓库变了（暂存 / 外部修改）就重取
    LaunchedEffect(t, repo.revision, repo.status) { if (state.target != t) state.set(t) else if (t.isWorking) state.reload() }
    val listState = rememberLazyListState()
    var current by remember(t) { mutableStateOf(-1) }
    val layout = state.layout
    val nav = AppModel.nav
    val name = VcsPath.name(t.path)
    val old = t.oldPath?.takeIf { it != t.path }
    PageScaffold(
        title = name,
        subtitle = if (old != null) "$old → ${t.path}" else VcsPath.dir(t.path).ifEmpty { route.title },
        actions = {
            if (route.targets.size > 1) {
                PaperIconButton("chevron-left", tr("vcs.mobile.diff.prevFile"), onClick = { index-- }, enabled = index > 0)
                Text("${index + 1}/${route.targets.size}", style = PaperTheme.typography.monoSmall, color = PaperTheme.colors.textTertiary)
                PaperIconButton("chevron-right", tr("vcs.mobile.diff.nextFile"), onClick = { index++ }, enabled = index < route.targets.lastIndex)
            }
            PaperMoreButton({
                buildList {
                    add(MenuEntry.Item(tr("vcs.diff.wrap"), checked = wrap) {
                        wrap = !wrap
                        VcsPrefs.diffWrap = wrap
                    })
                    add(MenuEntry.Item(tr("vcs.diff.ignoreWhitespace"), checked = state.ignoreWhitespace) {
                        state.ignoreWhitespace = !state.ignoreWhitespace
                        VcsPrefs.diffIgnoreWhitespace = state.ignoreWhitespace
                        state.reload()
                    })
                    add(MenuEntry.Item(tr("vcs.diff.expandAll"), "unfold-vertical", enabled = state.diff?.hunks?.isNotEmpty() == true) { state.expandAll() })
                    add(MenuEntry.Divider)
                    if (t.isWorking && t.status != "D") add(MenuEntry.Item(tr("vcs.mobile.openInEditor"), "file-pen") { VcsActions.openInEditor(repo, t.path) })
                    if (!(t.isWorking && (t.status == "?" || t.status == "A"))) {
                        add(MenuEntry.Item(tr("vcs.fileHistory"), "history") { nav.push(VcsFileHistoryRoute(repo.projectId, repo.root, t.path)) })
                    }
                    add(MenuEntry.Item(tr("vcs.copyPath"), "copy") { Actions.copy(t.path) })
                }
            }, tint = PaperTheme.colors.iconPrimary)
        },
        header = {
            VcsDiffInfoRow(t, state.diff, layout, current, state.loading, onJump = { delta ->
                val starts = layout?.changeStarts.orEmpty()
                if (starts.isNotEmpty()) {
                    current = (current + delta).coerceIn(0, starts.lastIndex)
                    scope.launch { listState.animateScrollToItem(starts[current]) }
                }
            })
        },
        bottomBar = workingBarFor(repo, t),
    ) {
        Column(Modifier.fillMaxSize()) {
            VcsRepoMessages(repo)
            VcsDiffBody(
                state.diff, layout, Modifier.weight(1f),
                path = t.path, loading = state.loading, error = state.error, wrap = wrap,
                hunkMode = state.hunkMode, busyHunk = state.busyHunk,
                oldImage = state.oldImage, newImage = state.newImage, oldSize = state.oldSize, newSize = state.newSize,
                listState = listState,
                onRetry = { state.reload() },
                onShowAnyway = {
                    state.noLimit = true
                    state.reload()
                },
                onExpandGap = { state.expand(it) },
                onStageHunk = { state.hunkOp(VCSOp.STAGE_HUNKS, it) },
                onUnstageHunk = { state.hunkOp(VCSOp.UNSTAGE_HUNKS, it) },
                onDiscardHunk = { state.confirmDiscardHunk(it) },
            )
        }
    }
}

/** 工作区差异底部的整文件操作（暂存 / 取消暂存 / 丢弃 / 解决冲突）；做完回到列表 */
internal fun workingBarFor(repo: VcsRepo, t: VcsDiffTarget): (@Composable () -> Unit)? {
    val side = t.side ?: return null
    return {
        val f = repo.status?.files?.firstOrNull { it.path == t.path }
        val back = { AppModel.nav.pop() }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = PaperTheme.spacing.lg, vertical = PaperTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm),
        ) {
            when (side) {
                VcsSide.CONFLICT -> {
                    PaperButton(tr("vcs.conflicts.useOurs"), onClick = { VcsActions.resolve(repo, listOf(t.path), "ours") { back() } }, modifier = Modifier.weight(1f), size = ButtonSize.Small)
                    PaperButton(tr("vcs.conflicts.useTheirs"), onClick = { VcsActions.resolve(repo, listOf(t.path), "theirs") { back() } }, modifier = Modifier.weight(1f), size = ButtonSize.Small)
                    PaperButton(tr("vcs.conflicts.markResolved"), onClick = { VcsActions.resolve(repo, listOf(t.path), "resolved") { back() } }, kind = ButtonKind.Primary, modifier = Modifier.weight(1f), size = ButtonSize.Small)
                }
                VcsSide.STAGED -> {
                    PaperButton(tr("vcs.unstage"), onClick = {
                        VcsActions.unstage(repo, listOf(t.path))
                        back()
                    }, icon = "minus", modifier = Modifier.weight(1f))
                }
                VcsSide.UNSTAGED, VcsSide.UNTRACKED -> {
                    PaperButton(tr("vcs.discard.ellipsis").plain(), onClick = {
                        if (f != null) VcsSheets.discard(repo, listOf(f), if (side == VcsSide.UNSTAGED) "unstaged" else null) { back() }
                    }, icon = "undo-2", enabled = f != null, modifier = Modifier.weight(1f))
                    PaperButton(tr("vcs.stage"), onClick = {
                        VcsActions.stage(repo, listOf(t.path))
                        back()
                    }, kind = ButtonKind.Primary, icon = "plus", modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 提交详情

data class VcsCommitRoute(val projectId: String, val root: String, val hash: String) : Route {
    override val key get() = "vcs-commit:$root:$hash"

    @Composable
    override fun Content() {
        val repo = remember(projectId, root) { VcsStore.repo(projectId, root) }
        val nav = AppModel.nav
        val c = repo.log.commits.firstOrNull { it.hash == hash || it.hash.startsWith(hash) }
        PageScaffold(
            title = c?.short ?: hash.take(8),
            subtitle = c?.subject,
            actions = {
                PaperIconButton("copy", tr("vcs.copyHash"), onClick = { Actions.copy(c?.hash ?: hash) })
                if (c != null) PaperMoreButton({ VcsActions.commitEntries(repo, c) }, tint = PaperTheme.colors.iconPrimary)
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                VcsRepoMessages(repo)
                VcsCommitDetailView(repo, hash, onOpenCommit = { nav.push(VcsCommitRoute(projectId, root, it)) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------- 储藏详情

data class VcsStashRoute(val projectId: String, val root: String, val index: Int) : Route {
    override val key get() = "vcs-stash:$root:$index"

    @Composable
    override fun Content() {
        val repo = remember(projectId, root) { VcsStore.repo(projectId, root) }
        val s = repo.stashes.firstOrNull { it.index == index }
        PageScaffold(
            title = "stash@{$index}",
            subtitle = s?.message,
            actions = { if (s != null) PaperMoreButton({ VcsActions.stashEntries(repo, s) }, tint = PaperTheme.colors.iconPrimary) },
        ) {
            Column(Modifier.fillMaxSize()) {
                VcsRepoMessages(repo)
                if (s == null) {
                    PaperEmptyState("archive", tr("vcs.stash.empty"), Modifier.fillMaxSize())
                } else {
                    androidx.compose.runtime.key(s.hash) { VcsStashDetail(repo, s, onGone = { AppModel.nav.pop() }) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 远程仓库

data class VcsRemotesRoute(val projectId: String, val root: String) : Route {
    override val key get() = "vcs-remotes:$root"

    @Composable
    override fun Content() {
        val repo = remember(projectId, root) { VcsStore.repo(projectId, root) }
        LaunchedEffect(Unit) { repo.loadRemotes() }
        PageScaffold(
            title = tr("vcs.mobile.remotes"),
            subtitle = repo.name,
            actions = { PaperIconButton("plus", tr("vcs.remote.add").plain(), onClick = { VcsSheets.remoteEdit(repo, null) }) },
        ) {
            Column(Modifier.fillMaxSize()) {
                VcsRepoMessages(repo)
                when {
                    !repo.remotesLoaded -> PaperLoadingPage()
                    repo.remotes.isEmpty() -> PaperEmptyState(
                        "server", tr("vcs.mobile.remotes.empty"), Modifier.fillMaxSize(),
                        actionText = tr("vcs.remote.add").plain(), actionIcon = "plus",
                    ) { VcsSheets.remoteEdit(repo, null) }
                    else -> VcsRemoteList(repo.remotes, onEdit = { VcsSheets.remoteEdit(repo, it) }, onRemove = { removeRemote(repo, it) })
                }
            }
        }
    }
}

private fun removeRemote(repo: VcsRepo, r: VCSRemote) {
    Dialogs.confirm(tr("vcs.mobile.remote.removeTitle", r.name), tr("vcs.mobile.remote.removeMessage"), confirmText = tr("common.remove"), danger = true) {
        repo.runThrowing(tr("vcs.mobile.remote.remove"), quiet = true) { api -> api.vcsRemoteRemove(repo.projectId, repo.root, r.name) }
        repo.loadRemotes()
        repo.loadBranches()
    }
}

/** 远程列表（无状态） */
@Composable
fun VcsRemoteList(remotes: List<VCSRemote>, onEdit: (VCSRemote) -> Unit = {}, onRemove: (VCSRemote) -> Unit = {}) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    LazyColumn(Modifier.fillMaxSize()) {
        items(remotes, key = { it.name }) { r ->
            val menu = {
                listOf(
                    MenuEntry.Item(tr("vcs.mobile.remote.edit"), "pencil") { onEdit(r) },
                    MenuEntry.Item(tr("vcs.remote.url"), "copy") { Actions.copy(r.fetchUrl) },
                    MenuEntry.Divider,
                    MenuEntry.Item(tr("vcs.mobile.remote.remove"), "trash-2", destructive = true) { onRemove(r) },
                )
            }
            PaperLongPressArea(onClick = { onEdit(r) }, menu = menu) {
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(start = sp.lg, end = sp.xs, top = sp.sm, bottom = sp.sm),
                    horizontalArrangement = Arrangement.spacedBy(sp.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PaperIcon("cloud", size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(r.name, style = PaperTheme.typography.bodyStrong, color = c.textPrimary)
                        Text(r.fetchUrl, style = PaperTheme.typography.monoSmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        val push = r.pushUrl
                        if (push != null && push != r.fetchUrl) {
                            Text("push  $push", style = PaperTheme.typography.monoSmall, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        }
                    }
                    PaperMoreButton(menu)
                }
            }
            PaperDivider(subtle = true)
        }
    }
}

// ---------------------------------------------------------------- 文件历史

data class VcsFileHistoryRoute(val projectId: String, val root: String, val path: String) : Route {
    override val key get() = "vcs-history:$root:$path"

    @Composable
    override fun Content() {
        val repo = remember(projectId, root) { VcsStore.repo(projectId, root) }
        var commits by remember { mutableStateOf<List<VCSCommit>>(emptyList()) }
        var hasMore by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        suspend fun load(skip: Int) {
            try {
                val log = repo.api().vcsFileHistory(projectId, root, VCSFileHistoryArgs(path, limit = 100, skip = skip.takeIf { it > 0 }))
                commits = if (skip == 0) log.commits else commits + log.commits
                hasMore = log.hasMore
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: tr("common.failed")
            }
            loading = false
        }
        LaunchedEffect(Unit) { load(0) }
        PageScaffold(title = tr("vcs.fileHistory"), subtitle = path) {
            val e = error
            when {
                loading -> PaperLoadingPage()
                e != null && commits.isEmpty() -> Column(Modifier.padding(PaperTheme.spacing.lg)) { PaperAlert(Tone.Danger, e) }
                commits.isEmpty() -> PaperEmptyState("history", tr("vcs.fileHistory.empty"), Modifier.fillMaxSize())
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(commits, key = { _, c -> c.hash }) { i, c ->
                        if (i == commits.lastIndex && hasMore) LaunchedEffect(commits.size) { scope.launch { load(commits.size) } }
                        HistoryRow(repo, c, commits)
                        PaperDivider(subtle = true)
                    }
                }
            }
        }
    }

    @Composable
    private fun HistoryRow(repo: VcsRepo, c: VCSCommit, all: List<VCSCommit>) {
        val col = PaperTheme.colors
        val sp = PaperTheme.spacing
        val p = c.path ?: path
        val open = {
            val targets = all.map { VcsDiffTarget(it.path ?: path, null, VcsDiffKind.Commit(it.hash), it.pathStatus) }
            VcsActions.openDiff(repo, targets, all.indexOf(c), VcsPath.name(path))
        }
        val menu = {
            listOf(
                MenuEntry.Item(tr("vcs.showDiff"), "file-diff") { open() },
                MenuEntry.Item(tr("vcs.showInLog"), "git-commit-horizontal") { VcsActions.openCommit(repo, c.hash) },
                MenuEntry.Item(tr("vcs.checkoutFileVersion"), "rotate-ccw", enabled = c.pathStatus != "D") { VcsActions.restoreFile(repo, p, c.hash, c.short) },
                MenuEntry.Divider,
                MenuEntry.Item(tr("vcs.copyHash"), "copy") { Actions.copy(c.hash) },
            )
        }
        PaperLongPressArea(onClick = open, menu = menu) {
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 60.dp).padding(horizontal = sp.lg, vertical = sp.sm),
                horizontalArrangement = Arrangement.spacedBy(sp.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VcsStatusLetter(c.pathStatus ?: "M")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(c.subject, style = PaperTheme.typography.body, color = col.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(c.short, c.author, VcsDate.short(c.parsedDate)).filter { it.isNotEmpty() }.joinToString("  ·  "),
                        style = PaperTheme.typography.small,
                        color = col.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (p != path) Text("← $p", style = PaperTheme.typography.small, color = col.textTertiary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 交互式变基

/** 交互式变基编辑器：from（含）到 HEAD 的提交，旧的在上；每行 保留 / 改信息 / 合并 / 合并（丢弃信息）/ 丢弃，上下移动 */
data class VcsRebaseRoute(val projectId: String, val root: String, val from: VCSCommit) : Route {
    override val key get() = "vcs-rebase:$root"

    @Composable
    override fun Content() = VcsRebasePage(this)
}

/** 编辑器里的一步 */
data class VcsRebaseItem(val commit: VCSCommit, val action: String = "pick", val message: String? = null, val original: String? = null, val editing: Boolean = false)

object VcsRebaseRules {
    val actions = listOf("pick", "reword", "squash", "fixup", "drop")

    /** 校验：第一个保留的提交不能是 squash/fixup；reword 的新信息不能为空 */
    fun validate(steps: List<VcsRebaseItem>): String? {
        val kept = steps.filter { it.action != "drop" }
        val first = kept.firstOrNull()
        if (first != null && (first.action == "squash" || first.action == "fixup")) return tr("vcs.rebase.firstSquash")
        if (steps.any { it.action == "reword" && (it.message ?: it.commit.subject).isBlank() }) return tr("vcs.rebase.emptyMessage")
        return null
    }

    fun plan(base: String, steps: List<VcsRebaseItem>) = VCSRebasePlan(base, steps.map { s ->
        val msg = if (s.action == "reword" || s.action == "squash") s.message?.trim()?.ifEmpty { null } else null
        VCSRebaseStep(s.commit.hash, s.action, msg)
    })
}

@Composable
private fun VcsRebasePage(route: VcsRebaseRoute) {
    val repo = remember(route.projectId, route.root) { VcsStore.repo(route.projectId, route.root) }
    val base = route.from.parents.firstOrNull().orEmpty()
    var steps by remember { mutableStateOf<List<VcsRebaseItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reloadKey) {
        loading = true
        error = null
        try {
            val ref = if (base.isEmpty()) "HEAD" else "$base..HEAD"
            val log = repo.api().vcsLog(repo.projectId, repo.root, VCSLogArgs(ref = ref, limit = 500, noGraph = true))
            error = when {
                log.hasMore -> tr("vcs.rebase.tooMany")
                log.commits.any { it.parents.size > 1 } -> tr("vcs.rebase.merges")
                log.commits.none { it.hash == route.from.hash } -> tr("vcs.rebase.notAncestor")
                else -> null
            }
            steps = log.commits.reversed().map { VcsRebaseItem(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: tr("common.failed")
        }
        loading = false
    }
    fun update(i: Int, f: (VcsRebaseItem) -> VcsRebaseItem) {
        steps = steps.toMutableList().also { it[i] = f(it[i]) }
    }
    fun ensureMessage(hash: String) {
        val i = steps.indexOfFirst { it.commit.hash == hash }
        if (i < 0 || steps[i].original != null) return
        scope.launch {
            val d = runCatching { repo.api().vcsShow(repo.projectId, repo.root, hash) }.getOrNull()
            val j = steps.indexOfFirst { it.commit.hash == hash }
            if (j < 0) return@launch
            val full = d?.let { VcsSheets.fullMessage(it.message, it.commit.subject, it.body) } ?: steps[j].commit.subject
            update(j) { it.copy(original = full, message = it.message ?: full) }
        }
    }
    fun move(from: Int, to: Int) {
        if (from !in steps.indices || to !in steps.indices) return
        steps = steps.toMutableList().also { it.add(to, it.removeAt(from)) }
    }
    val validation = VcsRebaseRules.validate(steps)
    PageScaffold(
        title = tr("vcs.rebase.title", route.from.short),
        subtitle = tr("vcs.rebase.onto", if (base.isEmpty()) tr("vcs.rebase.root") else base.take(8)),
        actions = { PaperInfoButton(tr("vcs.rebase.info")) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(horizontal = PaperTheme.spacing.lg, vertical = PaperTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm)) {
                val msg = validation ?: error
                if (msg != null && !loading) PaperAlert(Tone.Danger, msg)
                Row(horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md)) {
                    PaperButton(tr("vcs.rebase.reset"), onClick = { reloadKey++ }, enabled = !loading && !busy, modifier = Modifier.weight(1f))
                    PaperButton(
                        tr("vcs.rebase.start"),
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val r = repo.runThrowing(tr("vcs.rebaseInteractive"), VCSOp.REBASE_INTERACTIVE, long = true) { api ->
                                        api.vcsRebaseInteractive(repo.projectId, repo.root, VcsRebaseRules.plan(base, steps))
                                    }
                                    if (r.ok || r.conflicts.isNotEmpty() || r.reason == VCSReason.CONFLICTS) AppModel.nav.pop()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = e.message ?: tr("common.failed")
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        kind = ButtonKind.Primary,
                        loading = busy,
                        enabled = !loading && validation == null && error == null && steps.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    ) {
        if (loading) {
            PaperLoadingPage()
            return@PageScaffold
        }
        LazyColumn(Modifier.fillMaxSize().imePadding()) {
            itemsIndexed(steps, key = { _, s -> s.commit.hash }) { i, s ->
                RebaseRow(
                    s, i, steps.size,
                    onAction = { a ->
                        update(i) { it.copy(action = a, editing = (a == "reword" || a == "squash") || it.editing && (a == "reword" || a == "squash")) }
                        if (a == "reword") ensureMessage(s.commit.hash)
                    },
                    onEdit = {
                        if (s.action == "reword") ensureMessage(s.commit.hash)
                        update(i) { it.copy(editing = !it.editing) }
                    },
                    onMessage = { m -> update(i) { it.copy(message = m) } },
                    onMove = { d -> move(i, i + d) },
                )
                PaperDivider(subtle = true)
            }
            item { Box(Modifier.height(PaperTheme.spacing.xl)) }
        }
    }
}

@Composable
private fun RebaseRow(
    s: VcsRebaseItem,
    index: Int,
    count: Int,
    onAction: (String) -> Unit,
    onEdit: () -> Unit,
    onMessage: (String) -> Unit,
    onMove: (Int) -> Unit,
) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val edited = s.message != null && s.message != s.original
    Column(Modifier.fillMaxWidth().padding(start = sp.lg, end = sp.xs, top = sp.sm, bottom = sp.sm), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.sm)) {
            Text(s.commit.short, style = PaperTheme.typography.monoSmall, color = c.textTertiary)
            Text(
                (s.message ?: s.commit.subject).lineSequence().firstOrNull().orEmpty(),
                style = PaperTheme.typography.body.copy(textDecoration = strike(s.action == "drop")),
                color = when {
                    s.action == "drop" -> c.textTertiary
                    edited -> c.accent
                    else -> c.textPrimary
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaperSelect(
                VcsRebaseRules.actions.map { SelectOption(it, tr("vcs.rebase.action.$it")) },
                s.action,
                onAction,
                title = s.commit.short,
                size = ButtonSize.Small,
                modifier = Modifier.weight(1f),
            )
            if (s.action == "reword" || s.action == "squash") {
                PaperIconButton("pencil", tr("vcs.rebase.editMessage"), onClick = onEdit, active = s.editing)
            }
            PaperIconButton("arrow-up", tr("vcs.rebase.moveUp"), onClick = { onMove(-1) }, enabled = index > 0)
            PaperIconButton("arrow-down", tr("vcs.rebase.moveDown"), onClick = { onMove(1) }, enabled = index < count - 1)
        }
        if (s.editing && (s.action == "reword" || s.action == "squash")) {
            PaperTextArea(
                s.message.orEmpty(),
                onMessage,
                placeholder = if (s.action == "squash") tr("vcs.rebase.squashPlaceholder") else null,
                minLines = 3,
                maxLines = 10,
                modifier = Modifier.padding(end = sp.md),
            )
        }
    }
}
