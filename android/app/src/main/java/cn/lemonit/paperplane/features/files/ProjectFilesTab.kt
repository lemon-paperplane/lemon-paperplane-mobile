package cn.lemonit.paperplane.features.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.features.terminal.TerminalActions
import cn.lemonit.paperplane.kit.model.FSEntry
import cn.lemonit.paperplane.kit.model.FSGrepMatch
import cn.lemonit.paperplane.kit.model.FileIcon
import cn.lemonit.paperplane.kit.model.LanguageResolver
import cn.lemonit.paperplane.kit.model.ProjectSummary
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.kit.util.IconSource
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperLongPressArea
import cn.lemonit.paperplane.ui.components.PaperMoreButton
import cn.lemonit.paperplane.ui.components.PaperSearchField
import cn.lemonit.paperplane.ui.components.PaperSegmented
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.icons.RefIcon
import cn.lemonit.paperplane.ui.theme.PaperColors
import cn.lemonit.paperplane.ui.theme.PaperTheme
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

// 工程详情的「文件」标签（对照 Mac Features/Files/FilesTab）。
// 手机上不做展开式文件树，一次显示一个目录：顶部面包屑（点哪一级回到哪一级）、返回键回上一级；
// 点文件进编辑器，长按出菜单（新建、重命名、删除、复制路径、加到启动、在终端中打开）；
// 搜索两种：按文件名（fs.search）和在文件内容里找（fs.grep，点结果跳到那一行）。

/** 工程详情的「文件」标签（对照 Mac Features/Files/FilesTab） */
@Composable
fun ProjectFilesTab(projectId: String) {
    val state = remember(projectId) { FilesState(projectId) }
    FilesScreen(state, AppModel.data.projects[projectId])
}

/** 「文件」标签本体（截图测试直接给预先填好的 [state]） */
@OptIn(FlowPreview::class)
@Composable
internal fun FilesScreen(state: FilesState, project: ProjectSummary?, initialDir: String = "") {
    val projectId = state.projectId
    var dir by rememberSaveable(projectId) { mutableStateOf(initialDir) }
    val scope = rememberCoroutineScope()
    val projectPath = project?.path.orEmpty()
    val reload: suspend (String) -> Unit = { d ->
        state.load(d)
        state.loadGit()
    }

    val currentDir by rememberUpdatedState(dir)

    LaunchedEffect(projectId, dir) { state.load(dir) }
    LaunchedEffect(projectId) {
        state.loadGit()
    }
    // 仓库变化（提交、切分支、外部修改被 git 发现）或回到前台时刷新
    LaunchedEffect(projectId) {
        AppModel.bus.vcsChanged.filter { it.projectId == projectId }.debounce(400).collect { state.refresh(currentDir) }
    }
    LaunchedEffect(projectId) {
        AppModel.bus.resumed.collect { state.refresh(currentDir) }
    }

    BackHandler(enabled = state.searching) {
        state.searching = false
        state.clearSearch()
    }
    BackHandler(enabled = !state.searching && dir.isNotEmpty()) { dir = FileActions.parent(dir) }

    Column(Modifier.fillMaxSize().background(PaperTheme.colors.bgContent)) {
        if (state.searching) {
            SearchBar(state)
        } else {
            PathBar(
                projectName = project?.name ?: projectId,
                dir = dir,
                onNavigate = { dir = it },
                menu = {
                    folderMenu(state, projectId, projectPath, dir, reload) { scope.launch { state.refresh(dir) } }
                },
                onSearch = { state.searching = true },
            )
        }
        PaperDivider(subtle = true)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.searching) {
                SearchResults(state, projectId, projectPath, reload)
            } else {
                Listing(state, projectId, projectPath, dir, onOpenDir = { dir = it }, reload = reload)
            }
        }
    }
}

// ---------------------------------------------------------------- 顶部

/** 面包屑：工程名 › a › b，横向放不下时可以左右滑，切换目录后滚到最后 */
@Composable
private fun PathBar(
    projectName: String,
    dir: String,
    onNavigate: (String) -> Unit,
    menu: () -> List<MenuEntry>,
    onSearch: () -> Unit,
) {
    val c = PaperTheme.colors
    val s = PaperTheme.spacing
    val scroll = rememberScrollState()
    LaunchedEffect(dir) {
        delay(16)
        scroll.animateScrollTo(scroll.maxValue)
    }
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(start = s.xs, end = s.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dir.isNotEmpty()) {
            PaperIconButton("arrow-up", tr("mobile.files.up"), onClick = { onNavigate(FileActions.parent(dir)) }, tint = c.iconPrimary)
        } else {
            Spacer(Modifier.width(s.md))
        }
        Row(
            Modifier.weight(1f).horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val crumbs = FilesState.crumbs(dir)
            Crumb(projectName, current = crumbs.isEmpty(), icon = "folder-tree") { onNavigate("") }
            for ((name, path) in crumbs) {
                PaperIcon("chevron-right", size = 14.dp, tint = c.iconSecondary)
                Crumb(name, current = path == dir) { onNavigate(path) }
            }
        }
        PaperIconButton("search", tr("files.filter"), onClick = onSearch, tint = c.iconPrimary)
        PaperMoreButton(entries = menu, tint = c.iconPrimary)
    }
}

