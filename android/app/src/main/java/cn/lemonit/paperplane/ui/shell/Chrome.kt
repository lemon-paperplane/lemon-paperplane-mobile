package cn.lemonit.paperplane.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.Connection
import cn.lemonit.paperplane.core.MainTab
import cn.lemonit.paperplane.core.PluginViewRoute
import cn.lemonit.paperplane.core.PluginsRoute
import cn.lemonit.paperplane.core.Route
import cn.lemonit.paperplane.core.SettingsRoute
import cn.lemonit.paperplane.core.ToolchainsRoute
import cn.lemonit.paperplane.core.VcsHomeRoute
import cn.lemonit.paperplane.features.importflow.ImportFlow
import cn.lemonit.paperplane.features.servers.ServerSwitcherButton
import cn.lemonit.paperplane.features.vcs.VcsCloneFlow
import cn.lemonit.paperplane.kit.model.ViewLocation
import cn.lemonit.paperplane.kit.util.IconSource
import cn.lemonit.paperplane.kit.model.Workspace
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperBottomSheet
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperGroup
import cn.lemonit.paperplane.ui.components.PaperGroupDefaults
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperListRow
import cn.lemonit.paperplane.ui.components.PaperMoreButton
import cn.lemonit.paperplane.ui.components.PaperSearchField
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.icons.RefIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme

// 手机的顶栏、工作区条、底部标签栏、「更多」页。平板布局复用其中的工作区列表与「更多」条目。

/** 顶栏：服务器切换器 + 重连提示 + 搜索 + 导入 */
@Composable
internal fun PhoneTopBar() {
    val nav = AppModel.nav
    val s = PaperTheme.spacing
    var searching by rememberSaveable { mutableStateOf(false) }
    val searchable = nav.tab == MainTab.PROJECTS || nav.tab == MainTab.RUNNING
    if (!searchable && searching) {
        searching = false
        nav.search = ""
    }
    Column(Modifier.fillMaxWidth().background(PaperTheme.colors.bgChrome).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = s.md, end = s.xs), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                ServerSwitcherButton(Modifier.weight(1f, fill = false))
                ReconnectingIndicator()
            }
            if (searchable) {
                PaperIconButton("search", tr("common.search"), active = searching, onClick = {
                    searching = !searching
                    if (!searching) nav.search = ""
                })
            }
            ImportMenuButton()
        }
        if (searching) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Box(Modifier.fillMaxWidth().padding(start = s.lg, end = s.lg, bottom = s.md)) {
                PaperSearchField(nav.search, { nav.search = it }, placeholder = tr("toolbar.search"), focusRequester = focus)
            }
        }
        if (nav.tab == MainTab.PROJECTS || nav.tab == MainTab.LAUNCH || nav.tab == MainTab.TERMINAL) {
            WorkspaceBar()
        }
    }
}

/** 事件流断了、正在重连 */
@Composable
internal fun ReconnectingIndicator() {
    if (AppModel.connection != Connection.RECONNECTING) return
    val c = PaperTheme.colors
    Row(Modifier.padding(start = PaperTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = c.warning)
    }
}

/** 「+」导入菜单 */
@Composable
internal fun ImportMenuButton() {
    val data = AppModel.data
    PaperMoreButton(
        entries = {
            buildList {
                add(MenuEntry.Item(tr("import.folder"), icon = "folder-input") { ImportFlow.importFolder() })
                add(MenuEntry.Item(tr("import.scan"), icon = "scan-search") { ImportFlow.scanFolder() })
                if (data.contributions.vcs.isNotEmpty()) {
                    add(MenuEntry.Divider)
                    add(MenuEntry.Item(tr("import.clone"), icon = "git-branch") { VcsCloneFlow.start() })
                }
            }
        },
        icon = "plus",
        contentDescription = tr("toolbar.import"),
        tint = PaperTheme.colors.iconPrimary,
    )
}

/** 工作区条：当前工作区名 ⌄（点开选择 / 新建 / 长按管理） */
@Composable
internal fun WorkspaceBar() {
    val data = AppModel.data
    val nav = AppModel.nav
    val c = PaperTheme.colors
    val s = PaperTheme.spacing
    var picking by remember { mutableStateOf(false) }
    val ws = data.workspace(nav.workspaceId)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = s.md, end = s.md, bottom = s.sm)
            .height(36.dp)
            .clip(RoundedCornerShape(PaperTheme.radius.lg))
            .clickable(role = Role.Button) { picking = true }
            .padding(horizontal = s.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaperIcon("layers", size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
        Spacer(Modifier.width(s.md))
        Text(
            ws?.name ?: tr("mobile.workspace.none"),
            style = PaperTheme.typography.bodyStrong,
            color = if (ws == null) c.textSecondary else c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(s.xs))
        PaperIcon("chevron-down", size = 14.dp, tint = c.iconSecondary)
        Spacer(Modifier.weight(1f))
        if (ws != null) {
            val projects = data.projects(ws.id)
            val running = projects.count { it.hasActiveRun }
            if (running > 0) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(c.statusRunning))
                Spacer(Modifier.width(s.xs))
                Text("$running", style = PaperTheme.typography.small, color = c.textSecondary)
                Text(" / ", style = PaperTheme.typography.small, color = c.textTertiary)
            }
            Text("${projects.size}", style = PaperTheme.typography.small, color = c.textTertiary)
        }
    }
    if (picking) WorkspaceSheet(onDismiss = { picking = false })
}

