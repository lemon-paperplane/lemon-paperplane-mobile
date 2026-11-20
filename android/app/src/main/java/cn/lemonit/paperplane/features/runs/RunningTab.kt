package cn.lemonit.paperplane.features.runs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.features.resources.ResourceMetric
import cn.lemonit.paperplane.features.resources.ResourceStore
import cn.lemonit.paperplane.features.resources.RunResourceInline
import cn.lemonit.paperplane.kit.model.Run
import cn.lemonit.paperplane.kit.model.TaskInfo
import cn.lemonit.paperplane.kit.model.TaskState
import cn.lemonit.paperplane.kit.stopAll
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperBottomSheet
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperInfoButton
import cn.lemonit.paperplane.ui.components.PaperLongPressArea
import cn.lemonit.paperplane.ui.components.PaperProgressBar
import cn.lemonit.paperplane.ui.components.PaperSectionHeader
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.components.PaperStatusDot
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.shell.LocalWideLayout
import cn.lemonit.paperplane.ui.theme.PaperTheme

// 「运行中」标签（对照 Mac MainToolbar 的 RunsPopover / TasksPopover 与「运行中」页顶部汇总）：
// 合计（运行中的工程、合计内存、合计 CPU）、全部运行中的服务、后台任务。按顶栏搜索过滤。

@Composable
fun RunningTab() {
    LaunchedEffect(Unit) { ResourceStore.ensureStarted() }
    val q = AppModel.nav.search.trim()
    val runs = AppModel.runs.activeRuns.filter { r ->
        q.isEmpty() || r.name.contains(q, ignoreCase = true) || projectName(r.projectId).contains(q, ignoreCase = true) ||
            r.urls.any { it.contains(q, ignoreCase = true) }
    }
    val tasks = AppModel.runs.recentTasks.filter { t -> q.isEmpty() || t.title.contains(q, ignoreCase = true) }
    RunningContent(runs, tasks, ResourceStore.totals, searching = q.isNotEmpty())
}

/** 「运行中」的外观（数据作为参数，截图测试直接画） */
@Composable
internal fun RunningContent(runs: List<Run>, tasks: List<TaskInfo>, totals: ResourceStore.Totals, searching: Boolean) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    if (runs.isEmpty() && tasks.isEmpty()) {
        Box(Modifier.fillMaxSize().background(c.bgContent)) {
            PaperEmptyState(if (searching) "search" else "activity", if (searching) tr("workspace.noMatch") else tr("running.empty"), Modifier.align(Alignment.Center))
        }
        return
    }
    val wide = LocalWideLayout.current
    Box(Modifier.fillMaxSize().background(c.bgContent), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.fillMaxSize().then(if (wide) Modifier.widthIn(max = 760.dp) else Modifier),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(sp.lg),
        ) {
            item(key = "summary") { RunningSummary(totals) }
            item(key = "runsHeader") {
                PaperSectionHeader(
                    tr("toolbar.runs") + if (runs.isNotEmpty()) "  ${runs.size}" else "",
                    modifier = Modifier.padding(top = sp.xl, bottom = sp.sm),
                ) {
                    if (runs.isNotEmpty() && !searching) {
                        PaperButton(tr("menu.stopAll"), onClick = { confirmStopAll(runs.size) }, size = ButtonSize.Small, icon = "square")
                    }
                }
            }
            if (runs.isEmpty()) {
                item(key = "noRuns") {
                    Surface {
                        Text(
                            tr("runs.none"),
                            style = PaperTheme.typography.body,
                            color = c.textSecondary,
                            modifier = Modifier.padding(horizontal = sp.xl, vertical = sp.lg),
                        )
                    }
                }
            } else {
                cardGroup(runs, key = { "run:" + it.id }) { r -> RunRow(r) }
            }
            if (tasks.isNotEmpty()) {
                item(key = "tasksHeader") {
                    PaperSectionHeader(tr("toolbar.tasks"), modifier = Modifier.padding(top = sp.xl, bottom = sp.sm).heightIn(min = PaperTheme.sizes.controlHeightSmall))
                }
                cardGroup(tasks, key = { "task:" + it.id }) { t -> TaskRow(t) }
            }
        }
    }
}

private fun confirmStopAll(n: Int) {
    Dialogs.confirm(title = tr("mobile.running.stopAll.title", n), confirmText = tr("menu.stopAll"), danger = true) {
        AppModel.api().stopAll()
    }
}

/** 卡片底框（列表分组：首尾圆角，中间分隔线） */
@Composable
private fun Surface(content: @Composable () -> Unit) {
    val c = PaperTheme.colors
    val shape = RoundedCornerShape(PaperTheme.radius.lg)
    Box(Modifier.fillMaxWidth().clip(shape).background(c.bgSurface, shape).border(PaperTheme.sizes.border, c.border, shape)) { content() }
}

/** 汇总：运行中的工程、合计内存、合计 CPU */
@Composable
private fun RunningSummary(t: ResourceStore.Totals) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    Surface {
        Row(Modifier.fillMaxWidth().padding(start = sp.xl, end = sp.xs, top = sp.lg, bottom = sp.lg), verticalAlignment = Alignment.CenterVertically) {
            Stat(tr("resources.runningProjects"), "${t.projects}", c.textPrimary, Modifier.weight(1f))
            Stat(tr("resources.totalMemory"), Format.memory(t.rssMB), ResourceMetric.MEMORY.color(c), Modifier.weight(1f))
            Stat(tr("resources.totalCPU"), Format.cpu(t.cpu), ResourceMetric.CPU.color(c), Modifier.weight(1f))
            PaperInfoButton(tr("resources.info"))
        }
    }
}

@Composable
private fun Stat(title: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = PaperTheme.typography.caption, color = PaperTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = PaperTheme.typography.heading, color = color, maxLines = 1)
    }
}

