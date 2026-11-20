package cn.lemonit.paperplane.features.vcs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.kit.graph.CommitGraphGeometry
import cn.lemonit.paperplane.kit.model.VCSCommit
import cn.lemonit.paperplane.kit.model.VCSCommitDetail
import cn.lemonit.paperplane.kit.model.VCSContainingBranches
import cn.lemonit.paperplane.kit.model.VCSFileChange
import cn.lemonit.paperplane.kit.model.VCSGraphRow
import cn.lemonit.paperplane.kit.model.VCSLogArgs
import cn.lemonit.paperplane.kit.model.GoTime
import cn.lemonit.paperplane.kit.vcsContainingBranches
import cn.lemonit.paperplane.kit.vcsLog
import cn.lemonit.paperplane.kit.vcsShow
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperLink
import cn.lemonit.paperplane.ui.components.PaperLoadingPage
import cn.lemonit.paperplane.ui.components.PaperLongPressArea
import cn.lemonit.paperplane.ui.components.PaperSegmented
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// 日志：提交图、引用徽标、按分支 / 关键词 / 作者 / 路径过滤、分页；提交详情（信息、作者、哈希、父提交、包含于、文件）。
// 对照 Mac VCSLogView.swift。

/** 日志的加载状态（每个仓库一份，放在 VcsRepo 里，进出提交详情时不丢） */
class VcsLogState(val repo: VcsRepo) {
    var commits by mutableStateOf<List<VCSCommit>>(emptyList())
        private set
    var hasMore by mutableStateOf(false)
        private set
    var hasGraph by mutableStateOf(true)
        private set
    var loading by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** "--all" 全部分支、"" 当前分支、其它为分支名 */
    var branch by mutableStateOf(if (VcsPrefs.logAllBranches) "--all" else "")
    var text by mutableStateOf("")
    var author by mutableStateOf("")
    var path by mutableStateOf("")

    /** 平板双栏：右侧显示的提交 */
    var selected by mutableStateOf<String?>(null)

    private var loadedRevision = -1
    private var job: Job? = null

    val filtered: Boolean get() = text.isNotBlank() || author.isNotBlank() || path.isNotBlank()

    internal fun setPreview(list: List<VCSCommit>) {
        commits = list
        hasGraph = list.any { it.graph != null }
        loadedRevision = repo.revision
    }

    private fun args(skip: Int) = VCSLogArgs(
        ref = branch.ifEmpty { null },
        skip = skip.takeIf { it > 0 },
        limit = PAGE,
        grep = text.trim().ifEmpty { null },
        author = author.trim().ifEmpty { null },
        path = path.trim().ifEmpty { null },
    )

    /** 仓库变化后（或首次）重新加载第一页 */
    fun ensureLoaded() {
        if (loadedRevision != repo.revision) reload()
    }

    fun reload() {
        if (repo.session.preview) return
        loadedRevision = repo.revision
        job?.cancel()
        loading = commits.isEmpty()
        job = AppModel.scope.launch {
            try {
                val log = repo.api().vcsLog(repo.projectId, repo.root, args(0))
                commits = log.commits
                hasMore = log.hasMore
                hasGraph = (log.graph ?: true) && log.commits.any { it.graph != null }
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: tr("common.failed")
            }
            loading = false
        }
    }

    fun loadMore() {
        if (!hasMore || loadingMore || loading || repo.session.preview) return
        loadingMore = true
        val skip = commits.size
        AppModel.scope.launch {
            val log = runCatching { repo.api().vcsLog(repo.projectId, repo.root, args(skip)) }.getOrNull()
            if (log != null && commits.size == skip) {
                commits = commits + log.commits
                hasMore = log.hasMore
            }
            loadingMore = false
        }
    }

    /** 过滤条件变化：稍等再查 */
    fun filtersChanged() {
        job?.cancel()
        job = AppModel.scope.launch {
            delay(400)
            reload()
        }
    }

    companion object {
        const val PAGE = 100
    }
}

// ---------------------------------------------------------------- 列表

