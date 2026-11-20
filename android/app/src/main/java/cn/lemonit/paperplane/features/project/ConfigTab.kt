package cn.lemonit.paperplane.features.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.features.forms.KeyValueEditor
import cn.lemonit.paperplane.features.plugins.PluginSettingsCard
import cn.lemonit.paperplane.kit.deleteRunConfig
import cn.lemonit.paperplane.kit.launchAdd
import cn.lemonit.paperplane.kit.launchRemove
import cn.lemonit.paperplane.kit.model.Project
import cn.lemonit.paperplane.kit.model.RunConfig
import cn.lemonit.paperplane.kit.resetRunConfig
import cn.lemonit.paperplane.kit.setDefaultRunConfig
import cn.lemonit.paperplane.kit.util.IconSource
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperBadge
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperCard
import cn.lemonit.paperplane.ui.components.PaperGroup
import cn.lemonit.paperplane.ui.components.PaperGroupDefaults
import cn.lemonit.paperplane.ui.components.PaperListRow
import cn.lemonit.paperplane.ui.components.PaperSectionHeader
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.icons.RefIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 配置：运行配置（新建 / 编辑 / 删除 / 重置 / 设为默认 / 加到启动）、工程环境变量、工具链覆盖、相关插件设置。 */
@Composable
fun ConfigTab(project: Project, model: ProjectDetailModel) {
    val sp = PaperTheme.spacing
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(sp.lg), verticalArrangement = Arrangement.spacedBy(sp.xl)) {
        RunConfigsSection(project)
        EnvSection(project, model)
        ToolchainsSection(project, model)
        val relevant = AppModel.data.contributions.settings.filter { it.plugin in project.pluginIds && it.fields.isNotEmpty() }
        if (relevant.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                PaperSectionHeader(tr("config.pluginSettings"), info = tr("config.pluginSettings.info"))
                for (s in relevant) androidx.compose.runtime.key(s.plugin) { PluginSettingsCard(s) }
            }
        }
    }
}

// ---------------------------------------------------------------- 运行配置

@Composable
private fun RunConfigsSection(project: Project) {
    val c = PaperTheme.colors
    val contributions = AppModel.data.contributions
    val configs = project.allRunConfigs
    Column(verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm)) {
        PaperSectionHeader(tr("runconfig.title")) {
            PaperButton(tr("runconfig.new"), onClick = { RunConfigEditRoute.openNew(project) }, kind = ButtonKind.Text, size = ButtonSize.Small, icon = "plus")
        }
        if (configs.isEmpty()) {
            PaperCard { Text(tr("card.noConfig"), style = PaperTheme.typography.body, color = c.textSecondary) }
            return@Column
        }
        PaperGroup(dividerInset = PaperGroupDefaults.IconRowInset) {
            for (cfg in configs) {
                val type = contributions.runConfigType(cfg.plugin, cfg.type)
                val isDefault = cfg.id == project.defaultConfigId
                val inLaunch = cfg.id in project.launch
                val module = project.module(cfg.moduleId)
                PaperListRow(
                    cfg.name,
                    subtitle = listOfNotNull(type?.name ?: cfg.type, module?.name?.takeIf { project.modules.size > 1 }).joinToString("  ·  "),
                    leading = { RefIcon(IconSource.parse(type?.icon, cfg.plugin ?: type?.plugin), fallback = "play", tint = c.iconPrimary) },
                    trailing = {
                        if (isDefault) PaperBadge(tr("runconfig.default"), tone = Tone.Info)
                        if (cfg.edited) PaperBadge(tr("runconfig.edited"))
                        if (cfg.stale) PaperIcon("triangle-alert", size = PaperTheme.sizes.iconSmall, tint = c.warning)
                        if (inLaunch) PaperIcon("rocket", size = PaperTheme.sizes.iconSmall, tint = c.accent)
                    },
                    showChevron = true,
                    onClick = { RunConfigEditRoute.openEdit(project, cfg) },
                    menu = { RunConfigMenu.entries(project, cfg) },
                )
            }
        }
    }
}

/** 运行配置的长按菜单（配置标签与运行配置编辑页共用） */
internal object RunConfigMenu {
    fun entries(project: Project, c: RunConfig, inEditor: Boolean = false, onDeleted: () -> Unit = {}): List<MenuEntry> {
        val isDefault = c.id == project.defaultConfigId
        val inLaunch = c.id in project.launch
        return buildList {
            add(MenuEntry.Item(tr("action.run"), "play") { Actions.run(project.id, c.id) })
            if (!inEditor) add(MenuEntry.Item(tr("runconfig.edit"), "pencil") { RunConfigEditRoute.openEdit(project, c) })
            add(MenuEntry.Item(tr("runconfig.duplicate"), "copy") { RunConfigEditRoute.openDuplicate(project, c) })
            add(MenuEntry.Item(tr("runconfig.setDefault"), "pin", enabled = !isDefault) { setDefault(project.id, c.id) })
            add(MenuEntry.Item(if (inLaunch) tr("launch.remove") else tr("launch.add"), "rocket") { setLaunch(project.id, c.id, !inLaunch) })
            if (c.edited && c.source != "user") add(MenuEntry.Item(tr("runconfig.reset"), "rotate-ccw") { reset(project.id, c.id) })
            add(MenuEntry.Divider)
            add(MenuEntry.Item(tr("common.delete"), "trash-2", destructive = true) { delete(project.id, c, onDeleted) })
        }
    }

