package cn.lemonit.paperplane.features.vcs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.PluginsRoute
import cn.lemonit.paperplane.kit.model.ActionLocation
import cn.lemonit.paperplane.kit.model.VCSOp
import cn.lemonit.paperplane.kit.model.VCSStatus
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperLoadingPage
import cn.lemonit.paperplane.ui.components.PaperMoreButton
import cn.lemonit.paperplane.ui.components.PaperSearchField
import cn.lemonit.paperplane.ui.components.PaperSegmented
import cn.lemonit.paperplane.ui.components.PaperSelect
import cn.lemonit.paperplane.ui.components.PaperTextField
import cn.lemonit.paperplane.ui.components.SelectOption
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.shell.LocalWideLayout
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.launch

// 工程详情的「版本控制」标签（对照 Mac VCSTabView.swift）：仓库切换、分支按钮与获取 / 拉取 / 推送、
// 进行中操作横幅、失败提示、子页（提交 / 日志 / 分支 / 储藏 / 冲突）。

/** 工程详情的「版本控制」标签（由工程详情页调用） */
@Composable
fun ProjectVcsTab(projectId: String) {
    val data = AppModel.data
    if (data.loaded && data.contributions.vcs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PaperEmptyState("puzzle", tr("vcs.mobile.noPlugin"), actionText = tr("sidebar.plugins"), actionIcon = "puzzle") { AppModel.nav.push(PluginsRoute) }
        }
        return
    }
    val session = remember(projectId, AppModel.currentServerId) { VcsStore.session(projectId) }
    LaunchedEffect(session) {
        if (!session.rootsLoaded || session.roots.isEmpty()) session.loadRoots()
    }
    DisposableEffect(session) {
        session.visible = true
        onDispose { session.visible = false }
    }
    VcsSessionView(session)
}

/** 会话视图（截图测试可直接给预览会话） */
@Composable
fun VcsSessionView(session: VcsSession) {
    val repo = session.repo
    when {
        !session.rootsLoaded -> PaperLoadingPage()
        session.roots.isEmpty() -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            PaperEmptyState("folder-git", tr("vcs.notRepo"), actionText = tr("vcs.init"), actionIcon = "plus") { session.initRepository() }
            session.rootsError?.let { PaperAlert(Tone.Danger, it, Modifier.padding(PaperTheme.spacing.xl)) }
        }
        repo != null -> androidx.compose.runtime.key(repo.root) { VcsRepoView(session, repo) }
    }
}

@Composable
private fun VcsRepoView(session: VcsSession, repo: VcsRepo) {
    val wide = LocalWideLayout.current
    val s = repo.status
    LaunchedEffect(repo) {
        repo.refresh()
        if (!repo.remotesLoaded) repo.loadRemotes()
        if (repo.stashes.isEmpty()) repo.loadStashes()
    }
    // 冲突解决完后冲突页消失：回到提交页
    LaunchedEffect(s?.conflicts, s?.operation) {
        if (s != null && s.conflicts == 0 && s.operation == null && session.page == VcsPage.CONFLICTS) session.page = VcsPage.CHANGES
    }
    Column(Modifier.fillMaxSize().background(PaperTheme.colors.bgContent)) {
        VcsRepoHeader(session, repo)
        val op = s?.operation
        if (s != null && op != null) {
            VcsOperationBanner(repo, s, Modifier.padding(horizontal = PaperTheme.spacing.lg, vertical = PaperTheme.spacing.xs))
        }
        VcsRepoMessages(repo)
        VcsPageTabs(session, s)
        PaperDivider(subtle = true)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (session.page) {
                VcsPage.CHANGES -> VcsChangesPage(repo, wide)
                VcsPage.LOG -> VcsLogPage(repo, wide)
                VcsPage.BRANCHES -> VcsBranchesPage(repo)
                VcsPage.STASHES -> VcsStashPage(repo, wide)
                VcsPage.CONFLICTS -> VcsConflictsPage(repo)
            }
        }
    }
}

// ---------------------------------------------------------------- 标题行

