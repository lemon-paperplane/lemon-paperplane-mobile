package cn.lemonit.paperplane.features.browser

import androidx.compose.runtime.Composable
import cn.lemonit.paperplane.core.BrowserRoute
import cn.lemonit.paperplane.ui.shell.PendingPage

/** App 内置浏览器 */
@Composable
fun BrowserPage(route: BrowserRoute) = PendingPage("浏览器")
