package cn.lemonit.paperplane.features.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.ToolchainsRoute
import cn.lemonit.paperplane.features.forms.KeyValueList
import cn.lemonit.paperplane.features.forms.KvItem
import cn.lemonit.paperplane.features.plugins.PluginViewHost
import cn.lemonit.paperplane.kit.ViewContext
import cn.lemonit.paperplane.kit.model.DependencyState
import cn.lemonit.paperplane.kit.model.Module
import cn.lemonit.paperplane.kit.model.Project
import cn.lemonit.paperplane.kit.model.ProjectSummary
import cn.lemonit.paperplane.kit.model.Severity
import cn.lemonit.paperplane.kit.model.ToolchainOverride
import cn.lemonit.paperplane.kit.model.ToolchainSelection
import cn.lemonit.paperplane.kit.model.ViewLocation
import cn.lemonit.paperplane.kit.updateProjectSettings
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperBadge
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperCard
import cn.lemonit.paperplane.ui.components.PaperCodeText
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperInfoButton
import cn.lemonit.paperplane.ui.components.PaperMoreButton
import cn.lemonit.paperplane.ui.components.PaperSectionHeader
import cn.lemonit.paperplane.ui.components.PaperSelectSheet
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.components.PaperStatusDot
import cn.lemonit.paperplane.ui.components.SelectOption
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.RunStates
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.launch