@Composable
internal fun VcsRepoHeader(session: VcsSession, repo: VcsRepo) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val busy = repo.activity
    Column(Modifier.fillMaxWidth().background(c.bgChrome)) {
        if (session.roots.size > 1) {
            PaperSelect(
                options = session.roots.map { r ->
                    SelectOption(r.path, if (r.relPath == "." || r.relPath.isEmpty()) VcsPath.name(r.path) else r.relPath, "folder-git")
                },
                selected = session.selectedRoot,
                onSelect = { session.select(it) },
                title = tr("vcs.repository"),
                size = ButtonSize.Small,
                modifier = Modifier.fillMaxWidth().padding(start = sp.lg, end = sp.lg, top = sp.sm),
            )
        }
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = sp.sm, end = sp.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { VcsBranchChip(repo) }
            PaperIconButton("cloud-download", tr("vcs.fetch"), onClick = { VcsActions.fetch(repo) }, loading = busy?.op == VCSOp.FETCH, enabled = busy == null)
            PaperIconButton("arrow-down-to-line", tr("vcs.pull.ellipsis").plain(), onClick = { VcsSheets.pull(repo) }, loading = busy?.op == VCSOp.PULL, enabled = busy == null)
            PaperIconButton("arrow-up", tr("vcs.push.ellipsis").plain(), onClick = { VcsSheets.push(repo) }, loading = busy?.op == VCSOp.PUSH, enabled = busy == null)
            PaperMoreButton({ moreEntries(session, repo) }, tint = c.iconPrimary)
        }
    }
}

