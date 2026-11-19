package cn.lemonit.paperplane.features.terminal

import androidx.compose.runtime.Composable
import cn.lemonit.paperplane.core.TerminalRoute
import cn.lemonit.paperplane.ui.shell.PendingPage

/** 「终端」标签：当前工作区的终端列表与新建（对照 Mac Features/Terminal/TerminalPage） */
@Composable
fun TerminalTab() = PendingPage("终端")

/** 一个终端全屏 */
@Composable
fun TerminalPage(route: TerminalRoute) = PendingPage("终端")
