package cn.lemonit.paperplane.features.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.TerminalRoute
import cn.lemonit.paperplane.kit.model.ProjectSummary
import cn.lemonit.paperplane.kit.model.TerminalInfo
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.kit.util.IconSource
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.PaperBottomSheet
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.components.PaperGroup
import cn.lemonit.paperplane.ui.components.PaperGroupDefaults
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperInfoButton
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperListRow
import cn.lemonit.paperplane.ui.components.PaperMoreButton
import cn.lemonit.paperplane.ui.components.PaperSectionHeader
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.icons.RefIcon
import cn.lemonit.paperplane.ui.shell.LocalWideLayout
import cn.lemonit.paperplane.ui.theme.PaperTheme

// 「终端」标签（对照 Mac Features/Terminal/TerminalPage）：当前工作区的终端列表，点一个全屏打开；
// 平板上左边列表、右边直接是终端。终端在服务器内核里，App 关掉再打开还在，输出会补齐。

/** 「终端」标签：当前工作区的终端列表与新建（对照 Mac Features/Terminal/TerminalPage） */
@Composable
fun TerminalTab() {
    val ws = AppModel.nav.workspaceId
    if (ws == null) {
        PaperEmptyState("square-terminal", tr("mobile.workspace.none"), Modifier.fillMaxSize())
        return
    }
    val list = AppModel.terminals.terminals(ws)
    if (LocalWideLayout.current) WideTerminals(ws, list) else TerminalList(ws, list)
}

/** 手机：列表，点一个压栈打开 */
@Composable
private fun TerminalList(ws: String, list: List<TerminalInfo>) {
    if (list.isEmpty()) {
        EmptyTerminals(ws)
        return
    }
    val s = PaperTheme.spacing
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = s.lg, end = s.lg, top = s.md, bottom = s.xxl)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(s.sm)) {
                PaperSectionHeader(tr("mobile.tab.terminal"), info = tr("mobile.terminal.info")) {
                    PaperButton(tr("terminal.new"), onClick = { pickProject(ws) }, kind = ButtonKind.Primary, size = ButtonSize.Small, icon = "plus")
                }
                PaperGroup(dividerInset = PaperGroupDefaults.IconRowInset) {
                    for (t in list) TerminalRow(t, AppModel.data.projects[t.projectId]) { TerminalActions.open(t) }
                }
            }
        }
    }
}

@Composable
private fun TerminalRow(t: TerminalInfo, project: ProjectSummary?, selected: Boolean = false, chevron: Boolean = true, onClick: () -> Unit) {
    val c = PaperTheme.colors
    PaperListRow(
        title = t.name,
        subtitle = TerminalActions.subtitle(t, project),
        leading = { PaperIcon("square-terminal", tint = if (t.exited) c.danger else c.iconPrimary) },
        showChevron = chevron,
        selected = selected,
        onClick = onClick,
        menu = { TerminalActions.menu(t) },
    )
}

@Composable
private fun EmptyTerminals(ws: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        PaperEmptyState("square-terminal", tr("terminal.empty"), actionText = tr("terminal.new"), actionIcon = "plus") { pickProject(ws) }
    }
}

/** 「新建终端」：选工作区里的一个工程，终端从它的根目录开始 */
internal fun pickProject(ws: String) {
    val projects = AppModel.data.projects(ws)
    Dialogs.present("terminal-new") { dismiss ->
        PaperBottomSheet(onDismiss = dismiss, title = tr("terminal.pickProject"), edgeToEdge = true) { close ->
            if (projects.isEmpty()) {
                Text(
                    tr("terminal.noProjects"),
                    style = PaperTheme.typography.body,
                    color = PaperTheme.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = PaperTheme.spacing.xl, vertical = PaperTheme.spacing.lg),
                )
            }
            for (p in projects) {
                val facet = p.facets.firstOrNull()
                PaperListRow(
                    title = p.name,
                    subtitle = Format.abbreviatePath(p.path),
                    leading = { RefIcon(IconSource.parse(facet?.icon, facet?.plugin), fallback = "folder", tint = PaperTheme.colors.iconPrimary) },
                    onClick = {
                        close()
                        TerminalActions.create(p.id)
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 平板：列表 + 终端双栏

@Composable
private fun WideTerminals(ws: String, list: List<TerminalInfo>) {
    val c = PaperTheme.colors
    val s = PaperTheme.spacing
    val store = AppModel.terminals
    val current = store.current(ws)
    val session = remember(ws) { TerminalSession() }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(300.dp).fillMaxHeight().background(c.bgContent)) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(start = s.xl, end = s.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(s.xs),
            ) {
                Text(tr("mobile.tab.terminal"), style = PaperTheme.typography.bodyStrong, color = c.textPrimary)
                PaperInfoButton(tr("mobile.terminal.info"))
                Spacer(Modifier.weight(1f))
                PaperIconButton("plus", tr("terminal.new"), onClick = { pickProject(ws) })
            }
            PaperDivider(subtle = true)
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(list, key = { it.id }) { t ->
                    TerminalRow(t, AppModel.data.projects[t.projectId], selected = t.id == current?.id, chevron = false) {
                        store.selected[ws] = t.id
                    }
                }
            }
        }
        PaperDivider(vertical = true, subtle = true)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (list.isEmpty() || current == null) {
                EmptyTerminals(ws, Modifier.background(c.bgContent))
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).background(c.bgContent).padding(start = s.xl, end = s.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(s.sm),
                    ) {
                        PaperIcon("square-terminal", size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
                        Text(current.name, style = PaperTheme.typography.bodyStrong, color = c.textPrimary, maxLines = 1)
                        Text(
                            Format.abbreviatePath(current.cwd),
                            style = PaperTheme.typography.small,
                            color = c.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TerminalMenuButton(current, session)
                    }
                    PaperDivider(subtle = true)
                    TerminalPane(session, current, list.map { it.id }, Modifier.weight(1f))
                }
            }
        }
    }
}