/** 分支按钮：当前分支、领先 / 落后，点开快速切换 */
@Composable
internal fun VcsBranchChip(repo: VcsRepo, modifier: Modifier = Modifier) {
    val c = PaperTheme.colors
    val s = repo.status
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(PaperTheme.radius.md)
    Row(
        modifier
            .height(44.dp)
            .background(if (pressed) c.bgPressed else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { VcsSheets.branchPicker(repo) }
            .padding(horizontal = PaperTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaperIcon(if (s?.detached == true) "circle-dot" else "git-branch", tint = c.iconPrimary)
        Text(
            repo.headTitle.ifEmpty { "…" },
            style = PaperTheme.typography.bodyStrong,
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (s != null) {
            VcsAheadBehind(s.ahead, s.behind)
            if (s.upstreamGone) PaperIcon("triangle-alert", size = 14.dp, tint = c.warning, contentDescription = tr("vcs.branch.upstreamGone"))
        }
        PaperIcon("chevron-down", size = 14.dp, tint = c.iconSecondary)
    }
}

private fun moreEntries(session: VcsSession, repo: VcsRepo): List<MenuEntry> = buildList {
    add(MenuEntry.Item(tr("vcs.refresh"), "refresh-cw") {
        AppModel.scope.launch {
            session.loadRoots()
            repo.invalidate()
        }
    })
    add(MenuEntry.Divider)
    add(MenuEntry.Item(tr("vcs.stash.new").plain(), "archive", enabled = repo.status?.files?.isNotEmpty() == true) { VcsSheets.stashNew(repo, null) })
    add(MenuEntry.Item(tr("vcs.branch.new").plain(), "git-branch") { VcsSheets.newBranch(repo, null, null) })
    add(MenuEntry.Item(tr("vcs.checkoutRef").plain(), "circle-dot") { VcsActions.checkoutRef(repo) })
    add(MenuEntry.Item(tr("vcs.mobile.remotes"), "server") { AppModel.nav.push(VcsRemotesRoute(repo.projectId, repo.root)) })
    add(MenuEntry.Divider)
    add(MenuEntry.Item(tr("vcs.copyRepoPath"), "copy") { Actions.copy(repo.root) })
    add(MenuEntry.Item(tr("vcs.clone.ellipsis").plain(), "download") { VcsCloneFlow.start() })
    val plugin = VcsActions.pluginEntries(ActionLocation.VCS_TOOLBAR, repo, emptyMap())
    if (plugin.isNotEmpty()) {
        add(MenuEntry.Divider)
        addAll(plugin)
    }
}

/** 进行中的合并 / 变基 / 拣选：说明 + 去解决冲突 / 继续 / 跳过 / 中止 */
@Composable
internal fun VcsOperationBanner(repo: VcsRepo, s: VCSStatus, modifier: Modifier = Modifier) {
    val op = s.operation.orEmpty()
    val canSkip = op == "rebase" || op == "cherry-pick" || op == "revert" || op == "am"
    val d = s.operationDetail
    val title = when (op) {
        "rebase" -> {
            var t = tr("vcs.banner.rebase", d?.headName ?: s.branch, VcsStyle.shortHash(d?.onto))
            val step = d?.step
            val total = d?.total
            if (step != null && total != null && total > 0) t += "  " + tr("vcs.banner.step", step, total)
            t
        }
        "merge" -> tr("vcs.banner.merge", s.branch.ifEmpty { VcsStyle.shortHash(s.headHash) })
        "cherry-pick" -> tr("vcs.banner.cherryPick", VcsStyle.shortHash(d?.current))
        "revert" -> tr("vcs.banner.revert", VcsStyle.shortHash(d?.current))
        "am" -> tr("vcs.banner.am")
        else -> VcsStyle.operationTitle(op)
    }
    val detail = d?.message?.takeIf { it.isNotEmpty() }
        ?: if (op == "rebase" && !d?.current.isNullOrEmpty()) tr("vcs.banner.applying", VcsStyle.shortHash(d?.current)) else null
    val running = repo.activity?.op
    VcsToneBox(if (s.conflicts > 0) Tone.Warning else Tone.Info, title, modifier, message = detail, actions = {
        if (s.conflicts > 0) {
            PaperButton(tr("vcs.banner.conflicts", s.conflicts), onClick = { repo.session.page = VcsPage.CONFLICTS }, kind = ButtonKind.Primary, size = ButtonSize.Small, icon = "triangle-alert")
        }
        PaperButton(
            tr("vcs.op.continue"),
            onClick = { VcsActions.continueOp(repo) },
            kind = if (s.conflicts > 0) ButtonKind.Secondary else ButtonKind.Primary,
            size = ButtonSize.Small,
            enabled = s.conflicts == 0,
            loading = running == VCSOp.CONTINUE,
        )
        if (canSkip) PaperButton(tr("vcs.op.skip"), onClick = { VcsActions.skip(repo) }, size = ButtonSize.Small, loading = running == VCSOp.SKIP)
        PaperButton(tr("vcs.op.abort"), onClick = { VcsActions.abort(repo) }, kind = ButtonKind.Text, size = ButtonSize.Small)
    })
}

/** 子页切换 */
@Composable
internal fun VcsPageTabs(session: VcsSession, s: VCSStatus?) {
    val changes = s?.files?.size ?: 0
    val stashes = s?.stashCount ?: 0
    val showConflicts = (s?.conflicts ?: 0) > 0 || s?.operation != null || session.page == VcsPage.CONFLICTS
    fun withCount(t: String, n: Int) = if (n > 0) "$t $n" else t
    val options = buildList {
        add(VcsPage.CHANGES to withCount(tr("vcs.page.changes"), changes))
        add(VcsPage.LOG to tr("vcs.page.log"))
        add(VcsPage.BRANCHES to tr("vcs.page.branches"))
        add(VcsPage.STASHES to withCount(tr("vcs.page.stashes"), stashes))
        if (showConflicts) add(VcsPage.CONFLICTS to withCount(tr("vcs.page.conflicts"), s?.conflicts ?: 0))
    }
    // 放得下时等宽撑满；放不下（英文、出现冲突页时）改成按文字宽度、可横向滑动，不截断文字
    val measurer = rememberTextMeasurer()
    val style = PaperTheme.typography.label
    val density = LocalDensity.current
    val pad = PaperTheme.spacing.lg
    BoxWithConstraints(Modifier.fillMaxWidth().background(PaperTheme.colors.bgChrome).padding(horizontal = PaperTheme.spacing.lg, vertical = PaperTheme.spacing.sm)) {
        val widest = options.maxOf { (_, t) -> with(density) { measurer.measure(t, style).size.width.toDp() } }
        val fits = (widest + pad * 2 + 2.dp) * options.size + 4.dp <= maxWidth
        if (fits) {
            PaperSegmented(options, session.page, { session.page = it }, fill = true, modifier = Modifier.fillMaxWidth())
        } else {
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                PaperSegmented(options, session.page, { session.page = it })
            }
        }
    }
}

// ---------------------------------------------------------------- 日志页

@Composable
internal fun VcsLogPage(repo: VcsRepo, wide: Boolean) {
    val log = repo.log
    var showFilters by rememberSaveable { mutableStateOf(log.filtered) }
    LaunchedEffect(repo.revision) { log.ensureLoaded() }
    LaunchedEffect(Unit) { if (repo.branches == null) repo.loadBranches() }
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val listState = rememberLazyListState()
    val list: @Composable (Modifier) -> Unit = { m ->
        Column(m.fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(start = sp.lg, end = sp.xs, top = sp.sm, bottom = sp.sm), verticalAlignment = Alignment.CenterVertically) {
                val b = repo.branches
                val options = buildList {
                    add(SelectOption("--all", tr("vcs.log.allBranches"), "git-fork"))
                    add(SelectOption("", tr("vcs.log.currentBranch"), "circle-dot"))
                    b?.local?.forEach { add(SelectOption(it.name, it.name, "git-branch")) }
                    b?.remote?.forEach { add(SelectOption(it.name, it.name, "cloud")) }
                    if (none { it.value == log.branch }) add(SelectOption(log.branch, log.branch))
                }
                PaperSelect(options, log.branch, { v ->
                    log.branch = v
                    if (v == "--all" || v.isEmpty()) VcsPrefs.logAllBranches = v == "--all"
                    log.reload()
                }, title = tr("vcs.page.branches"), size = ButtonSize.Small, modifier = Modifier.weight(1f))
                Text(
                    tr("vcs.log.count", log.commits.size) + if (log.hasMore) "+" else "",
                    style = PaperTheme.typography.small,
                    color = c.textTertiary,
                    modifier = Modifier.padding(horizontal = sp.sm),
                )
                PaperIconButton("search", tr("common.filter"), onClick = { showFilters = !showFilters }, active = showFilters || log.filtered)
            }
            if (showFilters) {
                Column(Modifier.padding(start = sp.lg, end = sp.lg, bottom = sp.sm), verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                    PaperSearchField(log.text, {
                        log.text = it
                        log.filtersChanged()
                    }, placeholder = tr("vcs.log.searchText"))
                    Row(horizontalArrangement = Arrangement.spacedBy(sp.sm)) {
                        PaperTextField(log.author, {
                            log.author = it
                            log.filtersChanged()
                        }, placeholder = tr("vcs.log.author"), leadingIcon = "user", clearable = true, modifier = Modifier.weight(1f))
                        PaperTextField(log.path, {
                            log.path = it
                            log.filtersChanged()
                        }, placeholder = tr("vcs.log.path"), leadingIcon = "folder", clearable = true, mono = true, modifier = Modifier.weight(1f))
                    }
                }
            }
            PaperDivider(subtle = true)
            if (log.commits.isEmpty()) {
                LogPlaceholder(log)
            } else {
                VcsLogList(
                    log.commits,
                    log.hasGraph,
                    Modifier.weight(1f),
                    selected = if (wide) log.selected else null,
                    loadingMore = log.loadingMore,
                    listState = listState,
                    onClick = { cm -> if (wide) log.selected = cm.hash else VcsActions.openCommit(repo, cm.hash) },
                    menu = { cm -> VcsActions.commitEntries(repo, cm) },
                    onNearEnd = { log.loadMore() },
                )
            }
        }
    }
    if (!wide) {
        list(Modifier.fillMaxSize())
    } else {
        Row(Modifier.fillMaxSize()) {
            list(Modifier.weight(1f))
            PaperDivider(vertical = true)
            Box(Modifier.width(420.dp).fillMaxHeight()) {
                val h = log.selected
                if (h == null) {
                    PaperEmptyState("git-commit-horizontal", tr("vcs.log.selectCommit"), Modifier.align(Alignment.Center))
                } else {
                    androidx.compose.runtime.key(h) { VcsCommitDetailView(repo, h, onOpenCommit = { log.selected = it }) }
                }
            }
        }
    }
}
