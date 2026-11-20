package cn.lemonit.paperplane.features.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.TerminalRoute
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.components.PaperInfoButton
import cn.lemonit.paperplane.ui.components.PaperLoadingPage
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.shell.PageScaffold

/** 一个终端全屏（xterm 网页 + 辅助键栏）。标题栏：名称、目录（已退出时显示退出码），「更多」里重命名、完整输出、字号、关闭。 */
@Composable
fun TerminalPage(route: TerminalRoute) {
    val store = AppModel.terminals
    val t = store.list.firstOrNull { it.id == route.terminalId }
    CloseWhenGone(route.terminalId, t != null)
    val session = remember(route.terminalId) { TerminalSession() }
    if (t == null) {
        PageScaffold(title = tr("mobile.tab.terminal")) {
            if (AppModel.data.loaded) PaperEmptyState("square-terminal", tr("mobile.terminal.gone"), Modifier.fillMaxSize()) else PaperLoadingPage()
        }
        return
    }
    val ids = store.terminals(t.workspaceId).map { it.id }
    PageScaffold(
        title = t.name,
        subtitle = if (t.exited) tr("terminal.exited", t.exitCode) else Format.abbreviatePath(t.cwd),
        actions = {
            PaperInfoButton(tr("mobile.terminal.info"))
            TerminalMenuButton(t, session)
        },
    ) {
        TerminalPane(session, t, ids)
    }
}