/** 日志列表（无状态：截图测试直接喂数据）。点按看详情，长按出提交菜单；滚到接近末尾时 onNearEnd 加载下一页。 */
@Composable
fun VcsLogList(
    commits: List<VCSCommit>,
    hasGraph: Boolean,
    modifier: Modifier = Modifier,
    selected: String? = null,
    loadingMore: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    onClick: (VCSCommit) -> Unit = {},
    menu: ((VCSCommit) -> List<MenuEntry>)? = null,
    onNearEnd: () -> Unit = {},
) {
    val density = LocalDensity.current
    val rowH = 60.dp
    val geo = remember(density) {
        with(density) { CommitGraphGeometry(laneWidth = 12.dp.toPx(), rowHeight = rowH.toPx(), leftPadding = 8.dp.toPx()) }
    }
    val graphWidth: Dp = if (!hasGraph) 0.dp else with(density) {
        geo.width(commits.mapNotNull { it.graph }).toDp().coerceAtMost(112.dp)
    }
    LaunchedEffect(listState, commits.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (commits.isNotEmpty() && last >= commits.size - 20) onNearEnd() }
    }
    LazyColumn(modifier.fillMaxSize(), state = listState) {
        itemsIndexed(commits, key = { _, c -> c.hash }) { i, c ->
            LogRow(c, if (i > 0) commits[i - 1].graph else null, geo, graphWidth, rowH, c.hash == selected, onClick, menu)
        }
        if (loadingMore) {
            item { Box(Modifier.fillMaxWidth().padding(PaperTheme.spacing.lg), contentAlignment = Alignment.Center) { PaperSpinner(tint = PaperTheme.colors.iconSecondary) } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogRow(
    c: VCSCommit,
    previous: VCSGraphRow?,
    geo: CommitGraphGeometry,
    graphWidth: Dp,
    rowH: Dp,
    selected: Boolean,
    onClick: (VCSCommit) -> Unit,
    menu: ((VCSCommit) -> List<MenuEntry>)?,
) {
    val col = PaperTheme.colors
    val sp = PaperTheme.spacing
    val isHead = c.refs.any { it.kind == "head" }
    PaperLongPressArea(onClick = { onClick(c) }, menu = menu?.let { m -> { m(c) } }, modifier = Modifier.background(if (selected) col.bgSelected else col.bgContent)) {
        Row(Modifier.fillMaxWidth().height(rowH), verticalAlignment = Alignment.CenterVertically) {
            if (graphWidth > 0.dp) {
                GraphCell(c.graph, previous, geo, isHead, c.parents.size > 1, Modifier.width(graphWidth).height(rowH))
            } else {
                Box(Modifier.width(sp.lg))
            }
            Column(Modifier.weight(1f).padding(end = sp.lg), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalAlignment = Alignment.CenterVertically) {
                    val refs = c.refs.filter { it.kind != "head" }
                    for (r in refs.take(2)) VcsRefBadge(r, current = isHead && r.kind == "local")
                    if (refs.size > 2) Text("+${refs.size - 2}", style = PaperTheme.typography.caption, color = col.textTertiary)
                    Text(
                        c.subject,
                        style = if (isHead) PaperTheme.typography.bodyStrong else PaperTheme.typography.body,
                        color = col.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.short, style = PaperTheme.typography.monoSmall, color = col.textTertiary)
                    Text(c.author, style = PaperTheme.typography.small, color = col.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(VcsDate.short(c.parsedDate), style = PaperTheme.typography.small, color = col.textTertiary, maxLines = 1)
                }
            }
        }
    }
}

/** 提交图的一格（内核算好的车道与连线，CommitGraphGeometry 换算坐标）；HEAD 节点空心 */
@Composable
fun GraphCell(row: VCSGraphRow?, previous: VCSGraphRow?, geo: CommitGraphGeometry, isHead: Boolean, merge: Boolean, modifier: Modifier) {
    val c = PaperTheme.colors
    val density = LocalDensity.current
    val stroke = with(density) { 1.5.dp.toPx() }
    val radius = with(density) { (if (merge) 3.5.dp else 4.dp).toPx() }
    Canvas(modifier) {
        if (row == null) return@Canvas
        val r = geo.row(row, previous)
        for (s in r.segments) {
            drawLine(c.graphLane(CommitGraphGeometry.paletteIndex(s.color)), Offset(s.from.x, s.from.y), Offset(s.to.x, s.to.y), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        val color = c.graphLane(CommitGraphGeometry.paletteIndex(r.nodeColor))
        val center = Offset(r.node.x, r.node.y)
        if (isHead) {
            drawCircle(c.bgContent, radius, center)
            drawCircle(color, radius, center, style = Stroke(stroke * 1.2f))
        } else {
            drawCircle(color, radius, center)
        }
    }
}

// ---------------------------------------------------------------- 提交详情

/** 提交详情（手机：压栈页面；平板：日志右侧）。父提交点按后 onOpenCommit。 */
@Composable
fun VcsCommitDetailView(repo: VcsRepo, hash: String, onOpenCommit: (String) -> Unit, modifier: Modifier = Modifier) {
    var detail by remember(hash) { mutableStateOf<VCSCommitDetail?>(null) }
    var error by remember(hash) { mutableStateOf<String?>(null) }
    var parent by remember(hash) { mutableStateOf(1) }
    var containing by remember(hash) { mutableStateOf<VCSContainingBranches?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(hash, parent, reload) {
        try {
            detail = repo.api().vcsShow(repo.projectId, repo.root, hash, parent.takeIf { it > 1 })
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: tr("common.failed")
        }
        if (containing == null) containing = runCatching { repo.api().vcsContainingBranches(repo.projectId, repo.root, hash) }.getOrNull()
    }
    val d = detail
    when {
        d != null -> VcsCommitDetailContent(
            d, containing, parent, modifier,
            onParent = { parent = it },
            onOpenCommit = onOpenCommit,
            onOpenFile = { f ->
                val targets = d.files.map { VcsDiffTarget(it.path, it.oldPath, VcsDiffKind.Commit(d.commit.hash, parent), it.status) }
                VcsActions.openDiff(repo, targets, d.files.indexOf(f), "${d.commit.short}  ${d.commit.subject}")
            },
            fileMenu = { f -> commitFileEntries(repo, d, f, parent) },
        )
        error != null -> Column(modifier.padding(PaperTheme.spacing.lg)) {
            PaperAlert(Tone.Danger, tr("vcs.log.detailFailed"), message = error) {
                PaperButton(tr("connection.retry"), onClick = { reload++ }, size = ButtonSize.Small)
            }
        }
        else -> PaperLoadingPage(modifier)
    }
}

private fun commitFileEntries(repo: VcsRepo, d: VCSCommitDetail, f: VCSFileChange, parent: Int): List<MenuEntry> = listOf(
    MenuEntry.Item(tr("vcs.showDiff"), "file-diff") {
        val targets = d.files.map { VcsDiffTarget(it.path, it.oldPath, VcsDiffKind.Commit(d.commit.hash, parent), it.status) }
        VcsActions.openDiff(repo, targets, d.files.indexOf(f))
    },
    MenuEntry.Item(tr("vcs.fileHistory"), "history") { AppModel.nav.push(VcsFileHistoryRoute(repo.projectId, repo.root, f.path)) },
    MenuEntry.Item(tr("vcs.checkoutFileVersion"), "rotate-ccw", enabled = f.status != "D") { VcsActions.restoreFile(repo, f.path, d.commit.hash, d.commit.short) },
    MenuEntry.Divider,
    MenuEntry.Item(tr("vcs.copyPath"), "copy") { Actions.copy(f.path) },
)

/** 提交详情内容（无状态） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VcsCommitDetailContent(
    d: VCSCommitDetail,
    containing: VCSContainingBranches?,
    parent: Int,
    modifier: Modifier = Modifier,
    onParent: (Int) -> Unit = {},
    onOpenCommit: (String) -> Unit = {},
    onOpenFile: (VCSFileChange) -> Unit = {},
    fileMenu: ((VCSFileChange) -> List<MenuEntry>)? = null,
) {
    val col = PaperTheme.colors
    val sp = PaperTheme.spacing
    val c = d.commit
    val message = VcsSheets.fullMessage(d.message, c.subject, d.body)
    val subject = message.lineSequence().firstOrNull().orEmpty()
    val body = message.substringAfter('\n', "").trim()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(sp.lg), verticalArrangement = Arrangement.spacedBy(sp.md)) {
            SelectionContainer { Text(subject, style = PaperTheme.typography.heading, color = col.textPrimary) }
            if (body.isNotEmpty()) SelectionContainer { Text(body, style = PaperTheme.typography.body, color = col.textSecondary) }
            if (c.refs.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                    for (r in c.refs) VcsRefBadge(r)
                }
            }
        }
        PaperDivider(subtle = true)
        Column(Modifier.padding(horizontal = sp.lg, vertical = sp.md), verticalArrangement = Arrangement.spacedBy(sp.sm)) {
            MetaRow(tr("vcs.log.authorColumn")) {
                Text(c.author + (c.email?.let { " <$it>" } ?: ""), style = PaperTheme.typography.small, color = col.textPrimary)
            }
            MetaRow(tr("vcs.log.date")) { Text(VcsDate.full(c.parsedDate), style = PaperTheme.typography.small, color = col.textPrimary) }
            val cm = d.committer
            if (cm != null && (cm != c.author || d.committerDate != c.date)) {
                MetaRow(tr("vcs.log.committer")) {
                    Text(cm + "  " + VcsDate.full(GoTime.parse(d.committerDate)), style = PaperTheme.typography.small, color = col.textSecondary)
                }
            }
            MetaRow(tr("vcs.log.hash")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SelectionContainer(Modifier.weight(1f, fill = false)) {
                        Text(c.hash, style = PaperTheme.typography.monoSmall, color = col.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                    PaperIconButton("copy", tr("vcs.copyHash"), onClick = { Actions.copy(c.hash) }, size = ButtonSize.Small, tint = col.iconSecondary)
                }
            }
            if (c.parents.isNotEmpty()) {
                MetaRow(tr("vcs.log.parents")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(sp.md)) {
                        for (p in c.parents) PaperLink(p.take(8), onClick = { onOpenCommit(p) }, mono = true)
                    }
                }
            }
            MetaRow(tr("vcs.log.containedIn")) {
                when {
                    containing == null -> PaperSpinner(size = 14.dp, tint = col.iconSecondary)
                    containing.refs.isEmpty() -> Text(tr("vcs.log.noBranches"), style = PaperTheme.typography.small, color = col.textTertiary)
                    else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                        for (r in containing.refs.take(20)) VcsRefBadge(r)
                        if (containing.refs.size > 20 || containing.truncated) Text("…", color = col.textTertiary)
                    }
                }
            }
        }
        PaperDivider(subtle = true)
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = sp.lg),
            horizontalArrangement = Arrangement.spacedBy(sp.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr("vcs.log.files", d.files.size), style = PaperTheme.typography.label, color = col.textSecondary, modifier = Modifier.weight(1f))
            if (c.parents.size > 1) {
                PaperSegmented(c.parents.indices.map { (it + 1) to tr("vcs.log.parentN", it + 1) }, parent, onParent)
            }
        }
        for (f in d.files) VcsFileLine(f, onClick = { onOpenFile(f) }, menu = fileMenu?.let { m -> { m(f) } })
        Box(Modifier.height(sp.huge))
    }
}

@Composable
private fun MetaRow(key: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = PaperTheme.typography.small, color = PaperTheme.colors.textTertiary, modifier = Modifier.width(72.dp))
        Box(Modifier.weight(1f)) { value() }
    }
}

/** 文件行（提交详情、储藏、文件历史里用）：状态字母、文件名、目录、增删行数 */
@Composable
fun VcsFileLine(file: VCSFileChange, onClick: () -> Unit, menu: (() -> List<MenuEntry>)? = null, selected: Boolean = false) {
    val col = PaperTheme.colors
    val sp = PaperTheme.spacing
    PaperLongPressArea(onClick = onClick, menu = menu, modifier = Modifier.background(if (selected) col.bgSelected else androidx.compose.ui.graphics.Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(horizontal = sp.lg, vertical = sp.sm),
            horizontalArrangement = Arrangement.spacedBy(sp.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VcsStatusLetter(file.status)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    VcsPath.name(file.path),
                    style = PaperTheme.typography.body.copy(textDecoration = strike(file.status == "D")),
                    color = VcsStyle.color(file.status, col),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = file.oldPath?.takeIf { it != file.path }?.let { "← $it" } ?: VcsPath.dir(file.path)
                if (sub.isNotEmpty()) Text(sub, style = PaperTheme.typography.small, color = col.textTertiary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
            if (file.binary) PaperIcon("binary", size = 14.dp, tint = col.textTertiary) else VcsLineStats(file.additions, file.deletions)
        }
    }
}

/** 日志页的空 / 出错状态 */
@Composable
internal fun LogPlaceholder(log: VcsLogState) {
    val e = log.error
    when {
        e != null -> Column(Modifier.padding(PaperTheme.spacing.lg)) {
            PaperAlert(Tone.Danger, tr("vcs.log.failed"), message = e) {
                PaperButton(tr("connection.retry"), onClick = { log.reload() }, size = ButtonSize.Small)
            }
        }
        log.loading -> PaperLoadingPage()
        else -> PaperEmptyState("git-commit-horizontal", tr("vcs.log.empty"))
    }
}
