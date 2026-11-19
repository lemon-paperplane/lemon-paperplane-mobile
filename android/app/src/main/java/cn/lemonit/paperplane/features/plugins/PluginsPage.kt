package cn.lemonit.paperplane.features.plugins

import androidx.compose.runtime.Composable
import cn.lemonit.paperplane.core.PluginViewRoute
import cn.lemonit.paperplane.ui.shell.PendingPage

/** 插件与商店（对照 Mac Features/Plugins） */
@Composable
fun PluginsPage() = PendingPage("插件")

/** 插件贡献的独立页面 */
@Composable
fun PluginViewPage(route: PluginViewRoute) = PendingPage("插件视图")
