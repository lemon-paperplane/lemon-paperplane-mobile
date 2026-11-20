package cn.lemonit.paperplane.features.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.features.resources.ResourceStore
import cn.lemonit.paperplane.features.resources.RunResourceText
import cn.lemonit.paperplane.features.runs.CardCell
import cn.lemonit.paperplane.features.runs.RunLinks
import cn.lemonit.paperplane.features.runs.rememberNow
import cn.lemonit.paperplane.features.runs.runStatusText
import cn.lemonit.paperplane.kit.model.ConfigBrief
import cn.lemonit.paperplane.kit.model.ProjectSummary
import cn.lemonit.paperplane.kit.model.Run
import cn.lemonit.paperplane.kit.model.RunState
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.kit.util.IconSource
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperInfoButton
import cn.lemonit.paperplane.ui.components.PaperLongPressArea
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.components.PaperMoreButton
import cn.lemonit.paperplane.ui.components.PaperStatusDot
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.icons.RefIcon
import cn.lemonit.paperplane.ui.shell.LocalWideLayout
import cn.lemonit.paperplane.ui.theme.PaperTheme

// 「启动」标签（对照 Mac Features/Launch/LaunchBoard.swift）：用户从各工程挑出要一起跑的运行配置
// （ProjectSummary.launch），按工程分组，每项可单独运行 / 停止 / 看日志，也可一键全部运行、全部停止。
// 数据存在服务器内核（launch.* 接口），变化经 project.changed 推回来。

/** 工作区里的启动项：按工作区内工程顺序，每个工程按加入顺序 */
internal data class LaunchGroupData(val project: ProjectSummary, val items: List<ConfigBrief>)

internal fun launchGroups(projects: List<ProjectSummary>): List<LaunchGroupData> = projects.mapNotNull { p ->
    val items = p.launch.mapNotNull { id -> p.configs.firstOrNull { it.id == id } }
    if (items.isEmpty()) null else LaunchGroupData(p, items)
}

/** 搜索：工程名、运行配置名、模块名、别名 */
internal fun filterGroups(all: List<LaunchGroupData>, q: String): List<LaunchGroupData> {
    if (q.isEmpty()) return all
    return all.mapNotNull { g ->
        if (g.project.name.contains(q, ignoreCase = true)) return@mapNotNull g
        val items = g.items.filter {
            it.name.contains(q, ignoreCase = true) || it.moduleName.contains(q, ignoreCase = true) ||
                (g.project.launchAlias[it.id] ?: "").contains(q, ignoreCase = true)
        }
        if (items.isEmpty()) null else LaunchGroupData(g.project, items)
    }
}

@Composable
fun LaunchTab() {
    LaunchedEffect(Unit) { ResourceStore.ensureStarted() }
    val wsId = AppModel.nav.workspaceId
    val ws = AppModel.data.workspace(wsId)
    if (ws == null) {
        Box(Modifier.fillMaxSize().background(PaperTheme.colors.bgContent)) {
            PaperEmptyState("layers", tr("mobile.workspace.none"), Modifier.align(Alignment.Center))
        }
        return
    }
    val all = launchGroups(AppModel.data.projects(ws.id))
    val runs = AppModel.runs
    LaunchContent(
        workspaceId = ws.id,
        all = all,
        groups = filterGroups(all, AppModel.nav.search.trim()),
        activeRun = { pid, cid -> runs.activeRun(pid, cid) },
        latestRun = { pid, cid -> runs.latestRun(pid, cid) },
    )
}

