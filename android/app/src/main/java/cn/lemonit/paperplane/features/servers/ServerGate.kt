package cn.lemonit.paperplane.features.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.ServerFailure
import cn.lemonit.paperplane.core.ServerPhase
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperLoadingPage
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme

/**
 * 当前服务器还没连上时挡在内容前面：正在连接（第一次加载）、连不上（原因 + 重新登录 / 重试 / 切换服务器）。
 * 连上之后显示 [content]。
 */
@Composable
fun ServerGate(content: @Composable () -> Unit) {
    when (val p = AppModel.phase) {
        ServerPhase.Ready -> if (AppModel.data.loaded) content() else PaperLoadingPage(text = tr("server.status.connecting"))
        ServerPhase.Connecting -> PaperLoadingPage(text = tr("server.status.connecting"))
        is ServerPhase.Failed -> ServerFailedView(p.failure)
        ServerPhase.None -> PaperLoadingPage()
    }
}

@Composable
private fun ServerFailedView(failure: ServerFailure) {
    val c = PaperTheme.colors
    val s = PaperTheme.spacing
    val server = AppModel.currentServer
    var switching by remember { mutableStateOf(false) }
    val login = failure.needsLogin
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = s.xxxl, vertical = s.huge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(s.lg, Alignment.CenterVertically),
    ) {
        PaperIcon(if (login) "key-round" else "cloud-off", size = PaperTheme.sizes.iconEmpty, tint = c.iconSecondary)
        Text(tr("server.connectFailed", server?.name.orEmpty()), style = PaperTheme.typography.title, textAlign = TextAlign.Center)
        if (server != null) {
            SelectionContainer {
                Text(server.displayAddress, style = PaperTheme.typography.mono, color = c.textTertiary, textAlign = TextAlign.Center)
            }
        }
        SelectionContainer {
            Text(
                failure.text,
                style = PaperTheme.typography.body,
                color = c.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 480.dp),
            )
        }
        Column(
            Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(top = s.md),
            verticalArrangement = Arrangement.spacedBy(s.md),
        ) {
            if (server != null) {
                PaperButton(
                    tr("server.action.relogin"),
                    onClick = { AppModel.nav.push(AddServerRoute(reloginId = server.id)) },
                    icon = "key-round",
                    kind = if (login) ButtonKind.Primary else ButtonKind.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PaperButton(
                tr("server.retry"),
                onClick = { AppModel.reconnectServer() },
                icon = "refresh-cw",
                kind = if (login) ButtonKind.Secondary else ButtonKind.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            if (AppModel.serverList.size > 1) {
                PaperButton(tr("mobile.server.switch"), onClick = { switching = true }, icon = "server", modifier = Modifier.fillMaxWidth())
            }
        }
    }
    if (switching) ServerSwitcherSheet(onDismiss = { switching = false })
}