@Composable
private fun Crumb(text: String, current: Boolean, icon: String? = null, onClick: () -> Unit) {
    val c = PaperTheme.colors
    val s = PaperTheme.spacing
    Row(
        Modifier
            .defaultMinSize(minHeight = 44.dp)
            .clip(RoundedCornerShape(PaperTheme.radius.md))
            .clickable(enabled = !current, role = Role.Button, onClick = onClick)
            .padding(horizontal = s.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(s.xs),
    ) {
        if (icon != null) PaperIcon(icon, size = PaperTheme.sizes.iconSmall, tint = if (current) c.iconPrimary else c.iconSecondary)
        Text(
            text,
            style = if (current) PaperTheme.typography.bodyStrong else PaperTheme.typography.body,
            color = if (current) c.textPrimary else c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp),
        )
    }
}

/** 当前目录的「更多」菜单 */
private fun folderMenu(
    state: FilesState,
    projectId: String,
    projectPath: String,
    dir: String,
    reload: suspend (String) -> Unit,
    refresh: () -> Unit,
): List<MenuEntry> = listOf(
    MenuEntry.Item(tr("files.newFile").trimEnd('…', '.'), icon = "file-plus") { FileActions.newItem(projectId, projectPath, dir, false, reload) },
    MenuEntry.Item(tr("files.newFolder").trimEnd('…', '.'), icon = "folder-plus") { FileActions.newItem(projectId, projectPath, dir, true, reload) },
    MenuEntry.Divider,
    MenuEntry.Item(tr("mobile.files.showHidden"), icon = "eye", checked = state.showHidden) { state.toggleHidden() },
    MenuEntry.Item(tr("files.refresh"), icon = "refresh-cw") { refresh() },
    MenuEntry.Divider,
    MenuEntry.Item(tr("files.openTerminal"), icon = "square-terminal") { TerminalActions.create(projectId = projectId, cwd = dir, open = true) },
    MenuEntry.Item(tr("files.copyPath"), icon = "copy") { Actions.copy(FileActions.absolute(dir, projectPath)) },
)

@Composable
private fun SearchBar(state: FilesState) {
    val c = PaperTheme.colors
    val s = PaperTheme.spacing
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // 防抖：文件名 200ms；内容搜索开销大，500ms 且至少 2 个字
    LaunchedEffect(state.query, state.mode, state.caseSensitive, state.regex) {
        val q = state.query.trim()
        if (state.mode == FileSearchMode.CONTENT && q.length < 2) {
            if (q.isEmpty()) state.search()
            return@LaunchedEffect
        }
        delay(if (state.mode == FileSearchMode.NAME) 200 else 500)
        state.search()
    }
    Column(Modifier.fillMaxWidth().padding(start = s.lg, end = s.xs, top = s.sm, bottom = s.sm), verticalArrangement = Arrangement.spacedBy(s.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                PaperSearchField(
                    state.query,
                    { state.query = it },
                    placeholder = tr(if (state.mode == FileSearchMode.NAME) "files.filter" else "editor.findInFiles.placeholder"),
                    focusRequester = focus,
                )
            }
            PaperIconButton("x", tr("common.close"), onClick = {
                state.searching = false
                state.clearSearch()
            }, tint = c.iconSecondary)
        }
        Row(Modifier.padding(end = s.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s.sm)) {
            PaperSegmented(
                listOf(FileSearchMode.NAME to tr("mobile.files.searchName"), FileSearchMode.CONTENT to tr("mobile.files.searchContent")),
                selected = state.mode,
                onSelect = { state.mode = it },
            )
            Spacer(Modifier.weight(1f))
            if (state.searchBusy) PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
            if (state.mode == FileSearchMode.CONTENT) {
                PaperIconButton("case-sensitive", tr("editor.find.matchCase"), onClick = { state.caseSensitive = !state.caseSensitive }, active = state.caseSensitive, size = ButtonSize.Small)
                PaperIconButton("regex", tr("editor.find.regex"), onClick = { state.regex = !state.regex }, active = state.regex, size = ButtonSize.Small)
            }
        }
    }
}

// ---------------------------------------------------------------- 目录内容

