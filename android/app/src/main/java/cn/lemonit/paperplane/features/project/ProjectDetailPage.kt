package cn.lemonit.paperplane.features.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.ProjectRoute
import cn.lemonit.paperplane.core.ProjectTab
import cn.lemonit.paperplane.features.files.ProjectFilesTab
import cn.lemonit.paperplane.features.forms.PaperTabStrip
import cn.lemonit.paperplane.features.forms.StripTab
import cn.lemonit.paperplane.features.plugins.PluginViewHost
import cn.lemonit.paperplane.features.runs.ProjectRunTab
import cn.lemonit.paperplane.features.vcs.ProjectVcsTab
import cn.lemonit.paperplane.kit.ViewContext
import cn.lemonit.paperplane.kit.model.ActionLocation
import cn.lemonit.paperplane.kit.model.Project
import cn.lemonit.paperplane.kit.model.ProjectSummary
import cn.lemonit.paperplane.kit.model.RunState
import cn.lemonit.paperplane.kit.model.ViewInfo
import cn.lemonit.paperplane.kit.model.ViewLocation
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.kit.util.IconSource
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperLoadingPage
import cn.lemonit.paperplane.ui.components.PaperMoreButton
import cn.lemonit.paperplane.ui.components.PaperSelect
import cn.lemonit.paperplane.ui.components.SelectOption
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.shell.PageScaffold
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** 工程详情：概览 / 运行 / 文件 / 版本控制 / 配置 / 插件视图（对照 Mac Features/Project/ProjectDetailPage） */
@Composable
fun ProjectDetailPage(route: ProjectRoute) {
    val model = remember(route.projectId) { ProjectDetailModel(route.projectId) }
    ProjectDetailScreen(route, model)
}

/** 标签的键：内置标签用枚举名，插件视图用 "plugin:<ViewInfo.key>" */
private fun tabKey(tab: ProjectTab, viewKey: String?) = if (tab == ProjectTab.PLUGIN) "plugin:${viewKey.orEmpty()}" else tab.name

/** 页面本体（截图测试直接给预先填好的 model） */
@Composable
fun ProjectDetailScreen(route: ProjectRoute, model: ProjectDetailModel) {
    val pid = route.projectId
    val summary = AppModel.data.projects[pid]
    LaunchedEffect(model) {
        model.reload()
        merge(AppModel.bus.projectChanged.filter { it == pid }.map { }, AppModel.bus.resumed).collectLatest {
            delay(300)
            model.reload()
        }
    }
    var selected by rememberSaveable(route.tab, route.viewKey, route.configId) { mutableStateOf(tabKey(route.tab, route.viewKey)) }
    if (summary == null) {
        // 工程被删除时 nav 会关掉本页；这里只处理还没加载到的情况
        PageScaffold(title = tr("project.missing")) {
            if (!AppModel.data.loaded) PaperLoadingPage() else PaperEmptyState("folder-open", tr("project.missing"))
        }
        return
    }
    val project = model.project
    val pluginTabs = pluginTabs(project, summary)
    val tabs = buildList {
        add(StripTab(ProjectTab.OVERVIEW.name, tr("project.tab.overview"), IconSource.Builtin("layout-grid"), summary.problems, toneOfSeverity(summary.problemSeverity)))
        add(StripTab(ProjectTab.RUN.name, tr("project.tab.run"), IconSource.Builtin("play"), summary.runs.count { RunState.isActive(it.state) }, Tone.Success))
        add(StripTab(ProjectTab.FILES.name, tr("files.tab"), IconSource.Builtin("folder-tree")))
        add(StripTab(ProjectTab.VCS.name, tr("project.tab.vcs"), IconSource.Builtin("git-branch"), summary.vcs?.changes))
        add(StripTab(ProjectTab.CONFIG.name, tr("project.tab.config"), IconSource.Builtin("sliders-horizontal")))
        for (v in pluginTabs) add(StripTab("plugin:${v.key}", v.title, IconSource.parse(v.icon, v.plugin)))
    }
    PageScaffold(
        title = summary.name,
        subtitle = Format.abbreviatePath(summary.path),
        actions = {
            RunControl(summary)
            PaperMoreButton({ projectMenu(summary, project) }, tint = PaperTheme.colors.iconPrimary)
        },
        header = { PaperTabStrip(tabs, selected, onSelect = { selected = it }) },
    ) {
        Box(Modifier.fillMaxSize()) {
            when {
                selected == ProjectTab.RUN.name -> ProjectRunTab(pid, route.configId.takeIf { route.tab == ProjectTab.RUN })
                selected == ProjectTab.FILES.name -> ProjectFilesTab(pid)
                selected == ProjectTab.VCS.name -> ProjectVcsTab(pid)
                project == null -> ProjectLoading(model)
                selected == ProjectTab.CONFIG.name -> ConfigTab(project, model)
                selected.startsWith("plugin:") -> {
                    val v = pluginTabs.firstOrNull { "plugin:${it.key}" == selected }
                    if (v != null) PluginTabContent(v, project) else PaperEmptyState("puzzle", tr("plugin.viewMissing"))
                }
                else -> OverviewTab(summary, project, model)
            }
        }
    }
}

