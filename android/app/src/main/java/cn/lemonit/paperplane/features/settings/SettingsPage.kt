package cn.lemonit.paperplane.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.ServerEntry
import cn.lemonit.paperplane.core.ServerPhase
import cn.lemonit.paperplane.core.SettingsRoute
import cn.lemonit.paperplane.features.forms.SectionCaption
import cn.lemonit.paperplane.features.plugins.PluginViewHost
import cn.lemonit.paperplane.features.servers.AddServerRoute
import cn.lemonit.paperplane.features.servers.osIcon
import cn.lemonit.paperplane.kit.ViewContext
import cn.lemonit.paperplane.kit.model.ViewLocation
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperBadge
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperGroup
import cn.lemonit.paperplane.ui.components.PaperGroupDefaults
import cn.lemonit.paperplane.ui.components.PaperListRow
import cn.lemonit.paperplane.ui.components.PaperMoreButton
import cn.lemonit.paperplane.ui.components.PaperSegmented
import cn.lemonit.paperplane.ui.components.PaperSelectSheet
import cn.lemonit.paperplane.ui.components.SelectOption
import cn.lemonit.paperplane.ui.i18n.I18n
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.shell.PageScaffold
import cn.lemonit.paperplane.ui.theme.Appearance
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.ThemeMode
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.delay

/**
 * 设置（对照 Mac Features/Settings + Servers/ServerSettings）：本机界面（语言、主题，只影响手机）、已添加的服务器、
 * 「允许其他设备连接「服务器名」」、当前服务器上的设置（镜像、运行与资源、编辑器、关于）、插件的设置页视图。
 * route.anchor 为 "servers" / "host" 时打开后滚到对应分区。
 */