/** 终端的「更多」菜单（页面标题栏、平板工具栏共用） */
@Composable
internal fun TerminalMenuButton(t: TerminalInfo, session: TerminalSession) {
    PaperMoreButton(
        entries = {
            buildList {
                add(MenuEntry.Item(tr("terminal.rename"), icon = "pencil") { TerminalActions.rename(t) })
                if (t.exited) add(MenuEntry.Item(tr("terminal.restart"), icon = "rotate-ccw") { TerminalActions.restart(t) })
                add(MenuEntry.Item(tr("mobile.terminal.history"), icon = "scroll-text") { AppModel.nav.push(TerminalHistoryRoute(t.id)) })
                add(MenuEntry.Divider)
                add(MenuEntry.Item(tr("mobile.terminal.fontBigger"), icon = "plus", enabled = session.fontSize < TerminalActions.FONT_SIZES.last) {
                    session.changeFontSize(session.fontSize + 1)
                })
                add(MenuEntry.Item(tr("mobile.terminal.fontSmaller"), icon = "minus", enabled = session.fontSize > TerminalActions.FONT_SIZES.first) {
                    session.changeFontSize(session.fontSize - 1)
                })
                add(MenuEntry.Divider)
                add(MenuEntry.Item(tr("terminal.close"), icon = "trash-2", destructive = true) { TerminalActions.close(t) })
            }
        },
        tint = PaperTheme.colors.iconPrimary,
    )
}

/**
 * 终端区域 + 已退出提示 + 辅助键栏；底部让出系统导航栏与软键盘。
 */
@Composable
internal fun TerminalPane(session: TerminalSession, t: TerminalInfo, allIds: List<String>, modifier: Modifier = Modifier) {
    val c = PaperTheme.colors
    val s = PaperTheme.spacing
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))) {
        if (t.exited) {
            Row(
                Modifier.fillMaxWidth().background(c.dangerSubtle).padding(start = s.xl, end = s.md, top = s.xs, bottom = s.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(s.md),
            ) {
                PaperIcon("circle-alert", size = PaperTheme.sizes.iconSmall, tint = c.danger)
                Text(tr("terminal.exited", t.exitCode), style = PaperTheme.typography.body, color = c.textPrimary, modifier = Modifier.weight(1f))
                PaperButton(tr("terminal.restart"), onClick = { TerminalActions.restart(t) }, size = ButtonSize.Small, icon = "rotate-ccw")
            }
        }
        TerminalSurface(session, t.id, allIds, Modifier.weight(1f).fillMaxWidth())
        TerminalKeyBar(session, keyboardVisible = imeVisible, onPaste = {
            val text = Actions.clipboardText()
            if (!text.isNullOrEmpty()) session.paste(text)
        })
    }
}

/** 页面里用的：终端已经不在列表里（别处关掉了、输入 exit 退出了）时关掉页面 */
@Composable
internal fun CloseWhenGone(terminalId: String, present: Boolean) {
    var seen by remember(terminalId) { mutableStateOf(false) }
    LaunchedEffect(present) {
        if (present) seen = true
        else if (seen) AppModel.nav.removeWhere { (it is TerminalRoute && it.terminalId == terminalId) || (it is TerminalHistoryRoute && it.terminalId == terminalId) }
    }
}
