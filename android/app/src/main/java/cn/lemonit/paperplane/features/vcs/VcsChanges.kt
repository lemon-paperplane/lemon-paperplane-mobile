package cn.lemonit.paperplane.features.vcs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.kit.model.VCSFileChange
import cn.lemonit.paperplane.kit.model.VCSOp
import cn.lemonit.paperplane.kit.model.VCSStatus
import cn.lemonit.paperplane.kit.vcsShow
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
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
import cn.lemonit.paperplane.ui.components.PaperTextArea
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.launch

// 「提交」子页：变更列表（冲突 / 已暂存 / 未暂存 / 未跟踪）+ 提交区。手机上点文件压栈看差异，平板右侧直接显示差异。
// 手机上只用暂存区模式（逐个暂存 / 取消暂存）；没有暂存任何文件时，提交全部已修改的已跟踪文件（与桌面默认一致）。

/** 一个分组 */
class VcsChangeGroup(val key: String, val side: VcsSide, val files: List<VCSFileChange>)

object VcsChangeGroups {
    fun build(s: VCSStatus): List<VcsChangeGroup> {
        val conflicts = ArrayList<VCSFileChange>()
        val staged = ArrayList<VCSFileChange>()
        val unstaged = ArrayList<VCSFileChange>()
        val untracked = ArrayList<VCSFileChange>()
        for (f in s.files.sortedBy { it.path.lowercase() }) {
            when {
                f.conflicted -> conflicts.add(f)
                f.isUntracked -> untracked.add(f)
                else -> {
                    if (!f.indexStatus.isNullOrEmpty()) staged.add(f)
                    val w = f.worktreeStatus
                    if (!w.isNullOrEmpty() && w != "?") unstaged.add(f)
                }
            }
        }
        return listOf(
            VcsChangeGroup("conflict", VcsSide.CONFLICT, conflicts),
            VcsChangeGroup("staged", VcsSide.STAGED, staged),
            VcsChangeGroup("unstaged", VcsSide.UNSTAGED, unstaged),
            VcsChangeGroup("untracked", VcsSide.UNTRACKED, untracked),
        ).filter { it.files.isNotEmpty() }
    }

    fun title(side: VcsSide): String = when (side) {
        VcsSide.CONFLICT -> tr("vcs.changes.conflicts")
        VcsSide.STAGED -> tr("vcs.changes.staged")
        VcsSide.UNSTAGED -> tr("vcs.changes.unstaged")
        VcsSide.UNTRACKED -> tr("vcs.changes.untracked")
    }

    /** 按列表顺序的全部差异目标（差异页左右切换文件用） */
    fun targets(s: VCSStatus): List<VcsDiffTarget> = build(s).flatMap { g -> g.files.map { VcsActions.workingTarget(it, g.side) } }
}

/**
 * 变更列表（无状态：截图测试直接喂数据）。点按看差异，长按出文件菜单，行尾按钮暂存 / 取消暂存。
 */
