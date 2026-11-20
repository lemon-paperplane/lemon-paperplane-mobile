package cn.lemonit.paperplane.features.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.Route
import cn.lemonit.paperplane.features.forms.FormFields
import cn.lemonit.paperplane.features.forms.FormValues
import cn.lemonit.paperplane.kit.ViewContext
import cn.lemonit.paperplane.kit.model.ContribSummary
import cn.lemonit.paperplane.kit.model.PluginInfo
import cn.lemonit.paperplane.kit.model.PluginSettingsInfo
import cn.lemonit.paperplane.kit.model.ViewLocation
import cn.lemonit.paperplane.kit.updatePluginSettings
import cn.lemonit.paperplane.kit.util.IconSource
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperBadge
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperCard
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.components.PaperGroup
import cn.lemonit.paperplane.ui.components.PaperListRow
import cn.lemonit.paperplane.ui.components.PaperSectionHeader
import cn.lemonit.paperplane.ui.components.PaperToggleRow
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.RefIcon
import cn.lemonit.paperplane.ui.shell.PageScaffold
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/** 插件详情（已安装列表点进来） */
data class PluginDetailRoute(val pluginId: String) : Route {
    override val key get() = "plugin:$pluginId"

    @Composable
    override fun Content() = PluginDetailPage(pluginId)
}

@Composable
private fun PluginDetailPage(pluginId: String) {
    val p = AppModel.data.plugin(pluginId)
    PageScaffold(title = p?.name ?: pluginId, subtitle = p?.id) {
        if (p == null) {
            PaperEmptyState("puzzle", tr("plugin.none"))
        } else {
            PluginDetailContent(p)
        }
    }
}