/** 概览：工程状态与技术标签、缺失工具链、问题（可一键修复）、模块（信息、工具链、依赖）、插件 project.section 区块。 */
@Composable
fun OverviewTab(summary: ProjectSummary, project: Project, model: ProjectDetailModel) {
    val sp = PaperTheme.spacing
    val scope = rememberCoroutineScope()
    LaunchedEffect(project.analyzedAt, project.id) { model.loadProblems() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(sp.lg), verticalArrangement = Arrangement.spacedBy(sp.xl)) {
        ProjectHeaderCard(summary)
        if (summary.missingToolchains.isNotEmpty()) {
            PaperAlert(
                Tone.Warning,
                tr("mobile.project.missingToolchains"),
                message = summary.missingToolchains.joinToString("、") { AppModel.data.kindName(it) },
            ) {
                PaperButton(tr("sidebar.toolchains"), onClick = { AppModel.nav.push(ToolchainsRoute) }, kind = ButtonKind.Text, size = ButtonSize.Small)
            }
        }
        val problems = model.problems.sortedByDescending { Severity.rank(it.severity) }
        if (problems.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                PaperSectionHeader(tr("problems.title")) {
                    Text("${problems.size}", style = PaperTheme.typography.small, color = PaperTheme.colors.textTertiary)
                }
                for (d in problems) {
                    androidx.compose.runtime.key(d.stableKey) {
                        ProblemCard(d, project.id) { scope.launch { model.loadProblems() } }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
            PaperSectionHeader(tr("project.modules")) {
                PaperButton(tr("project.reimport"), onClick = { Actions.reimport(project.id) }, kind = ButtonKind.Text, size = ButtonSize.Small, icon = "refresh-cw")
            }
            for (m in project.modules) androidx.compose.runtime.key(m.id) { ModuleCard(project, m, model) }
        }
        for (v in AppModel.data.contributions.views(ViewLocation.PROJECT_SECTION)) {
            for (m in modulesFor(v, project)) {
                androidx.compose.runtime.key(v.key + "|" + m.id) {
                    Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                        PaperSectionHeader(v.title + if (project.modules.size > 1) "  ·  ${m.name}" else "")
                        PaperCard {
                            val ctx = remember(v.key, project.id, m.id) { ViewContext(v.plugin, v.id, project.id, m.id) }
                            PluginViewHost(v, ctx, scrolls = false)
                        }
                    }
                }
            }
        }
    }
}

/** 头部：状态、路径（可复制）、技术标签、正在进行的任务 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectHeaderCard(s: ProjectSummary) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    PaperCard {
        Row(horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalAlignment = Alignment.CenterVertically) {
            val state = s.status.ifEmpty { RunStates.IDLE }
            PaperStatusDot(state, size = 10.dp)
            Text(RunStates.title(state), style = PaperTheme.typography.bodyStrong, color = c.textPrimary)
            if (!s.busy.isNullOrEmpty()) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(sp.xs, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = c.accent)
                    Text(s.busy!!, style = PaperTheme.typography.small, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        PaperCodeText(s.path, small = true)
        if (s.facets.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                for (f in s.facets) PaperBadge(f.name.ifEmpty { f.id })
            }
        }
    }
}

/** 一个模块：名称、技术标签、关键信息、工具链、依赖 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModuleCard(project: Project, module: Module, model: ProjectDetailModel) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val contributions = AppModel.data.contributions
    PaperCard {
        Row(horizontalArrangement = Arrangement.spacedBy(sp.md), verticalAlignment = Alignment.CenterVertically) {
            PaperIcon("box", tint = c.iconSecondary)
            Column(Modifier.weight(1f)) {
                Text(module.name, style = PaperTheme.typography.title, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (module.relDir.isNotEmpty()) Text(module.relDir, style = PaperTheme.typography.monoSmall, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
            if (module.needsSync && !module.synced) {
                PaperButton(tr("project.sync"), onClick = { Actions.sync(project.id, module.id) }, size = ButtonSize.Small, icon = "refresh-cw")
            }
        }
        if (module.facets.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                for (f in module.facets) PaperBadge(contributions.facet(f.id)?.name?.takeIf { it.isNotEmpty() } ?: f.id)
            }
        }
        if (module.info.isNotEmpty()) KeyValueList(module.info.map { KvItem(it.label, it.value) })
        if (module.toolchains.isNotEmpty()) {
            PaperDivider(subtle = true)
            Column {
                for (kind in module.toolchains.keys.sorted()) {
                    module.toolchains[kind]?.let { ToolchainSelectionRow(project, it, model) }
                }
            }
        }
        val deps = module.dependencies
        if (deps != null && deps.state != "none") {
            PaperDivider(subtle = true)
            DependencyRow(project.id, module.id, deps)
        }
    }
}

/** 一类工具链：当前版本（或缺失）、是否指定过；点一下改（自动 / 已安装的版本 / 版本约束） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolchainSelectionRow(project: Project, sel: ToolchainSelection, model: ProjectDetailModel) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    var pick by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = PaperTheme.sizes.rowHeight)
            .clickable(role = Role.Button) { pick = true },
        horizontalArrangement = Arrangement.spacedBy(sp.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(AppModel.data.kindName(sel.kind), style = PaperTheme.typography.small, color = c.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                if (sel.missing) {
                    PaperBadge(tr("toolchain.missing"), tone = Tone.Danger)
                    sel.constraint?.takeIf { it.isNotEmpty() }?.let { Text(it, style = PaperTheme.typography.mono, color = c.textSecondary) }
                } else {
                    Text(sel.version.orEmpty(), style = PaperTheme.typography.mono, color = c.textPrimary)
                    AppModel.data.toolchains.firstOrNull { it.id == sel.toolchainId }?.vendor?.let {
                        Text(it, style = PaperTheme.typography.small, color = c.textTertiary)
                    }
                }
                if (sel.override) PaperBadge(tr("toolchain.override"), tone = Tone.Info)
            }
        }
        sel.source?.takeIf { it.isNotEmpty() }?.let { PaperInfoButton(tr("toolchain.source", it, sel.constraint ?: "-")) }
        PaperIcon("chevrons-up-down", size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
    }
    if (pick) ToolchainOverrideSheet(project, sel.kind, model) { pick = false }
}

private const val AUTO = "__auto"
private const val CUSTOM = "__custom"

/** 工具链覆盖选择（project.settings 的 toolchains） */
@Composable
internal fun ToolchainOverrideSheet(project: Project, kind: String, model: ProjectDetailModel, onDismiss: () -> Unit) {
    val current = project.settings?.toolchains?.get(kind)
    val options = buildList {
        add(SelectOption(AUTO, tr("toolchain.auto")))
        for (t in AppModel.data.toolchains(kind)) add(SelectOption(t.id, t.displayName, detail = t.source))
        current?.constraint?.takeIf { it.isNotEmpty() }?.let { add(SelectOption("c:$it", tr("toolchain.constraintValue", it))) }
        add(SelectOption(CUSTOM, tr("toolchain.customConstraint").trimEnd('…')))
    }
    val selected = current?.id?.takeIf { it.isNotEmpty() } ?: current?.constraint?.takeIf { it.isNotEmpty() }?.let { "c:$it" } ?: AUTO
    PaperSelectSheet(options, selected, onDismiss, title = AppModel.data.kindName(kind)) { v ->
        when {
            v == AUTO -> model.setToolchainOverride(kind, null)
            v == CUSTOM -> Dialogs.input(
                tr("toolchain.customConstraint").trimEnd('…'),
                label = tr("toolchain.constraintLabel"),
                initial = current?.constraint.orEmpty(),
                confirmText = tr("common.save"),
            ) { c ->
                model.project = AppModel.api().updateProjectSettings(project.id, toolchains = mapOf(kind to ToolchainOverride(constraint = c)))
            }
            v.startsWith("c:") -> {}
            else -> model.setToolchainOverride(kind, ToolchainOverride(id = v))
        }
    }
}

/** 依赖状态 + 安装 / 重新安装（··· 里有「清理后重新安装」） */
@Composable
private fun DependencyRow(projectId: String, moduleId: String, deps: DependencyState) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val installing = AppModel.runs.activeTasks.any { it.projectId == projectId && it.moduleId == moduleId && it.kind == "install" }
    Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("deps.title"), style = PaperTheme.typography.small, color = c.textSecondary)
            PaperBadge(depsTitle(deps.state), tone = depsTone(deps.state))
            deps.manager?.let { Text(it, style = PaperTheme.typography.monoSmall, color = c.textSecondary) }
            deps.message?.takeIf { it.isNotEmpty() }?.let { PaperInfoButton(it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalAlignment = Alignment.CenterVertically) {
            PaperButton(
                if (deps.state == "ok") tr("deps.reinstall") else tr("deps.install"),
                onClick = { Actions.installDeps(projectId, moduleId) },
                kind = if (deps.state == "ok") ButtonKind.Secondary else ButtonKind.Primary,
                size = ButtonSize.Small,
                icon = "download",
                loading = installing,
            )
            PaperMoreButton({
                listOf(
                    MenuEntry.Item(tr("deps.cleanInstall"), "eraser") {
                        Dialogs.confirm(tr("deps.cleanInstall"), message = tr("deps.cleanInstall.confirm"), confirmText = tr("deps.cleanInstall")) {
                            Actions.installDeps(projectId, moduleId, clean = true)
                        }
                    },
                )
            }, size = ButtonSize.Small, icon = "chevron-down")
        }
    }
}

internal fun depsTitle(s: String) = when (s) {
    "ok" -> tr("deps.ok")
    "missing" -> tr("deps.missing")
    "outdated" -> tr("deps.outdated")
    else -> tr("deps.unknown")
}

internal fun depsTone(s: String) = when (s) {
    "ok" -> Tone.Success
    "missing" -> Tone.Danger
    "outdated" -> Tone.Warning
    else -> Tone.Neutral
}
