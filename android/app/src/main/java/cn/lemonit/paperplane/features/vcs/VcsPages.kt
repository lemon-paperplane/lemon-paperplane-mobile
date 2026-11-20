package cn.lemonit.paperplane.features.vcs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.kit.model.GoTime
import cn.lemonit.paperplane.kit.model.VCSBranch
import cn.lemonit.paperplane.kit.model.VCSBranches
import cn.lemonit.paperplane.kit.model.VCSFileChange
import cn.lemonit.paperplane.kit.model.VCSStash
import cn.lemonit.paperplane.kit.model.VCSStashShow
import cn.lemonit.paperplane.kit.model.VCSStatus
import cn.lemonit.paperplane.kit.model.VCSTag
import cn.lemonit.paperplane.kit.vcsStashShow
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
import cn.lemonit.paperplane.ui.components.PaperSearchField
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException

// 分支页（本地 / 各远程 / 标签）、储藏页（列表 + 详情）、冲突页。对照 Mac VCSBranches / VCSStashView / VCSConflictsView。

// ---------------------------------------------------------------- 分支

@Composable
internal fun VcsBranchesPage(repo: VcsRepo) {
    LaunchedEffect(repo.revision) {
        repo.loadBranches()
        repo.loadTags()
    }
    val b = repo.branches
    if (b == null) {
        PaperLoadingPage()
        return
    }
    VcsBranchesList(
        b, repo.tags,
        onNewBranch = { VcsSheets.newBranch(repo, null, null) },
        onCheckoutRef = { VcsActions.checkoutRef(repo) },
        onFetch = { VcsActions.fetch(repo) },
        branchMenu = { br, remote -> VcsActions.branchEntries(repo, br, remote) },
        tagMenu = { VcsActions.tagEntries(repo, it) },
    )
}