    fun setDefault(projectId: String, configId: String) = AppModel.perform { api ->
        api.setDefaultRunConfig(projectId, configId)
        AppModel.bus.projectChanged.tryEmit(projectId)
    }

    fun setLaunch(projectId: String, configId: String, on: Boolean) = AppModel.perform { api ->
        if (on) api.launchAdd(projectId, listOf(configId)) else api.launchRemove(projectId, listOf(configId))
        AppModel.bus.projectChanged.tryEmit(projectId)
        Toasts.success(if (on) tr("mobile.launch.added") else tr("mobile.launch.removed"))
    }

    fun reset(projectId: String, configId: String) = AppModel.perform { api ->
        api.resetRunConfig(projectId, configId)
        AppModel.bus.projectChanged.tryEmit(projectId)
    }

    fun delete(projectId: String, c: RunConfig, after: () -> Unit = {}) {
        Dialogs.confirm(tr("runconfig.delete.title", c.name), confirmText = tr("common.delete"), danger = true) {
            AppModel.api().deleteRunConfig(projectId, c.id)
            AppModel.bus.projectChanged.tryEmit(projectId)
            after()
        }
    }
}

// ---------------------------------------------------------------- 环境变量

@Composable
private fun EnvSection(project: Project, model: ProjectDetailModel) {
    val saved = remember(project.settings?.env) { project.settings?.env.orEmpty().toList().sortedBy { it.first } }
    var pairs by remember(saved) { mutableStateOf(saved) }
    var dirty by remember(saved) { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm)) {
        PaperSectionHeader(tr("config.env"), info = tr("config.env.info")) {
            if (dirty) {
                PaperButton(tr("common.revert"), onClick = {
                    pairs = saved
                    dirty = false
                    version++
                }, kind = ButtonKind.Text, size = ButtonSize.Small)
                PaperButton(tr("common.save"), onClick = {
                    busy = true
                    scope.launch {
                        try {
                            model.saveEnv(pairs.filter { it.first.isNotEmpty() }.toMap())
                            dirty = false
                            Toasts.success(tr("common.saved"))
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
        PaperCard {
            KeyValueEditor(pairs, {
                pairs = it
                dirty = true
            }, resetKey = saved to version)
        }
    }
}

// ---------------------------------------------------------------- 工具链覆盖

@Composable
private fun ToolchainsSection(project: Project, model: ProjectDetailModel) {
    val kinds = remember(project) {
        val s = sortedSetOf<String>()
        for (m in project.modules) {
            s.addAll(m.toolchains.keys)
            m.requirements.forEach { s.add(it.kind) }
        }
        project.settings?.toolchains?.keys?.let { s.addAll(it) }
        s.toList()
    }
    if (kinds.isEmpty()) return
    var pick by remember { mutableStateOf<String?>(null) }
    val c = PaperTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm)) {
        PaperSectionHeader(tr("config.toolchains"), info = tr("config.toolchains.info"))
        PaperGroup(dividerInset = PaperGroupDefaults.IconRowInset) {
            for (k in kinds) {
                val kindInfo = AppModel.data.toolchainKinds.firstOrNull { it.kind == k }
                val o = project.settings?.toolchains?.get(k)
                val overrideTitle = when {
                    !o?.id.isNullOrEmpty() -> AppModel.data.toolchains.firstOrNull { it.id == o?.id }?.displayName ?: o?.id
                    !o?.constraint.isNullOrEmpty() -> tr("toolchain.constraintValue", o?.constraint)
                    else -> tr("toolchain.auto")
                }
                PaperListRow(
                    AppModel.data.kindName(k),
                    subtitle = selectedText(project, k),
                    subtitleMono = true,
                    leading = { RefIcon(IconSource.parse(kindInfo?.icon, kindInfo?.plugin), fallback = "boxes", tint = c.iconPrimary) },
                    value = overrideTitle,
                    showChevron = true,
                    onClick = { pick = k },
                )
            }
        }
    }
    pick?.let { k -> ToolchainOverrideSheet(project, k, model) { pick = null } }
}

/** 各模块实际选中的版本（有缺失时显示「缺失」） */
private fun selectedText(project: Project, kind: String): String {
    val sels = project.modules.mapNotNull { it.toolchains[kind] }
    if (sels.any { it.missing }) return tr("toolchain.missing")
    return sels.mapNotNull { it.version?.takeIf { v -> v.isNotEmpty() } }.toSortedSet().joinToString(", ")
}