@Composable
fun VcsChangesList(
    status: VCSStatus,
    modifier: Modifier = Modifier,
    collapsed: Set<String> = emptySet(),
    selected: VcsDiffTarget? = null,
    onToggle: (String) -> Unit = {},
    onOpen: (VCSFileChange, VcsSide) -> Unit = { _, _ -> },
    onStage: (List<String>) -> Unit = {},
    onUnstage: (List<String>) -> Unit = {},
    onConflicts: () -> Unit = {},
    menu: ((VCSFileChange, VcsSide) -> List<MenuEntry>)? = null,
) {
    val groups = remember(status) { VcsChangeGroups.build(status) }
    val c = PaperTheme.colors
    LazyColumn(modifier.fillMaxSize(), state = rememberLazyListState()) {
        for (g in groups) {
            item(key = "g:" + g.key) {
                val isCollapsed = g.key in collapsed
                VcsGroupHeader(VcsChangeGroups.title(g.side), g.files.size, isCollapsed, onToggle = { onToggle(g.key) }) {
                    when (g.side) {
                        VcsSide.CONFLICT -> PaperButton(tr("vcs.conflicts.resolve").plain(), onClick = onConflicts, kind = ButtonKind.Text, size = ButtonSize.Small)
                        VcsSide.STAGED -> PaperButton(tr("vcs.unstageAll"), onClick = { onUnstage(g.files.map { it.path }) }, kind = ButtonKind.Text, size = ButtonSize.Small, icon = "minus")
                        else -> PaperButton(tr("vcs.stageAll"), onClick = { onStage(g.files.map { it.path }) }, kind = ButtonKind.Text, size = ButtonSize.Small, icon = "plus")
                    }
                }
            }
            if (g.key !in collapsed) {
                items(g.files, key = { "f:" + g.key + ":" + it.path }) { f ->
                    val t = selected
                    val isSel = t != null && t.path == f.path && t.side == g.side
                    ChangeRow(f, g.side, isSel, onOpen, onStage, onUnstage, menu)
                }
                if (g.side == VcsSide.UNTRACKED && status.untrackedTruncated) {
                    item(key = "trunc") {
                        Row(
                            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = PaperTheme.spacing.lg),
                            horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PaperIcon("triangle-alert", size = PaperTheme.sizes.iconSmall, tint = c.warning)
                            Text(tr("vcs.changes.untrackedTruncated", status.untrackedCount), style = PaperTheme.typography.small, color = c.textSecondary, modifier = Modifier.weight(1f, fill = false))
                            PaperInfoButton(tr("vcs.changes.untrackedTruncated.info"))
                        }
                    }
                }
            }
        }
        item { Box(Modifier.height(PaperTheme.spacing.md)) }
    }
}

@Composable
private fun ChangeRow(
    f: VCSFileChange,
    side: VcsSide,
    selected: Boolean,
    onOpen: (VCSFileChange, VcsSide) -> Unit,
    onStage: (List<String>) -> Unit,
    onUnstage: (List<String>) -> Unit,
    menu: ((VCSFileChange, VcsSide) -> List<MenuEntry>)?,
) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val st = when (side) {
        VcsSide.STAGED -> f.indexStatus ?: f.status
        VcsSide.UNSTAGED -> f.worktreeStatus ?: f.status
        VcsSide.CONFLICT -> "U"
        VcsSide.UNTRACKED -> "?"
    }
    PaperLongPressArea(
        onClick = { onOpen(f, side) },
        menu = menu?.let { m -> { m(f, side) } },
        modifier = Modifier.background(if (selected) c.bgSelected else Color.Transparent),
    ) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(start = sp.lg, end = sp.xs, top = sp.xs, bottom = sp.xs),
            horizontalArrangement = Arrangement.spacedBy(sp.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VcsStatusLetter(st)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    VcsPath.name(f.path),
                    style = PaperTheme.typography.body.copy(textDecoration = strike(st == "D")),
                    color = VcsStyle.color(st, c),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val old = f.oldPath?.takeIf { it != f.path }
                val sub = when {
                    side == VcsSide.CONFLICT -> listOf(VcsStyle.conflictTitle(f.conflictType), VcsPath.dir(f.path)).filter { it.isNotEmpty() }.joinToString("  ·  ")
                    old != null -> "← " + if (VcsPath.dir(old) == VcsPath.dir(f.path)) VcsPath.name(old) else old
                    else -> VcsPath.dir(f.path)
                }
                if (sub.isNotEmpty()) {
                    Text(sub, style = PaperTheme.typography.small, color = if (side == VcsSide.CONFLICT) c.danger else c.textTertiary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
            }
            if (f.binary) {
                PaperIcon("binary", size = 14.dp, tint = c.textTertiary)
            } else if (side != VcsSide.UNSTAGED || f.indexStatus.isNullOrEmpty()) {
                // 同一文件部分暂存时两侧都出现：行数只在一侧显示（插件给的是相对 HEAD 的总数）
                VcsLineStats(f.additions, f.deletions)
            }
            when (side) {
                VcsSide.STAGED -> PaperIconButton("minus", tr("vcs.unstage"), onClick = { onUnstage(listOf(f.path)) }, tint = c.iconSecondary)
                VcsSide.UNSTAGED, VcsSide.UNTRACKED -> PaperIconButton("plus", tr("vcs.stage"), onClick = { onStage(listOf(f.path)) }, tint = c.iconSecondary)
                VcsSide.CONFLICT -> PaperIcon("chevron-right", size = 16.dp, tint = c.iconSecondary, modifier = Modifier.padding(horizontal = sp.md))
            }
        }
    }
}

