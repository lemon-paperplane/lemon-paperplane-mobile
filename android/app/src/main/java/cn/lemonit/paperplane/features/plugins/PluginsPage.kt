package cn.lemonit.paperplane.features.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.PluginViewRoute
import cn.lemonit.paperplane.features.importflow.ServerPathPicker
import cn.lemonit.paperplane.kit.ViewContext
import cn.lemonit.paperplane.kit.installPlugin
import cn.lemonit.paperplane.kit.model.PluginInfo
import cn.lemonit.paperplane.kit.reloadPlugins
import cn.lemonit.paperplane.kit.setPluginEnabled
import cn.lemonit.paperplane.kit.uninstallPlugin
import cn.lemonit.paperplane.kit.util.IconSource
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperBadge
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.components.PaperGroup
import cn.lemonit.paperplane.ui.components.PaperGroupDefaults
import cn.lemonit.paperplane.ui.components.PaperListRow
import cn.lemonit.paperplane.ui.components.PaperLoadingPage
import cn.lemonit.paperplane.ui.components.PaperMoreButton
import cn.lemonit.paperplane.ui.components.PaperSearchField
import cn.lemonit.paperplane.ui.components.PaperSegmented
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.icons.RefIcon
import cn.lemonit.paperplane.ui.shell.PageScaffold
import cn.lemonit.paperplane.ui.theme.PaperTheme

/** 插件与商店（对照 Mac Features/Plugins/PluginsPage）：已安装列表点进详情；商店按兼容版本安装、更新。 */
@Composable
fun PluginsPage() {
    var tab by rememberSaveable { mutableStateOf("installed") }
    PageScaffold(
        title = tr("sidebar.plugins"),
        actions = {
            PaperMoreButton({
                listOf(
                    MenuEntry.Item(tr("plugin.installFromFile").trimEnd('…'), "upload") { PluginOps.installFromFile() },
                    MenuEntry.Item(tr("mobile.plugin.reload"), "refresh-cw") { PluginOps.reloadAll() },
                )
            })
        },
        header = {
            Box(Modifier.fillMaxWidth().padding(start = PaperTheme.spacing.xl, end = PaperTheme.spacing.xl, bottom = PaperTheme.spacing.md)) {
                PaperSegmented(
                    listOf("installed" to tr("plugin.installed"), "store" to tr("plugin.store")),
                    selected = tab,
                    onSelect = { tab = it },
                    fill = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        if (tab == "installed") InstalledPlugins() else StoreView()
    }
}

/** 已安装的插件 */
@Composable
private fun InstalledPlugins() {
    val all = AppModel.data.plugins
    var filter by rememberSaveable { mutableStateOf("") }
    val sp = PaperTheme.spacing
    if (all.isEmpty()) {
        if (!AppModel.data.loaded) PaperLoadingPage() else PaperEmptyState("puzzle", tr("plugin.none"), actionText = tr("plugin.installFromFile").trimEnd('…'), actionIcon = "upload") { PluginOps.installFromFile() }
        return
    }
    val list = all.filter { filter.isBlank() || it.name.contains(filter.trim(), true) || it.id.contains(filter.trim(), true) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(sp.lg), verticalArrangement = Arrangement.spacedBy(sp.lg)) {
        if (all.size > 8) PaperSearchField(filter, { filter = it }, placeholder = tr("common.filter"))
        PaperGroup(dividerInset = PaperGroupDefaults.IconRowInset + 6.dp) {
            for (p in list) PluginRow(p)
        }
    }
}

@Composable
private fun PluginRow(p: PluginInfo) {
    val c = PaperTheme.colors
    PaperListRow(
        p.name,
        subtitle = listOfNotNull(p.version, p.vendor?.takeIf { it.isNotEmpty() }).joinToString("  ·  "),
        leading = {
            RefIcon(IconSource.parse(p.icon, p.id), size = PaperTheme.sizes.iconLarge, modifier = Modifier.alpha(if (p.enabled) 1f else 0.45f))
        },
        trailing = {
            if (!p.error.isNullOrEmpty()) PaperIcon("circle-alert", size = PaperTheme.sizes.iconSmall, tint = c.danger)
            if (!p.enabled) PaperBadge(tr("plugin.disabled"))
            if (p.running) Box(Modifier.size(7.dp).background(c.statusRunning, CircleShape))
        },
        showChevron = true,
        onClick = { AppModel.nav.push(PluginDetailRoute(p.id)) },
        menu = { PluginOps.menu(p) },
    )
}

/** 插件相关操作（列表长按菜单、详情页共用） */
internal object PluginOps {
    fun menu(p: PluginInfo): List<MenuEntry> = buildList {
        add(MenuEntry.Item(if (p.enabled) tr("mobile.plugin.disable") else tr("mobile.plugin.enable"), if (p.enabled) "circle-pause" else "circle-play") { setEnabled(p, !p.enabled) })
        if (p.source != "bundled") {
            add(MenuEntry.Divider)
            add(MenuEntry.Item(uninstallTitle(p), "trash-2", destructive = true) { uninstall(p) })
        }
    }

    fun uninstallTitle(p: PluginInfo) = p.bundledVersion?.let { tr("plugin.revert", it) } ?: tr("plugin.uninstall")

    fun setEnabled(p: PluginInfo, on: Boolean) = AppModel.perform(tr("settings.saveFailed")) { api ->
        api.setPluginEnabled(p.id, on)
        AppModel.reloadPlugins()
    }

    fun uninstall(p: PluginInfo, after: () -> Unit = {}) {
        Dialogs.confirm(tr("plugin.uninstall.title", p.name), confirmText = uninstallTitle(p), danger = true) {
            AppModel.api().uninstallPlugin(p.id)
            AppModel.reloadPlugins()
            after()
        }
    }

    /** 插件装在服务器上：选服务器上的插件包（zip）或插件文件夹 */
    fun installFromFile() {
        ServerPathPicker.pick(tr("plugin.installFromFile").trimEnd('…'), files = true) { path ->
            AppModel.perform(tr("plugin.installFailed")) { api ->
                val info = api.installPlugin(path)
                AppModel.reloadPlugins()
                Toasts.success(tr("plugin.installedToast", info.name, info.version))
                AppModel.nav.push(PluginDetailRoute(info.id))
            }
        }
    }

    /** 重新扫描插件目录与 manifest */
    fun reloadAll() = AppModel.perform(tr("mobile.plugin.reload")) { api ->
        api.reloadPlugins()
        AppModel.reloadPlugins()
        Toasts.success(tr("mobile.plugin.reloaded"))
    }
}

/** 插件贡献的独立页面（侧栏类视图：「更多」里的条目） */
@Composable
fun PluginViewPage(route: PluginViewRoute) {
    val view = AppModel.data.contributions.views.firstOrNull { it.key == route.viewKey }
    PageScaffold(title = view?.title ?: tr("sidebar.plugins")) {
        when {
            view != null -> {
                val ctx = remember(view.key) { ViewContext(view.plugin, view.id) }
                PluginViewHost(view, ctx)
            }
            !AppModel.data.loaded -> PaperLoadingPage()
            else -> PaperEmptyState("puzzle", tr("plugin.viewMissing"))
        }
    }
}