/** 一个运行中的服务：状态、名称、工程、地址、CPU / 内存、看日志、停止。点按看日志，长按出菜单。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunRow(r: Run) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    PaperLongPressArea(
        onClick = { AppModel.nav.openRunConsole(r.projectId, r.configId) },
        menu = { runRowMenu(r) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = sp.xl, end = sp.xs, top = sp.sm, bottom = sp.xs), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.sm)) {
                    PaperStatusDot(r.state)
                    Text(r.name, style = PaperTheme.typography.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(projectName(r.projectId), style = PaperTheme.typography.small, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(sp.sm), itemVerticalAlignment = Alignment.CenterVertically) {
                    val url = r.urls.firstOrNull()
                    if (url != null) {
                        RunLinks(url, r.projectId, r.configId, runId = r.id)
                    } else {
                        RunStatusLabel(r, Modifier.heightIn(min = PaperTheme.sizes.touchTarget), showDot = false)
                    }
                    RunResourceInline(r.id, r.startedAt)
                }
            }
            PaperIconButton("scroll-text", tr("runs.openLog"), onClick = { AppModel.nav.openRunConsole(r.projectId, r.configId) })
            PaperIconButton("square", tr("action.stop"), onClick = { Actions.stop(r.id) }, tint = c.danger)
        }
    }
}

private fun runRowMenu(r: Run): List<MenuEntry> = buildList {
    add(MenuEntry.Item(tr("runs.openLog"), icon = "scroll-text") { AppModel.nav.openRunConsole(r.projectId, r.configId) })
    add(MenuEntry.Item(tr("launch.openProject"), icon = "arrow-up-right") { AppModel.nav.openProject(r.projectId) })
    add(MenuEntry.Divider)
    add(MenuEntry.Item(tr("action.restart"), icon = "rotate-cw") { Actions.restart(r.id) })
    add(MenuEntry.Item(tr("action.stop"), icon = "square", destructive = true) { Actions.stop(r.id) })
    val url = r.urls.firstOrNull()
    if (url != null) {
        add(MenuEntry.Divider)
        addAll(RunLinkActions.menuEntries(url, r.projectId, r.configId, r.id).filterNot { it is MenuEntry.Divider })
    }
}

/** 一个后台任务：状态图标、标题、进度、说明 / 错误；看日志、取消 */
@Composable
private fun TaskRow(t: TaskInfo) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    PaperLongPressArea(onClick = { TaskLog.show(t.id) }, menu = null, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = sp.xl, end = sp.xs, top = sp.xs, bottom = sp.xs), verticalAlignment = Alignment.CenterVertically) {
            TaskStateIcon(t)
            Spacer(Modifier.padding(start = sp.md))
            Column(Modifier.weight(1f).padding(vertical = sp.sm), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                Text(t.title, style = PaperTheme.typography.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t.isRunning) PaperProgressBar(if (t.percent < 0) null else (t.percent / 100).toFloat())
                val m = if (t.isRunning) t.message else (t.error ?: t.message)
                if (!m.isNullOrEmpty()) {
                    Text(m, style = PaperTheme.typography.small, color = if (t.state == TaskState.FAILED) c.danger else c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            PaperIconButton("scroll-text", tr("task.viewLog"), onClick = { TaskLog.show(t.id) })
            if (t.isRunning) PaperIconButton("x", tr("common.cancel"), onClick = { Actions.cancelTask(t.id) }, tint = c.iconSecondary)
        }
    }
}

@Composable
internal fun TaskStateIcon(t: TaskInfo) {
    val c = PaperTheme.colors
    val size = PaperTheme.sizes.iconSmall
    when (t.state) {
        TaskState.RUNNING -> PaperSpinner(size = size, tint = c.accent)
        TaskState.SUCCEEDED -> PaperIcon("circle-check", size = size, tint = c.success)
        TaskState.FAILED -> PaperIcon("circle-x", size = size, tint = c.danger)
        else -> PaperIcon("circle-minus", size = size, tint = c.iconSecondary)
    }
}

/** 任务日志（失败提示里的「查看日志」、运行中页的任务行）：底部面板显示任务的进度、诊断与控制台 */
object TaskLog {
    fun show(taskId: String) {
        Dialogs.present(id = "tasklog-$taskId") { dismiss -> TaskLogSheet(taskId, dismiss) }
    }
}

@Composable
private fun TaskLogSheet(taskId: String, onDismiss: () -> Unit) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val task = AppModel.runs.tasks[taskId]
    val height = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    PaperBottomSheet(onDismiss = onDismiss, title = task?.title ?: tr("task.log"), scrollable = false, edgeToEdge = true) {
        if (task != null && (task.isRunning || !task.error.isNullOrEmpty() || task.diagnoses.isNotEmpty())) {
            Column(Modifier.fillMaxWidth().padding(horizontal = sp.xl, vertical = sp.sm), verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.md)) {
                    TaskStateIcon(task)
                    val m = if (task.isRunning) task.message else (task.error ?: task.message)
                    Text(
                        m.orEmpty(),
                        style = PaperTheme.typography.small,
                        color = if (task.state == TaskState.FAILED) c.danger else c.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (task.isRunning) PaperButton(tr("common.cancel"), onClick = { Actions.cancelTask(task.id) }, size = ButtonSize.Small)
                }
                if (task.isRunning) PaperProgressBar(if (task.percent < 0) null else (task.percent / 100).toFloat())
                for (d in task.diagnoses.take(3)) DiagnosisRow(d, taskId = task.id, projectId = task.projectId)
            }
        }
        PaperDivider(subtle = true)
        ConsoleView(taskId, Modifier.fillMaxWidth().height(height))
    }
}
