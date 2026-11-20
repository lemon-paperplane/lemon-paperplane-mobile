package cn.lemonit.paperplane.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.MainTab
import cn.lemonit.paperplane.features.servers.ServerGate
import cn.lemonit.paperplane.features.servers.ServerSwitcherButton
import cn.lemonit.paperplane.kit.util.IconSource
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperLongPressArea
import cn.lemonit.paperplane.ui.components.PaperSearchField
import cn.lemonit.paperplane.ui.components.PaperSegmented
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.icons.RefIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme

// 平板（宽度 ≥ 840dp）：和桌面一样的常驻侧栏 + 圆角内容区。
// 侧栏：服务器切换器、工作区列表、运行中、版本控制 / 工具链 / 插件 / 插件视图、服务器 / 设置。
// 内容区：选中工作区时顶部分段「工程 / 启动 / 终端」；选中「更多」里的条目时直接显示那个页面；压栈页面盖在内容区里。

private val SidebarWidth = 280.dp

@Composable
internal fun TabletShell() {
    val nav = AppModel.nav
    val c = PaperTheme.colors
    val holder = rememberSaveableStateHolder()
    BackHandler(enabled = nav.stack.isNotEmpty()) { nav.pop() }
    // 手机「更多」标签在平板上没有对应的根页面：没选条目时显示工作区
    LaunchedEffect(nav.tab, nav.moreSelection) {
        if (nav.tab == MainTab.MORE && nav.moreSelection == null) nav.tab = MainTab.PROJECTS
    }
    Row(Modifier.fillMaxSize().background(c.bgChrome).windowInsetsPadding(WindowInsets.safeDrawing)) {
        TabletSidebar(Modifier.width(SidebarWidth).fillMaxHeight())
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(end = PaperTheme.spacing.md, bottom = PaperTheme.spacing.md, top = PaperTheme.spacing.md)
                .clip(RoundedCornerShape(PaperTheme.radius.xl))
                .background(c.bgContent)
                .border(PaperTheme.sizes.border, c.border, RoundedCornerShape(PaperTheme.radius.xl)),
        ) {
            CompositionLocalProvider(LocalWideLayout provides true, LocalEdgeToEdge provides false) {
                StackTransition(nav.stack.size, nav.top) { route ->
                    if (route != null) {
                        holder.SaveableStateProvider("route:" + route.key) {
                            CompositionLocalProvider(LocalPageBack provides { nav.pop() }) { route.Content() }
                        }
                    } else {
                        CompositionLocalProvider(LocalPageBack provides null) { TabletRoot(holder) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabletRoot(holder: androidx.compose.runtime.saveable.SaveableStateHolder) {
    val nav = AppModel.nav
    val more = nav.moreSelection
    if (nav.tab == MainTab.MORE && more != null) {
        holder.SaveableStateProvider("more:" + more.key) { ServerGate { more.Content() } }
        return
    }
    Column(Modifier.fillMaxSize()) {
        TabletPageHeader()
        PaperDivider(subtle = true)
        Box(Modifier.weight(1f).fillMaxWidth()) { ServerGate { TabRoot(nav.tab, holder) } }
    }
}

/** 工作区页 / 运行中页的标题行：名称 + 分段（工程 / 启动 / 终端）+ 搜索 + 导入 */
@Composable
private fun TabletPageHeader() {
    val nav = AppModel.nav
    val s = PaperTheme.spacing
    var searching by remember { mutableStateOf(false) }
    val running = nav.tab == MainTab.RUNNING
    val title = if (running) tr("sidebar.running") else AppModel.data.workspace(nav.workspaceId)?.name ?: tr("mobile.workspace.none")
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = s.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(s.md),
    ) {
        Text(title, style = PaperTheme.typography.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (!running) {
            PaperSegmented(
                listOf(
                    MainTab.PROJECTS to tr("mobile.tab.projects"),
                    MainTab.LAUNCH to tr("mobile.tab.launch"),
                    MainTab.TERMINAL to tr("mobile.tab.terminal"),
                ),
                selected = nav.tab,
                onSelect = { nav.tab = it },
            )
        }
        Spacer(Modifier.weight(1f))
        if (nav.tab == MainTab.PROJECTS || running) {
            if (searching) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Box(Modifier.width(260.dp)) {
                    PaperSearchField(nav.search, { nav.search = it }, placeholder = tr("toolbar.search"), focusRequester = focus)
                }
            }
            PaperIconButton("search", tr("common.search"), active = searching, onClick = {
                searching = !searching
                if (!searching) nav.search = ""
            })
        }
        ImportMenuButton()
    }
}

@Composable
private fun TabletSidebar(modifier: Modifier) {
    val nav = AppModel.nav
    val data = AppModel.data
    val s = PaperTheme.spacing
    val c = PaperTheme.colors
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = s.md), verticalAlignment = Alignment.CenterVertically) {
            ServerSwitcherButton(Modifier.weight(1f, fill = false))
            ReconnectingIndicator()
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = s.md, vertical = s.sm)) {
            Row(Modifier.fillMaxWidth().padding(start = s.md, bottom = s.xxs), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("sidebar.workspaces"), style = PaperTheme.typography.caption, color = c.textTertiary, modifier = Modifier.weight(1f))
                PaperIconButton("plus", tr("workspace.new"), onClick = { Actions.createWorkspace() }, size = cn.lemonit.paperplane.ui.components.ButtonSize.Small)
            }
            val onWorkspacePage = nav.tab == MainTab.PROJECTS || nav.tab == MainTab.LAUNCH || nav.tab == MainTab.TERMINAL
            for (ws in data.workspaces) {
                val projects = data.projects(ws.id)
                val running = projects.count { it.hasActiveRun }
                SidebarItem(
                    icon = "layers",
                    title = ws.name,
                    selected = onWorkspacePage && nav.workspaceId == ws.id,
                    count = projects.size,
                    runningDot = running > 0,
                    menu = {
                        listOf(
                            MenuEntry.Item(tr("workspace.rename"), icon = "pencil") { Actions.renameWorkspace(ws) },
                            MenuEntry.Item(tr("menu.stopAll"), icon = "square", enabled = running > 0) { Actions.stopWorkspace(ws.id) },
                            MenuEntry.Divider,
                            MenuEntry.Item(tr("workspace.delete"), icon = "trash-2", destructive = true) { Actions.deleteWorkspace(ws) },
                        )
                    },
                ) {
                    AppModel.selectWorkspace(ws.id)
                    if (!onWorkspacePage) nav.tab = MainTab.PROJECTS
                    nav.moreSelection = null
                    nav.popToRoot()
                }
            }
            Spacer(Modifier.height(s.md))
            val active = AppModel.runs.activeRuns.size
            SidebarItem(
                icon = "activity",
                title = tr("sidebar.running"),
                selected = nav.tab == MainTab.RUNNING,
                count = active,
                countColor = if (active > 0) c.statusRunning else null,
            ) {
                nav.tab = MainTab.RUNNING
                nav.moreSelection = null
                nav.popToRoot()
            }
            for (group in moreItems()) {
                PaperDivider(Modifier.padding(vertical = s.md, horizontal = s.md), subtle = true)
                for (item in group) {
                    SidebarItem(
                        iconSource = item.iconSource ?: IconSource.Builtin(item.icon),
                        title = item.title,
                        selected = nav.tab == MainTab.MORE && nav.moreSelection?.key == item.route.key,
                    ) {
                        nav.tab = MainTab.MORE
                        nav.moreSelection = item.route
                        nav.popToRoot()
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarItem(
    title: String,
    selected: Boolean,
    icon: String? = null,
    iconSource: IconSource? = null,
    count: Int? = null,
    countColor: Color? = null,
    runningDot: Boolean = false,
    menu: (() -> List<MenuEntry>)? = null,
    onClick: () -> Unit,
) {
    val c = PaperTheme.colors
    val s = PaperTheme.spacing
    PaperLongPressArea(onClick = onClick, menu = menu, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(PaperTheme.radius.lg))
                .background(if (selected) c.bgSelected else Color.Transparent)
                .padding(horizontal = s.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = if (selected) c.accent else c.iconPrimary
            if (iconSource != null) RefIcon(iconSource, size = PaperTheme.sizes.icon, tint = tint) else PaperIcon(icon ?: "circle", size = PaperTheme.sizes.icon, tint = tint)
            Spacer(Modifier.width(s.md))
            Text(
                title,
                style = if (selected) PaperTheme.typography.bodyStrong else PaperTheme.typography.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (runningDot) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(c.statusRunning))
                Spacer(Modifier.width(s.sm))
            }
            if (count != null) Text("$count", style = PaperTheme.typography.small, color = countColor ?: c.textTertiary)
        }
    }
}