/** 启动面板的外观（数据作为参数，截图测试直接画） */
@Composable
internal fun LaunchContent(
    workspaceId: String,
    all: List<LaunchGroupData>,
    groups: List<LaunchGroupData>,
    activeRun: (String, String) -> Run?,
    latestRun: (String, String) -> Run?,
) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    Column(Modifier.fillMaxSize().background(c.bgContent)) {
        if (all.isEmpty()) {
            LaunchEmpty(workspaceId)
            return@Column
        }
        val total = all.sumOf { it.items.size }
        val active = all.sumOf { g -> g.items.count { activeRun(g.project.id, it.id) != null } }
        LaunchToolbar(workspaceId, total, active)
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize()) { PaperEmptyState("search", tr("workspace.noMatch"), Modifier.align(Alignment.Center)) }
            return@Column
        }
        val wide = LocalWideLayout.current
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.fillMaxSize().then(if (wide) Modifier.widthIn(max = 840.dp) else Modifier),
                contentPadding = PaddingValues(start = sp.lg, end = sp.lg, top = sp.xs, bottom = sp.xl),
            ) {
                for (g in groups) {
                    val n = g.items.size
                    item(key = "g:" + g.project.id, contentType = "groupHead") {
                        Box(Modifier.padding(top = sp.lg)) {
                            CardCell(first = true, last = false, dividerInset = 0.dp) {
                                GroupHeader(workspaceId, g, g.items.count { activeRun(g.project.id, it.id) != null })
                            }
                        }
                    }
                    for ((i, cfg) in g.items.withIndex()) {
                        item(key = "i:" + g.project.id + "/" + cfg.id, contentType = "launchItem") {
                            CardCell(first = false, last = i == n - 1, dividerInset = 52.dp) {
                                val a = activeRun(g.project.id, cfg.id)
                                LaunchItemRow(
                                    p = g.project,
                                    cfg = cfg,
                                    active = a,
                                    latest = a ?: latestRun(g.project.id, cfg.id),
                                    showModule = g.items.map { it.moduleId }.distinct().size > 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 没有启动项：图标 + 一句话 + 三种添加方式 + 说明 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LaunchEmpty(workspaceId: String) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    Column(
        Modifier.fillMaxSize().padding(sp.huge),
        verticalArrangement = Arrangement.spacedBy(sp.lg, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PaperIcon("rocket", size = PaperTheme.sizes.iconEmpty, tint = c.iconSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("launch.empty"), style = PaperTheme.typography.body, color = c.textSecondary, textAlign = TextAlign.Center)
            PaperInfoButton(tr("launch.info"))
        }
        Column(Modifier.widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(sp.md)) {
            PaperButton(tr("launch.choose"), onClick = { LaunchSheets.choose(workspaceId) }, kind = ButtonKind.Primary, icon = "list-checks", modifier = Modifier.fillMaxWidth())
            PaperButton(tr("launch.addExecutable"), onClick = { LaunchSheets.addRunnable(workspaceId) }, icon = "file-terminal", modifier = Modifier.fillMaxWidth())
            PaperButton(tr("launch.addCommand"), onClick = { LaunchSheets.addCommand(workspaceId) }, icon = "square-terminal", modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 工具栏：「N 项 · M 个运行中」+ 说明；添加（菜单）、全部停止、全部运行 */
@Composable
private fun LaunchToolbar(workspaceId: String, total: Int, active: Int) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    Row(
        Modifier.fillMaxWidth().padding(start = sp.lg, end = sp.md, top = sp.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.xs),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("launch.summary", total, active), style = PaperTheme.typography.small, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            PaperInfoButton(tr("launch.info"))
        }
        PaperMoreButton(
            {
                listOf(
                    MenuEntry.Item(tr("launch.choose"), icon = "list-checks") { LaunchSheets.choose(workspaceId) },
                    MenuEntry.Item(tr("launch.addExecutable"), icon = "file-terminal") { LaunchSheets.addRunnable(workspaceId) },
                    MenuEntry.Item(tr("launch.addCommand"), icon = "square-terminal") { LaunchSheets.addCommand(workspaceId) },
                )
            },
            icon = "plus",
            contentDescription = tr("mobile.launch.add"),
            tint = c.iconPrimary,
        )
        PaperIconButton("square", tr("launch.stopAll"), onClick = { LaunchActions.stopAll(workspaceId) }, tint = c.danger, enabled = active > 0)
        PaperButton(tr("launch.runAll"), onClick = { LaunchActions.runAll(workspaceId) }, size = ButtonSize.Small, kind = ButtonKind.Primary, icon = "play", enabled = active < total)
    }
}

/** 分组标题：状态点、工程名、路径；运行 / 停止这个工程的启动项、打开工程 */
@Composable
private fun GroupHeader(workspaceId: String, g: LaunchGroupData, active: Int) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val p = g.project
    PaperLongPressArea(
        onClick = { AppModel.nav.openProject(p.id) },
        menu = {
            listOf(
                MenuEntry.Item(tr("launch.runGroup"), icon = "play", enabled = active < g.items.size) { LaunchActions.runAll(workspaceId, p.id) },
                MenuEntry.Item(tr("launch.stopGroup"), icon = "square", enabled = active > 0) { LaunchActions.stopAll(workspaceId, p.id) },
                MenuEntry.Divider,
                MenuEntry.Item(tr("launch.openProject"), icon = "arrow-up-right") { AppModel.nav.openProject(p.id) },
            )
        },
        modifier = Modifier.fillMaxWidth().background(c.bgSubtle),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = sp.lg, end = sp.xxs), verticalAlignment = Alignment.CenterVertically) {
            PaperStatusDot(p.status)
            Spacer(Modifier.size(sp.md))
            Column(Modifier.weight(1f).padding(vertical = sp.sm)) {
                Text(p.name, style = PaperTheme.typography.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Format.abbreviatePath(p.path), style = PaperTheme.typography.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
            PaperIconButton("play", tr("launch.runGroup"), onClick = { LaunchActions.runAll(workspaceId, p.id) }, tint = c.success, enabled = active < g.items.size)
            PaperIconButton("square", tr("launch.stopGroup"), onClick = { LaunchActions.stopAll(workspaceId, p.id) }, tint = c.danger, enabled = active > 0)
        }
    }
}

/** 一个启动项：图标（带最近一次运行的状态点）、名称或别名、模块 / 原名、状态与时长、地址与资源；运行 / 停止 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LaunchItemRow(p: ProjectSummary, cfg: ConfigBrief, active: Run?, latest: Run?, showModule: Boolean) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val alias = p.launchAlias[cfg.id]?.takeIf { it.isNotEmpty() }
    val module = cfg.moduleName.takeIf { showModule && it.isNotEmpty() }
    val subtitle = if (alias != null) listOfNotNull(cfg.name, module).joinToString(" · ") else module
    PaperLongPressArea(
        onClick = { AppModel.nav.openRunConsole(p.id, cfg.id) },
        menu = { itemMenu(p, cfg, active, alias) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = sp.lg, end = sp.xxs, top = sp.xs, bottom = sp.xs), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = sp.md).size(24.dp)) {
                RefIcon(IconSource.parse(cfg.icon), size = PaperTheme.sizes.icon, tint = c.iconPrimary, fallback = "play", modifier = Modifier.align(Alignment.Center))
                if (latest != null) PaperStatusDot(latest.state, Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp), size = 7.dp)
            }
            Spacer(Modifier.size(sp.lg))
            Column(Modifier.weight(1f).padding(vertical = sp.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(alias ?: cfg.name, style = PaperTheme.typography.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                if (subtitle != null) Text(subtitle, style = PaperTheme.typography.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                ItemStatus(latest)
                if (active != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.md), itemVerticalAlignment = Alignment.CenterVertically) {
                        active.urls.firstOrNull()?.let { RunLinks(it, active.projectId, active.configId, runId = active.id, maxLabelWidth = 150.dp) }
                        RunResourceText(active.id, Modifier.heightIn(min = PaperTheme.sizes.touchTarget), fallbackCpu = active.cpu, fallbackRss = active.rssMB)
                    }
                }
            }
            Row(Modifier.align(Alignment.CenterVertically)) {
                if (active != null) {
                    PaperIconButton("rotate-cw", tr("action.restart"), onClick = { Actions.restart(active.id) })
                    PaperIconButton("square", tr("action.stop"), onClick = { Actions.stop(active.id) }, tint = c.danger)
                } else {
                    PaperIconButton("play", tr("action.run"), onClick = { Actions.run(p.id, cfg.id) }, tint = c.success)
                }
            }
        }
    }
}

/** 状态文字：运行中带时长（每秒刷新，只重组这一行）；没跑过显示「未运行」 */
@Composable
private fun ItemStatus(r: Run?) {
    val c = PaperTheme.colors
    val st = PaperTheme.typography.small
    if (r == null) {
        Text(tr("launch.notRun"), style = st, color = c.textTertiary, maxLines = 1)
        return
    }
    val now = if (r.isActive) rememberNow(1000) else System.currentTimeMillis()
    val color = when {
        r.isActive -> c.textSecondary
        r.state == RunState.FAILED -> c.danger
        else -> c.textTertiary
    }
    Text(runStatusText(r, now), style = st, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private fun itemMenu(p: ProjectSummary, cfg: ConfigBrief, active: Run?, alias: String?): List<MenuEntry> = buildList {
    if (active != null) {
        add(MenuEntry.Item(tr("action.restart"), icon = "rotate-cw") { Actions.restart(active.id) })
        add(MenuEntry.Item(tr("action.stop"), icon = "square") { Actions.stop(active.id) })
    } else {
        add(MenuEntry.Item(tr("action.run"), icon = "play") { Actions.run(p.id, cfg.id) })
    }
    add(MenuEntry.Item(tr("launch.logs"), icon = "terminal") { AppModel.nav.openRunConsole(p.id, cfg.id) })
    add(MenuEntry.Item(tr("launch.alias.set").trimEnd('…', '.'), icon = "pencil") { LaunchSheets.editAlias(p.id, cfg.id, cfg.name, alias) })
    add(MenuEntry.Divider)
    add(MenuEntry.Item(tr("launch.remove"), icon = "x", destructive = true) { LaunchActions.setLaunch(p.id, listOf(cfg.id), false) })
}