/** 选择工作区（长按管理：重命名、全部停止、删除） */
@Composable
internal fun WorkspaceSheet(onDismiss: () -> Unit) {
    val data = AppModel.data
    val nav = AppModel.nav
    PaperBottomSheet(onDismiss = onDismiss, title = tr("mobile.workspace.pick")) { close ->
        PaperGroup(dividerInset = PaperGroupDefaults.IconRowInset) {
            for (ws in data.workspaces) {
                WorkspaceRow(ws, selected = ws.id == nav.workspaceId, onClick = {
                    AppModel.selectWorkspace(ws.id)
                    close()
                })
            }
            PaperListRow(tr("workspace.new"), icon = "plus", onClick = {
                close()
                Actions.createWorkspace()
            })
        }
        Spacer(Modifier.height(PaperTheme.spacing.lg))
    }
}

@Composable
internal fun WorkspaceRow(ws: Workspace, selected: Boolean, onClick: () -> Unit) {
    val data = AppModel.data
    val c = PaperTheme.colors
    val projects = data.projects(ws.id)
    val running = projects.count { it.hasActiveRun }
    PaperListRow(
        ws.name,
        icon = "layers",
        iconTint = if (selected) c.accent else null,
        selected = selected,
        trailing = {
            if (running > 0) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(c.statusRunning))
                Spacer(Modifier.width(PaperTheme.spacing.sm))
            }
            Text("${projects.size}", style = PaperTheme.typography.small, color = c.textTertiary)
        },
        onClick = onClick,
        menu = {
            listOf(
                MenuEntry.Item(tr("workspace.rename"), icon = "pencil") { Actions.renameWorkspace(ws) },
                MenuEntry.Item(tr("menu.stopAll"), icon = "square", enabled = running > 0) { Actions.stopWorkspace(ws.id) },
                MenuEntry.Divider,
                MenuEntry.Item(tr("workspace.delete"), icon = "trash-2", destructive = true) { Actions.deleteWorkspace(ws) },
            )
        },
    )
}

// ---------------------------------------------------------------- 底部标签栏

private data class TabItem(val tab: MainTab, val icon: String, val title: String)

private fun tabItems() = listOf(
    TabItem(MainTab.PROJECTS, "layout-grid", tr("mobile.tab.projects")),
    TabItem(MainTab.LAUNCH, "rocket", tr("mobile.tab.launch")),
    TabItem(MainTab.RUNNING, "activity", tr("mobile.tab.running")),
    TabItem(MainTab.TERMINAL, "square-terminal", tr("mobile.tab.terminal")),
    TabItem(MainTab.MORE, "ellipsis", tr("mobile.tab.more")),
)

@Composable
internal fun BottomTabBar() {
    val nav = AppModel.nav
    val c = PaperTheme.colors
    val running = AppModel.runs.activeRuns.size
    Column(Modifier.fillMaxWidth().background(c.bgChrome)) {
        PaperDivider(subtle = true)
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).height(56.dp)) {
            for (item in tabItems()) {
                val selected = nav.tab == item.tab
                val tint = if (selected) c.accent else c.iconSecondary
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                        ) {
                            if (nav.tab == item.tab) nav.popToRoot() else nav.selectTab(item.tab)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box {
                        PaperIcon(item.icon, size = PaperTheme.sizes.icon, tint = tint)
                        if (item.tab == MainTab.RUNNING && running > 0) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 10.dp, y = (-4).dp)
                                    .height(16.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(c.statusRunning)
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("$running", style = PaperTheme.typography.caption, color = c.textOnAccent)
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(item.title, style = PaperTheme.typography.caption, color = if (selected) c.accent else c.textSecondary)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 更多

/** 「更多」里的条目（手机：更多页列表；平板：侧栏下半部分） */
internal data class MoreItem(val icon: String, val title: String, val route: Route, val iconSource: IconSource? = null)

@Composable
internal fun moreItems(): List<List<MoreItem>> {
    val views = AppModel.data.contributions.views(ViewLocation.SIDEBAR)
    return buildList {
        add(
            listOf(
                MoreItem("git-branch", tr("mobile.more.vcs"), VcsHomeRoute),
                MoreItem("boxes", tr("sidebar.toolchains"), ToolchainsRoute),
                MoreItem("puzzle", tr("sidebar.plugins"), PluginsRoute),
            ),
        )
        if (views.isNotEmpty()) {
            add(views.map { v -> MoreItem("puzzle", v.title, PluginViewRoute(v.key), IconSource.parse(v.icon, v.plugin)) })
        }
        add(
            listOf(
                MoreItem("server", tr("mobile.more.servers"), SettingsRoute("servers")),
                MoreItem("settings", tr("sidebar.settings"), SettingsRoute()),
            ),
        )
    }
}

@Composable
internal fun MorePage() {
    val s = PaperTheme.spacing
    val groups = moreItems()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = s.lg)) {
        for (g in groups) {
            Box(Modifier.padding(horizontal = s.lg)) {
                PaperGroup(dividerInset = PaperGroupDefaults.IconRowInset) {
                    for (item in g) {
                        PaperListRow(
                            item.title,
                            leading = { RefIcon(item.iconSource ?: IconSource.Builtin(item.icon), tint = PaperTheme.colors.iconPrimary) },
                            showChevron = true,
                            onClick = { AppModel.nav.push(item.route) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(s.xl))
        }
    }
}