/** 详情内容：头部、启用开关、出错信息、提供的功能、依赖、设置、设置页视图、主页、卸载 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PluginDetailContent(p: PluginInfo, modifier: Modifier = Modifier) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val contributions = AppModel.data.contributions
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(sp.lg), verticalArrangement = Arrangement.spacedBy(sp.xl)) {
        PaperCard {
            Row(horizontalArrangement = Arrangement.spacedBy(sp.lg), verticalAlignment = Alignment.Top) {
                RefIcon(IconSource.parse(p.icon, p.id), size = 40.dp, tint = c.iconPrimary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                    Text(p.name, style = PaperTheme.typography.display, color = c.textPrimary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                        PaperBadge(p.version, mono = true)
                        PaperBadge(sourceTitle(p.source), tone = if (p.source == "bundled") Tone.Neutral else Tone.Info)
                        if (p.running) PaperBadge(tr("plugin.running"), tone = Tone.Success)
                    }
                    Text(
                        listOfNotNull(p.id, p.vendor, p.category).filter { it.isNotEmpty() }.joinToString("  ·  "),
                        style = PaperTheme.typography.small,
                        color = c.textSecondary,
                    )
                }
            }
            if (!p.description.isNullOrEmpty()) Text(p.description!!, style = PaperTheme.typography.body, color = c.textSecondary)
        }
        PaperGroup {
            PaperToggleRow(tr("plugin.enabled"), checked = p.enabled, onCheckedChange = { PluginOps.setEnabled(p, it) })
            if (!p.homepage.isNullOrEmpty()) {
                PaperListRow(tr("plugin.homepage"), icon = "external-link", onClick = { Actions.openExternal(p.homepage!!) })
            }
        }
        if (!p.error.isNullOrEmpty()) PaperAlert(Tone.Danger, tr("plugin.error"), message = p.error)
        p.contributes?.let { Contributes(it) }
        if (p.dependencies.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                PaperSectionHeader(tr("plugin.dependencies"))
                FlowRow(Modifier.padding(horizontal = sp.xs), horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                    for (d in p.dependencies) {
                        val text = d.id + (d.version?.let { " $it" } ?: "") + (if (d.optional) " · " + tr("plugin.optional") else "")
                        val missing = AppModel.data.plugin(d.id) == null
                        PaperBadge(text, tone = if (missing && !d.optional) Tone.Danger else Tone.Neutral)
                    }
                }
            }
        }
        for (s in contributions.settings.filter { it.plugin == p.id && it.fields.isNotEmpty() }) {
            Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                PaperSectionHeader(tr("plugin.settings"))
                PluginSettingsCard(s, showTitle = false)
            }
        }
        for (v in contributions.views(ViewLocation.SETTINGS).filter { it.plugin == p.id }) {
            Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                PaperSectionHeader(v.title)
                PaperCard { PluginViewHost(v, remember(v.key) { ViewContext(v.plugin, v.id) }, scrolls = false) }
            }
        }
        if (p.source != "bundled") {
            PaperButton(
                PluginOps.uninstallTitle(p),
                onClick = { PluginOps.uninstall(p) { AppModel.nav.removeWhere { it is PluginDetailRoute && it.pluginId == p.id && p.bundledVersion == null } } },
                kind = ButtonKind.Secondary,
                icon = "trash-2",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Contributes(cs: ContribSummary) {
    val rows = listOf(
        tr("plugin.c.facets") to cs.facets,
        tr("plugin.c.toolchains") to cs.toolchains,
        tr("plugin.c.runConfigTypes") to cs.runConfigTypes,
        tr("plugin.c.views") to cs.views,
        tr("plugin.c.actions") to cs.actions,
        tr("plugin.c.tools") to cs.tools,
        tr("plugin.c.vcs") to listOfNotNull(cs.vcs?.takeIf { it.isNotEmpty() }),
    ).filter { it.second.isNotEmpty() }
    if (rows.isEmpty() && cs.diagnostics == 0) return
    val sp = PaperTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
        PaperSectionHeader(tr("plugin.contributes"))
        PaperCard {
            for ((title, items) in rows) {
                Column(verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                    Text(title, style = PaperTheme.typography.label, color = PaperTheme.colors.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                        for (i in items) PaperBadge(i)
                    }
                }
            }
            if (cs.diagnostics > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("plugin.c.diagnostics"), style = PaperTheme.typography.label, color = PaperTheme.colors.textSecondary, modifier = Modifier.width(120.dp))
                    Text("${cs.diagnostics}", style = PaperTheme.typography.mono, color = PaperTheme.colors.textPrimary)
                }
            }
        }
    }
}

private fun sourceTitle(s: String) = when (s) {
    "bundled" -> tr("plugin.source.bundled")
    "dev" -> tr("plugin.source.dev")
    else -> tr("plugin.source.user")
}

/**
 * 一个插件的设置表单（plugin.settings；工程「配置」标签与插件详情共用，对照 Mac PluginSettingsCard）。
 * 改过之后标题行出现「保存」；服务器上的值变了且本地没改时跟着更新。
 */
@Composable
fun PluginSettingsCard(info: PluginSettingsInfo, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    var values by remember(info.plugin) { mutableStateOf(FormValues.initial(info.fields, info.values)) }
    var dirty by remember(info.plugin) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(info.values) { if (!dirty) values = FormValues.initial(info.fields, info.values) }
    PaperCard(modifier) {
        if (showTitle || dirty) {
            Row(horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                if (showTitle) {
                    RefIcon(IconSource.parse(AppModel.data.plugin(info.plugin)?.icon, info.plugin), tint = PaperTheme.colors.iconPrimary)
                    Text(info.title, style = PaperTheme.typography.title, color = PaperTheme.colors.textPrimary, modifier = Modifier.weight(1f))
                } else {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
                if (dirty) {
                    PaperButton(tr("common.revert"), onClick = {
                        values = FormValues.initial(info.fields, info.values)
                        dirty = false
                    }, kind = ButtonKind.Text, size = ButtonSize.Small)
                    PaperButton(tr("common.save"), onClick = {
                        busy = true
                        scope.launch {
                            try {
                                AppModel.api().updatePluginSettings(info.plugin, FormValues.normalized(info.fields, values))
                                dirty = false
                                Toasts.success(tr("common.saved"))
                                AppModel.reloadPlugins()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Toasts.error(e, tr("settings.saveFailed"))
                            } finally {
                                busy = false
                            }
                        }
                    }, kind = ButtonKind.Primary, size = ButtonSize.Small, loading = busy)
                }
            }
        }
        FormFields(info.fields, values, { v: Map<String, JsonElement> ->
            values = v
            dirty = true
        })
    }
}