// ---------------------------------------------------------------- 提交区

/** 提交区：提交信息、修改上一次提交、最近信息、选项、提交 / 提交并推送 */
@Composable
fun VcsCommitPanel(repo: VcsRepo, status: VCSStatus?, modifier: Modifier = Modifier) {
    val d = repo.draft
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val scope = rememberCoroutineScope()
    val staged = status?.let { repo.stagedCount(it) } ?: 0
    val fallback = if (status != null && staged == 0 && !d.amend) repo.fallbackPaths(status).size else 0
    val can = repo.canCommit(status)
    fun setAmend(on: Boolean) {
        d.amend = on
        if (!on || d.message.isNotBlank()) return
        scope.launch {
            val det = runCatching { repo.api().vcsShow(repo.projectId, repo.root, "HEAD") }.getOrNull() ?: return@launch
            if (d.amend && d.message.isEmpty()) d.message = VcsSheets.fullMessage(det.message, det.commit.subject, det.body)
        }
    }
    Column(modifier.fillMaxWidth().background(c.bgChrome).padding(horizontal = sp.lg, vertical = sp.md), verticalArrangement = Arrangement.spacedBy(sp.sm)) {
        PaperTextArea(d.message, { d.message = it }, placeholder = tr("vcs.commit.placeholder"), minLines = 2, maxLines = 6)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
            PaperCheckbox(d.amend, { setAmend(it) }, label = tr("vcs.commit.amend"), enabled = status?.unborn != true)
            Box(Modifier.weight(1f))
            if (d.author.isNotEmpty()) PaperBadge(d.author.substringBefore('<').trim().ifEmpty { d.author }, tone = Tone.Info, icon = "user")
            if (d.noVerify) PaperBadge(tr("vcs.commit.noVerify"), tone = Tone.Warning)
            if (d.signOff) PaperBadge(tr("vcs.commit.signOff"), tone = Tone.Neutral)
            PaperIconButton("history", tr("vcs.commit.history"), onClick = { VcsSheets.messageHistory(repo) }, tint = c.iconPrimary)
            PaperMoreButton({ commitOptions(repo) }, contentDescription = tr("vcs.commit.options"), tint = c.iconPrimary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.sm)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                val summary = when {
                    staged > 0 -> tr("vcs.commit.stagedCount", staged)
                    fallback > 0 -> tr("vcs.mobile.commit.allTracked", fallback)
                    else -> ""
                }
                Text(summary, style = PaperTheme.typography.small, color = c.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (staged == 0 && fallback > 0) PaperInfoButton(tr("vcs.mobile.commit.allTrackedInfo"))
            }
            PaperButton(tr("vcs.commitAndPush").plain(), onClick = { repo.commit(andPush = true) }, enabled = can)
            PaperButton(
                if (d.amend) tr("vcs.commit.amendButton") else tr("vcs.commit"),
                onClick = { repo.commit(andPush = false) },
                kind = ButtonKind.Primary,
                enabled = can,
                loading = d.committing,
            )
        }
    }
}

private fun commitOptions(repo: VcsRepo): List<MenuEntry> {
    val d = repo.draft
    return listOf(
        MenuEntry.Item(tr("vcs.commit.signOff"), checked = d.signOff) { d.signOff = !d.signOff },
        MenuEntry.Item(tr("vcs.commit.noVerify"), checked = d.noVerify) { d.noVerify = !d.noVerify },
        MenuEntry.Divider,
        MenuEntry.Item(tr("vcs.commit.author").plain(), "user", checked = d.author.isNotEmpty()) { VcsSheets.author(repo) },
        MenuEntry.Item(tr("vcs.commit.clearAuthor"), enabled = d.author.isNotEmpty()) { d.author = "" },
        MenuEntry.Divider,
        MenuEntry.Item(tr("vcs.commit.undoLast"), "undo-2", enabled = repo.status?.unborn == false) { VcsActions.undoLast(repo) },
    )
}