@Composable
private fun Listing(
    state: FilesState,
    projectId: String,
    projectPath: String,
    dir: String,
    onOpenDir: (String) -> Unit,
    reload: suspend (String) -> Unit,
) {
    val entries = state.visible(dir)
    val err = state.errors[dir]
    val scope = rememberCoroutineScope()
    when {
        entries == null && err != null -> PaperEmptyState(
            "circle-alert", tr("files.loadFailed") + "\n" + err, Modifier.fillMaxSize(),
            actionText = tr("connection.retry"), actionIcon = "refresh-cw",
        ) { scope.launch { state.load(dir) } }
        entries == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PaperSpinner(tint = PaperTheme.colors.iconSecondary) }
        entries.isEmpty() -> PaperEmptyState(
            "folder-open", tr(if (dir.isEmpty()) "files.empty" else "mobile.files.emptyFolder"), Modifier.fillMaxSize(),
            actionText = tr("files.newFile").trimEnd('…', '.'), actionIcon = "file-plus",
        ) { FileActions.newItem(projectId, projectPath, dir, false, reload) }
        else -> {
            val list = rememberLazyListState()
            LaunchedEffect(dir) { list.scrollToItem(0) }
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = PaperTheme.spacing.xs),
            ) {
                items(entries, key = { it.path }) { e ->
                    EntryRow(
                        entry = e,
                        git = state.git[e.path],
                        changedDir = e.dir && e.path in state.changedDirs,
                        onClick = { if (e.dir) onOpenDir(e.path) else FileActions.edit(projectId, projectPath, e.path) },
                        menu = { FileActions.menu(projectId, projectPath, e, reload) },
                    )
                }
                if (state.listings[dir]?.truncated == true) {
                    item {
                        Text(
                            tr("files.truncated"),
                            style = PaperTheme.typography.small,
                            color = PaperTheme.colors.textTertiary,
                            modifier = Modifier.padding(PaperTheme.spacing.xl),
                        )
                    }
                }
                item { BottomInset() }
            }
        }
    }
}

/** 列表末尾让出系统导航栏（工程详情页压栈全屏显示时） */
@Composable
private fun BottomInset() {
    Spacer(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)))
}

@Composable
private fun EntryRow(entry: FSEntry, git: String?, changedDir: Boolean, onClick: () -> Unit, menu: () -> List<MenuEntry>) {
    val c = PaperTheme.colors
    val s = PaperTheme.spacing
    val t = PaperTheme.typography
    val nameColor = when {
        git != null -> gitColor(git, c)
        changedDir -> c.info
        else -> c.textPrimary
    }
    val detail = if (entry.dir) null else listOfNotNull(Format.bytes(entry.size), FileActions.mtime(entry.mtime)).joinToString(" · ")
    PaperLongPressArea(onClick = onClick, menu = menu, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = PaperTheme.sizes.rowHeight)
                .alpha(if (entry.ignored) 0.5f else if (entry.hidden) 0.75f else 1f)
                .padding(horizontal = s.xl, vertical = s.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(s.lg),
        ) {
            FileIconView(entry)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s.xs)) {
                    Text(entry.name, style = t.body, color = nameColor, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
                    if (entry.symlink) PaperIcon("folder-symlink", size = 14.dp, tint = c.iconSecondary, contentDescription = tr("files.symlink"))
                }
                if (!detail.isNullOrEmpty()) Text(detail, style = t.small, color = c.textTertiary, maxLines = 1)
            }
            if (git != null) Text(git, style = t.monoSmall, color = gitColor(git, c))
            if (entry.dir) PaperIcon("chevron-right", size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
        }
    }
}

/** 文件图标：有语言贡献且对应某个技术徽标时用插件图标，否则按扩展名用通用图标（同 Mac FileIconView） */
@Composable
private fun FileIconView(entry: FSEntry) {
    val c = PaperTheme.colors
    val contributions = AppModel.data.contributions
    val builtin = FileIcon.builtinName(entry)
    val facetIcon = if (entry.dir) null else {
        LanguageResolver.language(entry.name, contributions.languages)?.let { lang ->
            contributions.facet(lang.id)?.let { f -> IconSource.parse(f.icon, f.plugin) }
        }
    }
    if (facetIcon != null) {
        RefIcon(facetIcon, size = PaperTheme.sizes.icon, tint = c.iconPrimary, fallback = builtin)
    } else {
        PaperIcon(builtin, size = PaperTheme.sizes.icon, tint = if (entry.dir) c.iconSecondary else c.iconPrimary)
    }
}

/** Git 状态颜色（与 IDEA / Mac 一致：修改蓝、新增绿、未跟踪橙、冲突红、删除灰） */
internal fun gitColor(s: String, c: PaperColors): Color = when (s) {
    "M", "R", "C", "T" -> c.info
    "A" -> c.success
    "?" -> c.warning
    "U" -> c.danger
    "D" -> c.textTertiary
    else -> c.textPrimary
}

