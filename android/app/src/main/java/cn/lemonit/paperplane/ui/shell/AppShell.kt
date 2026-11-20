package cn.lemonit.paperplane.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.MainTab
import cn.lemonit.paperplane.core.Route
import cn.lemonit.paperplane.features.launch.LaunchTab
import cn.lemonit.paperplane.features.runs.RunningTab
import cn.lemonit.paperplane.features.servers.ServerGate
import cn.lemonit.paperplane.features.servers.WelcomePage
import cn.lemonit.paperplane.features.terminal.TerminalTab
import cn.lemonit.paperplane.features.workspace.ProjectsTab
import cn.lemonit.paperplane.ui.components.PaperDialogHost
import cn.lemonit.paperplane.ui.components.PaperToastHost
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.PaperplaneTheme

/** 宽度达到这个值用平板布局（常驻侧栏） */
val WideLayoutMinWidth = 840.dp

/**
 * App 根界面：还没添加服务器时是欢迎页；否则手机布局（顶栏 + 底部标签）或平板布局（侧栏 + 内容区）。
 * 对话框（底部面板）和提示条的宿主放在最上层。
 */
@Composable
fun AppRoot() {
    PaperplaneTheme {
        Box(Modifier.fillMaxSize().background(PaperTheme.colors.bgChrome)) {
            if (AppModel.serverList.isEmpty()) {
                WelcomePage()
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    if (maxWidth >= WideLayoutMinWidth) TabletShell() else PhoneShell()
                }
            }
            PaperDialogHost()
            PaperToastHost(
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom)),
            )
        }
    }
}

// ---------------------------------------------------------------- 手机

@Composable
private fun PhoneShell() {
    val nav = AppModel.nav
    val holder = rememberSaveableStateHolder()
    BackHandler(enabled = nav.stack.isNotEmpty()) { nav.pop() }
    // 不在「工程」标签时，返回键先回到「工程」，再退出
    BackHandler(enabled = nav.stack.isEmpty() && nav.tab != MainTab.PROJECTS) { nav.selectTab(MainTab.PROJECTS) }

    StackTransition(nav.stack.size, nav.top) { route ->
        if (route != null) {
            holder.SaveableStateProvider("route:" + route.key) {
                CompositionLocalProvider(LocalPageBack provides { nav.pop() }, LocalEdgeToEdge provides true) {
                    route.Content()
                }
            }
        } else {
            Column(Modifier.fillMaxSize().background(PaperTheme.colors.bgChrome)) {
                PhoneTopBar()
                Box(Modifier.weight(1f).fillMaxWidth().background(PaperTheme.colors.bgContent)) {
                    CompositionLocalProvider(LocalEdgeToEdge provides false, LocalPageBack provides null) {
                        ServerGate { TabRoot(nav.tab, holder) }
                    }
                }
                BottomTabBar()
            }
        }
    }
}

/** 底部标签对应的根页面（状态按标签保存，切回来滚动位置还在） */
@Composable
internal fun TabRoot(tab: MainTab, holder: SaveableStateHolder) {
    val ws = AppModel.nav.workspaceId.orEmpty()
    holder.SaveableStateProvider("tab:${tab.name}:$ws") {
        when (tab) {
            MainTab.PROJECTS -> ProjectsTab()
            MainTab.LAUNCH -> LaunchTab()
            MainTab.RUNNING -> RunningTab()
            MainTab.TERMINAL -> TerminalTab()
            MainTab.MORE -> MorePage()
        }
    }
}

/** 压栈 / 出栈的左右滑动 */
@Composable
internal fun StackTransition(depth: Int, top: Route?, content: @Composable (Route?) -> Unit) {
    val motion = PaperTheme.motion
    AnimatedContent(
        targetState = depth to top,
        contentKey = { (d, r) -> "$d:${r?.key}" },
        transitionSpec = {
            val forward = targetState.first >= initialState.first
            val ms = motion.normal
            if (forward) {
                (slideInHorizontally(tween(ms)) { it / 3 } + fadeIn(tween(ms))) togetherWith fadeOut(tween(ms / 2))
            } else {
                fadeIn(tween(ms)) togetherWith (slideOutHorizontally(tween(ms)) { it / 3 } + fadeOut(tween(ms)))
            }
        },
        label = "stack",
    ) { (_, route) -> content(route) }
}