// ---------------------------------------------------------------- 页面

@Composable
internal fun VcsChangesPage(repo: VcsRepo, wide: Boolean) {
    val s = repo.status
    val err = repo.statusError
    if (s == null) {
        if (err != null) {
            Column(Modifier.padding(PaperTheme.spacing.lg)) {
                PaperAlert(Tone.Danger, tr("vcs.status.failed"), message = err) {
                    PaperButton(tr("connection.retry"), onClick = { AppModel.scope.launch { repo.loadStatus() } }, size = ButtonSize.Small)
                }
            }
        } else {
            PaperLoadingPage()
        }
        return
    }
    val list: @Composable (Modifier) -> Unit = { m ->
        if (s.files.isEmpty()) {
            Box(m.fillMaxWidth(), contentAlignment = Alignment.Center) { PaperEmptyState("circle-check", tr("vcs.changes.clean")) }
        } else {
            VcsChangesList(
                s,
                m,
                collapsed = repo.collapsed,
                selected = if (wide) repo.selectedChange else null,
                onToggle = { k -> repo.collapsed = if (k in repo.collapsed) repo.collapsed - k else repo.collapsed + k },
                onOpen = { f, side ->
                    if (wide) repo.selectedChange = VcsActions.workingTarget(f, side)
                    else VcsActions.openWorkingDiff(repo, f, side, VcsChangeGroups.targets(s))
                },
                onStage = { VcsActions.stage(repo, it) },
                onUnstage = { VcsActions.unstage(repo, it) },
                onConflicts = { repo.session.page = VcsPage.CONFLICTS },
                menu = { f, side -> VcsActions.fileEntries(repo, f, side) },
            )
        }
    }
    // 提交区固定在底部：让出导航栏，键盘弹出时跟着上移（外层已经处理过的插入量不会重复）
    val bottomInsets = Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))
    if (!wide) {
        Column(Modifier.fillMaxSize().then(bottomInsets)) {
            list(Modifier.weight(1f))
            PaperDivider()
            VcsCommitPanel(repo, s)
        }
    } else {
        // 选中的文件已不在变更里（提交、丢弃之后）：清掉右侧
        LaunchedEffect(s) {
            val t = repo.selectedChange ?: return@LaunchedEffect
            if (VcsChangeGroups.targets(s).none { it.path == t.path && it.side == t.side }) repo.selectedChange = null
        }
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(380.dp).fillMaxHeight().then(bottomInsets)) {
                list(Modifier.weight(1f))
                PaperDivider()
                VcsCommitPanel(repo, s)
            }
            PaperDivider(vertical = true)
            VcsDiffPane(repo, repo.selectedChange, Modifier.weight(1f))
        }
    }
}

/** 平板右侧的差异（带状态行，块操作可用） */
@Composable
internal fun VcsDiffPane(repo: VcsRepo, target: VcsDiffTarget?, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val state = remember(repo) { VcsDiffState(repo, scope) }
    LaunchedEffect(target, repo.revision, repo.status) {
        if (state.target != target) state.set(target) else if (target?.isWorking == true) state.reload()
    }
    val listState = rememberLazyListState()
    Column(modifier.fillMaxHeight()) {
        val t = state.target
        if (t == null) {
            PaperEmptyState("file-diff", tr("vcs.diff.none"), Modifier.fillMaxSize())
            return@Column
        }
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = PaperTheme.spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Text(t.path, style = PaperTheme.typography.bodyStrong, color = PaperTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        val layout = state.layout
        VcsDiffInfoRow(t, state.diff, layout, -1, state.loading, onJump = { delta ->
            val starts = layout?.changeStarts.orEmpty()
            if (starts.isNotEmpty()) {
                val first = listState.firstVisibleItemIndex
                val target = if (delta > 0) starts.firstOrNull { it > first } ?: starts.last() else starts.lastOrNull { it < first } ?: starts.first()
                scope.launch { listState.animateScrollToItem(target) }
            }
        })
        VcsDiffBody(
            state.diff, layout, Modifier.weight(1f),
            path = t.path, loading = state.loading, error = state.error, wrap = VcsPrefs.diffWrap,
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
