package cn.lemonit.paperplane.features.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.Connection
import cn.lemonit.paperplane.core.ServerEntry
import cn.lemonit.paperplane.core.ServerPhase
import cn.lemonit.paperplane.core.SettingsRoute
import cn.lemonit.paperplane.ui.components.PaperBottomSheet
import cn.lemonit.paperplane.ui.components.PaperGroup
import cn.lemonit.paperplane.ui.components.PaperGroupDefaults
import cn.lemonit.paperplane.ui.components.PaperListRow
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme

/** 系统图标（共用图标库里没有系统徽标，用设备类图标：桌面系统 monitor，Linux 服务器 server；与桌面版一致） */
fun osIcon(os: String): String = if (os == "darwin" || os == "windows") "monitor" else "server"

fun osName(os: String): String = if (os == "darwin" || os == "windows" || os == "linux") tr("server.os.$os") else os

/** 顶栏左上角的服务器切换器：系统图标 + 当前服务器名 + ⌄，点开底部面板列出全部服务器 */
@Composable
fun ServerSwitcherButton(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val c = PaperTheme.colors
    val s = PaperTheme.spacing
    val server = AppModel.currentServer
    val os = AppModel.systemInfo?.os ?: server?.os.orEmpty()
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(PaperTheme.radius.lg))
            .clickable(role = Role.Button) { open = true }
            .padding(horizontal = s.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            PaperIcon(osIcon(os), size = PaperTheme.sizes.icon, tint = c.iconSecondary)
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(c.bgChrome)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(phaseColor()),
            )
        }
        Spacer(Modifier.width(s.md))
        Text(
            server?.name ?: tr("server.add.title"),
            style = PaperTheme.typography.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(s.xs))
        PaperIcon("chevron-down", size = 14.dp, tint = c.iconSecondary)
    }
    if (open) ServerSwitcherSheet(onDismiss = { open = false })
}

/** 当前服务器的状态色 */
@Composable
fun phaseColor(): Color {
    val c = PaperTheme.colors
    return when (val p = AppModel.phase) {
        ServerPhase.Ready -> if (AppModel.connection == Connection.CONNECTED) c.statusRunning else c.warning
        ServerPhase.Connecting -> c.warning
        is ServerPhase.Failed -> if (p.failure.needsLogin) c.danger else c.statusFailed
        ServerPhase.None -> c.statusIdle
    }
}

/** 切换器面板：全部服务器（当前那台打勾），底部「添加服务器…」「管理服务器…」 */
@Composable
fun ServerSwitcherSheet(onDismiss: () -> Unit) {
    PaperBottomSheet(onDismiss = onDismiss, title = tr("mobile.server.switch")) { close ->
        PaperGroup(dividerInset = PaperGroupDefaults.IconRowInset) {
            for (s in AppModel.serverList) ServerRow(s) {
                close()
                AppModel.switchServer(s.id)
            }
        }
        Spacer(Modifier.height(PaperTheme.spacing.lg))
        PaperGroup(dividerInset = PaperGroupDefaults.IconRowInset) {
            PaperListRow(tr("server.add").trimEnd('…'), icon = "plus", onClick = {
                close()
                AppModel.nav.push(AddServerRoute())
            })
            PaperListRow(tr("server.manage").trimEnd('…'), icon = "settings", onClick = {
                close()
                AppModel.nav.push(SettingsRoute("servers"))
            })
        }
        Spacer(Modifier.height(PaperTheme.spacing.lg))
    }
}

@Composable
private fun ServerRow(s: ServerEntry, onClick: () -> Unit) {
    val c = PaperTheme.colors
    val current = AppModel.currentServerId == s.id
    val info = if (current) AppModel.systemInfo else null
    val detail = if (current && AppModel.phase == ServerPhase.Ready && info != null) {
        listOf(info.hostname, osName(info.os)).filter { it.isNotEmpty() }.joinToString(" · ")
    } else {
        s.displayAddress + " · " + statusText(current)
    }
    val running = if (current) AppModel.runs.activeRuns.size else 0
    PaperListRow(
        s.name,
        subtitle = detail,
        leading = { PaperIcon(osIcon(info?.os ?: s.os), size = PaperTheme.sizes.icon, tint = c.iconSecondary) },
        selected = current,
        trailing = {
            if (running > 0) {
                PaperIcon("activity", size = PaperTheme.sizes.iconSmall, tint = c.statusRunning)
                Spacer(Modifier.width(PaperTheme.spacing.xxs))
                Text("$running", style = PaperTheme.typography.small, color = c.textSecondary)
                Spacer(Modifier.width(PaperTheme.spacing.md))
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (current) phaseColor() else c.statusIdle))
            Spacer(Modifier.width(PaperTheme.spacing.md))
            Box(Modifier.size(PaperTheme.sizes.iconSmall)) {
                if (current) PaperIcon("check", size = PaperTheme.sizes.iconSmall, tint = c.accent)
            }
        },
        onClick = onClick,
    )
}

private fun statusText(current: Boolean): String {
    if (!current) return tr("server.status.disconnected")
    return when (AppModel.phase) {
        ServerPhase.Ready -> tr("server.status.connected")
        ServerPhase.Connecting -> tr("server.status.connecting")
        is ServerPhase.Failed -> tr("server.status.failed")
        ServerPhase.None -> tr("server.status.disconnected")
    }
}