// ---------------------------------------------------------------- 搜索结果

@Composable
private fun SearchResults(state: FilesState, projectId: String, projectPath: String, reload: suspend (String) -> Unit) {
    val c = PaperTheme.colors
    val s = PaperTheme.spacing
    if (state.query.isBlank()) return
    when (state.mode) {
        FileSearchMode.NAME -> {
            val hits = state.nameHits ?: return
            if (hits.isEmpty() && !state.searchBusy) {
                PaperEmptyState("file-search", state.searchError ?: tr("files.noMatch"), Modifier.fillMaxSize())
                return
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(hits, key = { it.path }) { hit ->
                    val entry = FSEntry(name = hit.name, path = hit.path)
                    val git = state.git[hit.path]
                    PaperLongPressArea(
                        onClick = { FileActions.edit(projectId, projectPath, hit.path) },
                        menu = { FileActions.menu(projectId, projectPath, entry, reload) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(horizontal = s.xl, vertical = s.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(s.lg),
                        ) {
                            FileIconView(entry)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(hit.name, style = PaperTheme.typography.body, color = git?.let { gitColor(it, c) } ?: c.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                                if (hit.directory.isNotEmpty()) {
                                    Text(hit.directory, style = PaperTheme.typography.small, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                                }
                            }
                        }
                    }
                }
                item { BottomInset() }
            }
        }
        FileSearchMode.CONTENT -> {
            val r = state.grep ?: return
            if (r.matches.isEmpty() && !state.searchBusy) {
                PaperEmptyState("text-search", state.searchError ?: tr("editor.findInFiles.noResults"), Modifier.fillMaxSize())
                return
            }
            val groups = remember(r) { FilesState.group(r.matches) }
            val pattern = remember(state.query, state.regex, state.caseSensitive) { highlightPattern(state.query.trim(), state.regex, state.caseSensitive) }
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Text(
                        tr(if (r.truncated) "editor.findInFiles.countTruncated" else "editor.findInFiles.count", r.matches.size, groups.size),
                        style = PaperTheme.typography.small,
                        color = c.textTertiary,
                        modifier = Modifier.padding(horizontal = s.xl, vertical = s.md),
                    )
                }
                for ((path, matches) in groups) {
                    item(key = "f:$path") { GrepFileHeader(path, matches.size, state.git[path]) }
                    items(matches, key = { "m:$path:${it.line}:${it.column}" }) { m ->
                        GrepLine(m, pattern) { FileActions.edit(projectId, projectPath, m.path, m.line) }
                    }
                }
                item { BottomInset() }
            }
        }
    }
}

@Composable
private fun GrepFileHeader(path: String, count: Int, git: String?) {
    val c = PaperTheme.colors
    val s = PaperTheme.spacing
    val name = path.substringAfterLast('/')
    val dir = FileActions.parent(path)
    Row(
        Modifier.fillMaxWidth().background(c.bgSubtle).padding(horizontal = s.xl, vertical = s.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(s.md),
    ) {
        PaperIcon(FileIcon.builtinName(name), size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
        Text(name, style = PaperTheme.typography.bodyStrong, color = git?.let { gitColor(it, c) } ?: c.textPrimary, maxLines = 1)
        Text(dir, style = PaperTheme.typography.small, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f))
        Text("$count", style = PaperTheme.typography.small, color = c.textTertiary)
    }
}

@Composable
private fun GrepLine(m: FSGrepMatch, pattern: Regex?, onClick: () -> Unit) {
    val c = PaperTheme.colors
    val s = PaperTheme.spacing
    val text = remember(m, pattern, c) { highlight(m.text.trim(), pattern, SpanStyle(background = c.searchMatch)) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = s.xl, vertical = s.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(s.md),
    ) {
        Text("${m.line}", style = PaperTheme.typography.monoSmall, color = c.textTertiary, modifier = Modifier.width(44.dp))
        Text(text, style = PaperTheme.typography.monoSmall, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** 结果行里要高亮的模式；正则写错时不高亮 */
internal fun highlightPattern(query: String, regex: Boolean, caseSensitive: Boolean): Regex? {
    if (query.isEmpty()) return null
    val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
    return runCatching { if (regex) Regex(query, opts) else Regex(Regex.escape(query), opts) }.getOrNull()
}

internal fun highlight(text: String, pattern: Regex?, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    if (pattern == null) return@buildAnnotatedString
    var n = 0
    for (m in pattern.findAll(text)) {
        if (m.range.isEmpty()) continue
        addStyle(style, m.range.first, m.range.last + 1)
        if (++n >= 20) break
    }
}