@Composable
fun SettingsPage(route: SettingsRoute) {
    val scroll = rememberScrollState()
    val anchors = remember { mutableStateMapOf<String, Int>() }
    var scrolled by remember(route.anchor) { mutableStateOf(false) }
    val target = route.anchor?.let { anchors[it] }
    LaunchedEffect(route.anchor, target) {
        if (scrolled || target == null) return@LaunchedEffect
        delay(200)
        scroll.animateScrollTo(target)
        scrolled = true
    }
    val ready = AppModel.phase == ServerPhase.Ready
    val server = AppModel.currentServer
    val serverName = server?.name.orEmpty()
    val host = rememberHostModel(if (ready) AppModel.currentServerId else null)
    val sp = PaperTheme.spacing
    PageScaffold(title = tr("sidebar.settings")) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(sp.lg), verticalArrangement = Arrangement.spacedBy(sp.xxl)) {
            AppAppearanceGroup()
            Box(Modifier.onGloballyPositioned { anchors["servers"] = it.positionInParent().y.toInt() }) {
                ServersGroup(AppModel.serverList, AppModel.currentServerId, AppModel.phase)
            }
            if (ready && server != null) {
                Box(Modifier.onGloballyPositioned { anchors["host"] = it.positionInParent().y.toInt() }) {
                    HostSection(host, serverName)
                }
                ServerSettingsSections(serverName)
                for (v in AppModel.data.contributions.views(ViewLocation.SETTINGS)) {
                    androidx.compose.runtime.key(v.key) {
                        PaperGroup(v.title) {
                            Box(Modifier.fillMaxWidth().padding(sp.lg)) {
                                PluginViewHost(v, remember(v.key) { ViewContext(v.plugin, v.id) }, scrolls = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 本机界面

/** 语言、主题：存在手机上，不改服务器的设置 */
@Composable
fun AppAppearanceGroup() {
    val lang by I18n.setting.collectAsState()
    val theme by Appearance.mode.collectAsState()
    var pickLang by remember { mutableStateOf(false) }
    val langs = listOf(
        SelectOption(I18n.SYSTEM, tr("settings.followSystem")),
        SelectOption(I18n.ZH, "中文"),
        SelectOption(I18n.EN, "English"),
    )
    PaperGroup(tr("mobile.settings.app"), info = tr("mobile.settings.app.info"), dividerInset = PaperGroupDefaults.IconRowInset) {
        SettingRow(
            tr("settings.language"),
            icon = "languages",
            value = langs.firstOrNull { it.value == lang }?.title,
            showChevron = true,
            onClick = { pickLang = true },
        )
        SettingRow(tr("settings.theme"), icon = if (PaperTheme.isDark) "moon" else "sun") {
            PaperSegmented(
                listOf(ThemeMode.System to tr("settings.followSystem"), ThemeMode.Light to tr("settings.light"), ThemeMode.Dark to tr("settings.dark")),
                selected = theme,
                onSelect = { Appearance.set(it) },
            )
        }
    }
    if (pickLang) PaperSelectSheet(langs, lang, onDismiss = { pickLang = false }, title = tr("settings.language")) { I18n.setSetting(it) }
}

// ---------------------------------------------------------------- 已添加的服务器

/** 本机保存的服务器：系统图标、名称、地址、指纹前 8 位；点一下（或 ···）连接 / 重命名 / 重新登录 / 删除 */
@Composable
fun ServersGroup(servers: List<ServerEntry>, currentId: String?, phase: ServerPhase) {
    val c = PaperTheme.colors
    PaperGroup(
        tr("settings.servers"),
        dividerInset = PaperGroupDefaults.IconRowInset,
        trailing = {
            PaperButton(tr("server.add").trimEnd('…'), onClick = { AppModel.nav.push(AddServerRoute()) }, kind = ButtonKind.Text, size = ButtonSize.Small, icon = "plus")
        },
    ) {
        if (servers.isEmpty()) {
            PaperListRow(tr("server.saved.empty"), enabled = false)
        }
        for (s in servers) {
            val current = s.id == currentId
            PaperListRow(
                s.name,
                subtitle = listOf(s.displayAddress, s.shortFingerprint).filter { it.isNotEmpty() }.joinToString("  ·  "),
                subtitleMono = true,
                leading = { PaperIcon(osIcon(if (current) AppModel.systemInfo?.os ?: s.os else s.os), tint = c.iconSecondary) },
                trailing = {
                    if (current) {
                        when (phase) {
                            ServerPhase.Ready -> PaperBadge(tr("server.status.connected"), tone = Tone.Success)
                            ServerPhase.Connecting -> PaperBadge(tr("server.status.connecting"), tone = Tone.Warning)
                            is ServerPhase.Failed -> PaperBadge(tr("server.status.failed"), tone = Tone.Danger)
                            ServerPhase.None -> {}
                        }
                    }
                    PaperMoreButton({ serverMenu(s, current, phase) }, size = ButtonSize.Small)
                },
                menu = { serverMenu(s, current, phase) },
            )
        }
    }
}

private fun serverMenu(s: ServerEntry, current: Boolean, phase: ServerPhase): List<MenuEntry> = listOf(
    MenuEntry.Item(tr("server.action.connect"), "plug", enabled = !(current && phase == ServerPhase.Ready)) {
        if (current) AppModel.reconnectServer() else AppModel.switchServer(s.id)
    },
    MenuEntry.Item(tr("server.action.rename"), "pencil") {
        Dialogs.input(tr("server.rename.title"), label = tr("common.name"), initial = s.name, confirmText = tr("common.save")) { name ->
            AppModel.renameServer(s.id, name)
        }
    },
    MenuEntry.Item(tr("server.action.relogin"), "key-round") { AppModel.nav.push(AddServerRoute(reloginId = s.id)) },
    MenuEntry.Divider,
    MenuEntry.Item(tr("server.action.remove"), "trash-2", destructive = true) {
        Dialogs.confirm(tr("server.remove.confirm", s.name), message = tr("server.remove.message"), confirmText = tr("server.action.remove"), danger = true) {
            AppModel.removeServer(s.id)
        }
    },
)

// ---------------------------------------------------------------- 当前服务器上的设置

@Composable
private fun ServerSettingsSections(serverName: String) {
    val settings = AppModel.data.settings ?: return
    val sp = PaperTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(sp.xxl)) {
        SectionCaption(tr("mobile.settings.serverSettings", serverName), info = tr("mobile.settings.serverSettings.info"))
        MirrorGroup(settings)
        BehaviorGroup(settings)
        EditorGroup(settings)
        AboutGroup()
    }
}