/** 完整工程还没取到：转圈或出错重试 */
@Composable
private fun ProjectLoading(model: ProjectDetailModel) {
    val scope = rememberCoroutineScope()
    val e = model.error
    if (e != null) {
        PaperEmptyState("circle-alert", e, actionText = tr("connection.retry"), actionIcon = "refresh-cw") { scope.launch { model.reload() } }
    } else {
        PaperLoadingPage()
    }
}

private fun toneOfSeverity(s: String?) = when (s) {
    "error" -> Tone.Danger
    "warning" -> Tone.Warning
    else -> Tone.Neutral
}

/** 标题栏右侧：有运行时「停止全部」，否则「运行」默认配置 */
@Composable
private fun RunControl(s: ProjectSummary) {
    val c = PaperTheme.colors
    if (s.hasActiveRun) {
        PaperIconButton("square", tr("card.stopAll"), onClick = { Actions.stopProject(s.id) }, tint = c.danger)
    } else if (s.configs.isNotEmpty()) {
        PaperIconButton("play", tr("action.run"), onClick = { Actions.run(s.id) }, tint = c.success)
    }
}

/** 「更多」菜单：运行 / 停止、重新导入、同步、安装依赖、插件动作、重命名、移除 */
private fun projectMenu(s: ProjectSummary, project: Project?): List<MenuEntry> = buildList {
    if (s.hasActiveRun) {
        add(MenuEntry.Item(tr("card.stopAll"), "square") { Actions.stopProject(s.id) })
    } else if (s.configs.isNotEmpty()) {
        add(MenuEntry.Item(tr("action.run"), "play") { Actions.run(s.id) })
    }
    add(MenuEntry.Divider)
    add(MenuEntry.Item(tr("project.reimport"), "scan-search") { Actions.reimport(s.id) })
    add(MenuEntry.Item(tr("project.sync"), "refresh-cw") { Actions.sync(s.id) })
    add(MenuEntry.Item(tr("deps.install"), "download") { Actions.installDeps(s.id, null) })
    // 手机上没有工具栏：project.toolbar 的动作也放进菜单
    val acts = listOf(ActionLocation.PROJECT_TOOLBAR, ActionLocation.PROJECT_MENU).flatMap { loc ->
        if (project != null) PluginActions.actions(loc, project) else PluginActions.actions(loc, s.id)
    }.distinctBy { it.key }
    if (acts.isNotEmpty()) {
        add(MenuEntry.Divider)
        addAll(PluginActions.menuEntries(acts, s.id))
    }
    add(MenuEntry.Divider)
    add(MenuEntry.Item(tr("project.rename"), "pencil") { Actions.rename(s) })
    add(MenuEntry.Item(tr("project.remove"), "trash-2", destructive = true) { Actions.remove(s) })
}

/** 插件贡献的 project.tab 视图：声明了 facet 的看 facet，没声明的看插件是否参与了这个工程 */
private fun pluginTabs(project: Project?, s: ProjectSummary): List<ViewInfo> {
    val facets = project?.facetIds ?: s.facets.map { it.id }.toSet()
    val plugins = project?.pluginIds ?: s.facets.map { it.plugin }.toSet()
    return AppModel.data.contributions.views(ViewLocation.PROJECT_TAB).filter { v ->
        if (v.facets.isEmpty()) v.plugin in plugins else v.facets.any { it in facets }
    }
}

/** 视图适用的模块 */
internal fun modulesFor(view: ViewInfo, project: Project) = project.modules.filter { m ->
    if (view.facets.isEmpty()) m.facets.any { it.plugin == view.plugin } || m.analysis.containsKey(view.plugin) else m.hasAnyFacet(view.facets)
}

/** 工程标签页里的插件视图：多个模块适用时可切换模块 */
@Composable
private fun PluginTabContent(view: ViewInfo, project: Project) {
    val modules = modulesFor(view, project)
    var moduleId by rememberSaveable(view.key) { mutableStateOf("") }
    val mid = if (modules.any { it.id == moduleId }) moduleId else modules.firstOrNull()?.id.orEmpty()
    Column(Modifier.fillMaxSize()) {
        if (modules.size > 1) {
            PaperSelect(
                modules.map { SelectOption(it.id, it.name, detail = it.relDir.ifEmpty { null }) },
                selected = mid,
                onSelect = { moduleId = it },
                title = tr("project.module"),
                size = ButtonSize.Small,
                modifier = Modifier.fillMaxWidth().padding(start = PaperTheme.spacing.xl, end = PaperTheme.spacing.xl, top = PaperTheme.spacing.md),
            )
        }
        val ctx = remember(view.key, project.id, mid) { ViewContext(view.plugin, view.id, project.id, mid.ifEmpty { null }) }
        androidx.compose.runtime.key(mid) { PluginViewHost(view, ctx, Modifier.weight(1f)) }
    }
}