/** 分支列表（无状态）：搜索、新建 / 签出修订 / 获取、本地 / 各远程 / 标签分组（可折叠），点按出操作菜单 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VcsBranchesList(
    b: VCSBranches,
    tags: List<VCSTag>,
    modifier: Modifier = Modifier,
    onNewBranch: () -> Unit = {},
    onCheckoutRef: () -> Unit = {},
    onFetch: () -> Unit = {},
    branchMenu: ((VCSBranch, Boolean) -> List<MenuEntry>)? = null,
    tagMenu: ((VCSTag) -> List<MenuEntry>)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var collapsed by rememberSaveable { mutableStateOf(listOf<String>()) }
    val sp = PaperTheme.spacing
    fun match(n: String) = query.isBlank() || n.contains(query.trim(), ignoreCase = true)
    fun toggle(k: String) {
        collapsed = if (k in collapsed) collapsed - k else collapsed + k
    }
    Column(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = sp.lg, vertical = sp.sm), verticalArrangement = Arrangement.spacedBy(sp.sm)) {
            PaperSearchField(query, { query = it }, placeholder = tr("vcs.branch.search"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                PaperButton(tr("vcs.branch.new").plain(), onClick = onNewBranch, size = ButtonSize.Small, icon = "plus")
                PaperButton(tr("vcs.checkoutRef").plain(), onClick = onCheckoutRef, size = ButtonSize.Small, icon = "circle-dot")
                PaperButton(tr("vcs.fetch"), onClick = onFetch, size = ButtonSize.Small, icon = "cloud-download")
            }
        }
        PaperDivider(subtle = true)
        val byRemote = b.remote.groupBy { it.remote ?: it.name.substringBefore('/') }.toSortedMap()
        LazyColumn(Modifier.fillMaxSize()) {
            val local = b.local.filter { match(it.name) }
            item(key = "h:local") { VcsGroupHeader(tr("vcs.branch.local"), b.local.size, "local" in collapsed, { toggle("local") }) }
            if ("local" !in collapsed) items(local, key = { "l:" + it.name }) { br -> BranchRow(br, false, null, branchMenu) }
            for ((rn, list) in byRemote) {
                val key = "remote:$rn"
                item(key = "h:$key") { VcsGroupHeader(tr("vcs.branch.remoteNamed", rn), list.size, key in collapsed, { toggle(key) }) }
                if (key !in collapsed) items(list.filter { match(it.name) }, key = { "r:" + it.name }) { br -> BranchRow(br, true, rn, branchMenu) }
            }
            item(key = "h:tags") { VcsGroupHeader(tr("vcs.branch.tags"), tags.size, "tags" in collapsed, { toggle("tags") }) }
            if ("tags" !in collapsed) items(tags.filter { match(it.name) }, key = { "t:" + it.name }) { t -> TagRow(t, tagMenu) }
            item { Box(Modifier.height(sp.huge)) }
        }
    }
}

@Composable
private fun BranchRow(br: VCSBranch, remote: Boolean, remoteName: String?, menu: ((VCSBranch, Boolean) -> List<MenuEntry>)?) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    PaperLongPressArea(onClick = null, menu = menu?.let { m -> { m(br, remote) } }) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(horizontal = sp.lg, vertical = sp.sm),
            horizontalArrangement = Arrangement.spacedBy(sp.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaperIcon(
                if (br.current) "check" else if (remote) "cloud" else "git-branch",
                size = PaperTheme.sizes.iconSmall,
                tint = if (br.current) c.accent else c.iconSecondary,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (remoteName != null) br.name.removePrefix("$remoteName/") else br.name,
                        style = if (br.current) PaperTheme.typography.bodyStrong else PaperTheme.typography.body,
                        color = c.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (br.gone) PaperBadge(tr("vcs.branch.gone"), tone = Tone.Warning)
                    if (br.worktree != null) PaperBadge(tr("vcs.branch.worktree"), icon = "git-fork")
                }
                val sub = listOfNotNull(br.upstream?.let { "→ $it" }, br.subject).joinToString("  ·  ")
                if (sub.isNotEmpty()) Text(sub, style = PaperTheme.typography.small, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(sp.xs)) { VcsAheadBehind(br.ahead, br.behind) }
                Text(VcsDate.short(br.parsedDate), style = PaperTheme.typography.caption, color = c.textTertiary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TagRow(t: VCSTag, menu: ((VCSTag) -> List<MenuEntry>)?) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    PaperLongPressArea(onClick = null, menu = menu?.let { m -> { m(t) } }) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(horizontal = sp.lg, vertical = sp.sm),
            horizontalArrangement = Arrangement.spacedBy(sp.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaperIcon("tag", size = PaperTheme.sizes.iconSmall, tint = c.warning)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(t.name, style = PaperTheme.typography.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                val sub = listOfNotNull(t.hash.take(7).ifEmpty { null }, (if (t.annotated) t.message else null) ?: t.subject).joinToString("  ")
                if (sub.isNotEmpty()) Text(sub, style = PaperTheme.typography.small, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(VcsDate.short(GoTime.parse(t.date)), style = PaperTheme.typography.caption, color = c.textTertiary, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- 储藏

@Composable
internal fun VcsStashPage(repo: VcsRepo, wide: Boolean) {
    LaunchedEffect(repo.revision) { repo.loadStashes() }
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val list: @Composable (Modifier) -> Unit = { m ->
        Column(m.fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(start = sp.lg, end = sp.xs, top = sp.sm, bottom = sp.sm), verticalAlignment = Alignment.CenterVertically) {
                PaperButton(tr("vcs.stash.new").plain(), onClick = { VcsSheets.stashNew(repo, null) }, size = ButtonSize.Small, icon = "archive", enabled = repo.status?.files?.isNotEmpty() == true)
                Box(Modifier.weight(1f))
                PaperIconButton("trash-2", tr("vcs.stash.clear"), onClick = { VcsActions.stashClear(repo) }, enabled = repo.stashes.isNotEmpty(), tint = c.iconSecondary)
            }
            PaperDivider(subtle = true)
            VcsStashList(
                repo.stashes,
                Modifier.weight(1f),
                selected = if (wide) repo.selectedStash else null,
                onClick = { s -> if (wide) repo.selectedStash = s.index else AppModel.nav.push(VcsStashRoute(repo.projectId, repo.root, s.index)) },
                menu = { VcsActions.stashEntries(repo, it) },
            )
        }
    }
    if (!wide) {
        list(Modifier.fillMaxSize())
    } else {
        Row(Modifier.fillMaxSize()) {
            list(Modifier.width(380.dp))
            PaperDivider(vertical = true)
            val s = repo.stashes.firstOrNull { it.index == repo.selectedStash }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (s == null) PaperEmptyState("archive", tr("vcs.stash.select"), Modifier.align(Alignment.Center))
                else androidx.compose.runtime.key(s.index, s.hash) { VcsStashDetail(repo, s) }
            }
        }
    }
}

/** 储藏列表（无状态） */
@Composable
fun VcsStashList(
    stashes: List<VCSStash>,
    modifier: Modifier = Modifier,
    selected: Int? = null,
    onClick: (VCSStash) -> Unit = {},
    menu: ((VCSStash) -> List<MenuEntry>)? = null,
) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    if (stashes.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PaperEmptyState("archive", tr("vcs.stash.empty")) }
        return
    }
    LazyColumn(modifier.fillMaxWidth()) {
        items(stashes, key = { it.index }) { s ->
            PaperLongPressArea(onClick = { onClick(s) }, menu = menu?.let { m -> { m(s) } }, modifier = Modifier.background(if (s.index == selected) c.bgSelected else Color.Transparent)) {
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(horizontal = sp.lg, vertical = sp.sm),
                    horizontalArrangement = Arrangement.spacedBy(sp.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PaperIcon("archive", size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(s.message.ifEmpty { "stash@{${s.index}}" }, style = PaperTheme.typography.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull("stash@{${s.index}}", s.branch, VcsDate.short(s.parsedDate).ifEmpty { null }).joinToString("  ·  "),
                            style = PaperTheme.typography.small,
                            color = c.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    PaperIcon("chevron-right", size = 16.dp, tint = c.iconSecondary)
                }
            }
        }
    }
}

/** 储藏详情：说明、分支、时间，应用 / 弹出 / 恢复为分支 / 删除，文件列表（点开看差异） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VcsStashDetail(repo: VcsRepo, s: VCSStash, onGone: () -> Unit = {}) {
    var show by remember { mutableStateOf<VCSStashShow?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var restoreIndex by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(s.index, s.hash) {
        try {
            show = repo.api().vcsStashShow(repo.projectId, repo.root, s.index)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: tr("common.failed")
        }
    }
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(sp.lg), verticalArrangement = Arrangement.spacedBy(sp.md)) {
            SelectionContainer { Text(s.message.ifEmpty { "stash@{${s.index}}" }, style = PaperTheme.typography.heading, color = c.textPrimary) }
            Row(horizontalArrangement = Arrangement.spacedBy(sp.md), verticalAlignment = Alignment.CenterVertically) {
                Text("stash@{${s.index}}", style = PaperTheme.typography.monoSmall, color = c.textTertiary)
                val br = s.branch
                if (br != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalAlignment = Alignment.CenterVertically) {
                        PaperIcon("git-branch", size = 14.dp, tint = c.iconSecondary)
                        Text(br, style = PaperTheme.typography.small, color = c.textSecondary, maxLines = 1)
                    }
                }
                Text(VcsDate.full(s.parsedDate), style = PaperTheme.typography.small, color = c.textSecondary)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                PaperButton(tr("vcs.stash.apply"), onClick = { VcsActions.stashApply(repo, s, pop = false, restoreIndex = restoreIndex) }, kind = ButtonKind.Primary, size = ButtonSize.Small, icon = "archive-restore")
                PaperButton(tr("vcs.stash.pop"), onClick = { VcsActions.stashApply(repo, s, pop = true, restoreIndex = restoreIndex, then = onGone) }, size = ButtonSize.Small, icon = "arrow-up-from-line")
                PaperButton(tr("vcs.stash.asBranch").plain(), onClick = { VcsActions.stashAsBranch(repo, s, onGone) }, size = ButtonSize.Small, icon = "git-branch")
                PaperButton(tr("vcs.stash.drop"), onClick = { VcsActions.stashDrop(repo, s, onGone) }, kind = ButtonKind.Text, size = ButtonSize.Small, icon = "trash-2")
            }
            PaperCheckbox(restoreIndex, { restoreIndex = it }, label = tr("vcs.stash.restoreIndex"))
        }
        PaperDivider(subtle = true)
        val e = error
        val sh = show
        when {
            e != null -> PaperAlert(Tone.Danger, e, Modifier.padding(sp.lg))
            sh == null -> Box(Modifier.fillMaxWidth().height(120.dp)) { PaperLoadingPage() }
            else -> {
                Text(tr("vcs.log.files", sh.files.size), style = PaperTheme.typography.label, color = c.textSecondary, modifier = Modifier.padding(horizontal = sp.lg, vertical = sp.md))
                for (f in sh.files) {
                    VcsFileLine(f, onClick = {
                        val targets = sh.files.map { VcsDiffTarget(it.path, it.oldPath, VcsDiffKind.Commit(sh.hash, 1), it.status) }
                        VcsActions.openDiff(repo, targets, sh.files.indexOf(f), "stash@{${s.index}}  ${s.message}")
                    })
                }
                if (sh.untracked.isNotEmpty()) {
                    Text(tr("vcs.stash.untracked", sh.untracked.size), style = PaperTheme.typography.label, color = c.textSecondary, modifier = Modifier.padding(horizontal = sp.lg, vertical = sp.md))
                    for (p in sh.untracked) {
                        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = sp.lg), horizontalArrangement = Arrangement.spacedBy(sp.md), verticalAlignment = Alignment.CenterVertically) {
                            VcsStatusLetter("?")
                            Text(p, style = PaperTheme.typography.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        }
                    }
                }
            }
        }
        Box(Modifier.height(sp.huge))
    }
}

// ---------------------------------------------------------------- 冲突

@Composable
internal fun VcsConflictsPage(repo: VcsRepo) {
    val st = repo.status ?: VCSStatus()
    VcsConflictsList(
        st,
        onOpen = { f -> VcsActions.openWorkingDiff(repo, f, VcsSide.CONFLICT, st.files.filter { it.conflicted }.map { VcsActions.workingTarget(it, VcsSide.CONFLICT) }) },
        onResolve = { paths, side -> VcsActions.resolve(repo, paths, side) },
        onContinue = { VcsActions.continueOp(repo) },
        menu = { f -> conflictEntries(repo, f) },
    )
}

internal fun conflictEntries(repo: VcsRepo, f: VCSFileChange): List<MenuEntry> = listOf(
    MenuEntry.Item(tr("vcs.conflicts.useOurs"), "arrow-left") { VcsActions.resolve(repo, listOf(f.path), "ours") },
    MenuEntry.Item(tr("vcs.conflicts.useTheirs"), "arrow-right") { VcsActions.resolve(repo, listOf(f.path), "theirs") },
    MenuEntry.Item(tr("vcs.mobile.openInEditor"), "file-pen", enabled = f.conflictType != "DD") { VcsActions.openInEditor(repo, f.path) },
    MenuEntry.Item(tr("vcs.conflicts.markResolved"), "check") { VcsActions.resolve(repo, listOf(f.path), "resolved") },
    MenuEntry.Divider,
    MenuEntry.Item(tr("vcs.showDiff"), "file-diff") { VcsActions.openWorkingDiff(repo, f, VcsSide.CONFLICT) },
    MenuEntry.Item(tr("vcs.copyPath"), "copy") { cn.lemonit.paperplane.core.Actions.copy(f.path) },
)

/** 冲突列表（无状态）：我方 / 对方是谁、全部用我的 / 他们的、逐个处理；全部解决后「继续」 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VcsConflictsList(
    st: VCSStatus,
    modifier: Modifier = Modifier,
    onOpen: (VCSFileChange) -> Unit = {},
    onResolve: (List<String>, String) -> Unit = { _, _ -> },
    onContinue: () -> Unit = {},
    menu: ((VCSFileChange) -> List<MenuEntry>)? = null,
) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val files = st.files.filter { it.conflicted }
    val (ours, theirs) = VcsActions.sides(st)
    Column(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(sp.lg), verticalArrangement = Arrangement.spacedBy(sp.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("vcs.conflicts.title", files.size), style = PaperTheme.typography.title, color = c.textPrimary)
                PaperInfoButton(tr("vcs.conflicts.info"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(sp.lg)) {
                Row(horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
                    Text(tr("vcs.conflicts.ours"), style = PaperTheme.typography.small, color = c.textTertiary)
                    Text(ours, style = PaperTheme.typography.monoSmall, color = c.textPrimary, maxLines = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
                    Text(tr("vcs.conflicts.theirs"), style = PaperTheme.typography.small, color = c.textTertiary)
                    Text(theirs, style = PaperTheme.typography.monoSmall, color = c.textPrimary, maxLines = 1)
                }
            }
            if (files.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                    PaperButton(tr("vcs.conflicts.allOurs"), onClick = { onResolve(files.map { it.path }, "ours") }, size = ButtonSize.Small)
                    PaperButton(tr("vcs.conflicts.allTheirs"), onClick = { onResolve(files.map { it.path }, "theirs") }, size = ButtonSize.Small)
                }
            }
        }
        PaperDivider(subtle = true)
        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (st.operation != null) {
                    PaperEmptyState("circle-check", tr("vcs.conflicts.allResolved"), actionText = tr("vcs.op.continue"), actionIcon = "play", onAction = onContinue)
                } else {
                    PaperEmptyState("circle-check", tr("vcs.conflicts.none"))
                }
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(files, key = { it.path }) { f ->
                PaperLongPressArea(onClick = { onOpen(f) }, menu = menu?.let { m -> { m(f) } }) {
                    Row(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(start = sp.lg, end = sp.xs, top = sp.xs, bottom = sp.xs),
                        horizontalArrangement = Arrangement.spacedBy(sp.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PaperIcon("triangle-alert", size = PaperTheme.sizes.iconSmall, tint = c.danger)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(VcsPath.name(f.path), style = PaperTheme.typography.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(VcsStyle.conflictTitle(f.conflictType), VcsPath.dir(f.path)).filter { it.isNotEmpty() }.joinToString("  ·  "),
                                style = PaperTheme.typography.small,
                                color = c.textTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                        }
                        if (menu != null) PaperMoreButton({ menu(f) })
                    }
                }
            }
        }
    }
}
