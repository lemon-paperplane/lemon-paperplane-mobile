package cn.lemonit.paperplane.core

import androidx.compose.runtime.Composable
import cn.lemonit.paperplane.features.browser.BrowserPage
import cn.lemonit.paperplane.features.editor.EditorPage
import cn.lemonit.paperplane.features.plugins.PluginViewPage
import cn.lemonit.paperplane.features.plugins.PluginsPage
import cn.lemonit.paperplane.features.project.ProjectDetailPage
import cn.lemonit.paperplane.features.settings.SettingsPage
import cn.lemonit.paperplane.features.terminal.TerminalPage
import cn.lemonit.paperplane.features.toolchains.ToolchainsPage
import cn.lemonit.paperplane.features.vcs.VcsHomePage

// 跨功能会跳转的页面。每个页面的实现在各功能包里（函数签名不要改，改了要同步这里）。

/** 工程详情的标签 */
enum class ProjectTab { OVERVIEW, RUN, FILES, VCS, CONFIG, PLUGIN }

/**
 * 工程详情。tab = RUN 时 configId 指定显示哪个运行配置的控制台；tab = PLUGIN 时 viewKey 是插件视图（ViewInfo.key）。
 */
data class ProjectRoute(
    val projectId: String,
    val tab: ProjectTab = ProjectTab.OVERVIEW,
    val configId: String? = null,
    val viewKey: String? = null,
) : Route {
    override val key get() = "project:$projectId"

    @Composable
    override fun Content() = ProjectDetailPage(this)
}

/** 编辑器（WebView 加载 /editor/）。path 是服务器上的绝对路径；projectId 有值时按工程打开（工程根、语言设置）。 */
data class EditorRoute(val path: String, val projectId: String? = null, val line: Int? = null) : Route {
    override val key get() = "editor:$path"

    @Composable
    override fun Content() = EditorPage(this)
}

/** 交互终端（全屏）。 */
data class TerminalRoute(val terminalId: String) : Route {
    override val key get() = "terminal:$terminalId"

    @Composable
    override fun Content() = TerminalPage(this)
}

/** App 内置浏览器：打开运行地址（端口转发到手机回环地址的，或服务器地址直连的）。forwardPort 有值时页面负责开/关端口转发。 */
data class BrowserRoute(val url: String, val title: String? = null, val forwardPort: Int? = null, val runId: String? = null) : Route {
    override val key get() = "browser:$url"

    @Composable
    override fun Content() = BrowserPage(this)
}

data object ToolchainsRoute : Route {
    override val key get() = "toolchains"

    @Composable
    override fun Content() = ToolchainsPage()
}

data object PluginsRoute : Route {
    override val key get() = "plugins"

    @Composable
    override fun Content() = PluginsPage()
}

/** 设置。anchor 打开后滚动到的分组（如 "servers"、"host"）。 */
data class SettingsRoute(val anchor: String? = null) : Route {
    override val key get() = "settings"

    @Composable
    override fun Content() = SettingsPage(this)
}

/** 插件贡献的独立页面（ViewInfo.location = sidebar 的视图）。 */
data class PluginViewRoute(val viewKey: String) : Route {
    override val key get() = "pluginView:$viewKey"

    @Composable
    override fun Content() = PluginViewPage(this)
}

/** 版本控制总览（「更多 → 版本控制」：各工程的仓库状态，点进去是工程详情的版本控制标签）。 */
data object VcsHomeRoute : Route {
    override val key get() = "vcs"

    @Composable
    override fun Content() = VcsHomePage()
}
