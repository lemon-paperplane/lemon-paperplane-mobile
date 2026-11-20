package cn.lemonit.paperplane.features.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.features.project.PluginActions
import cn.lemonit.paperplane.features.resources.RunResourceInline
import cn.lemonit.paperplane.features.runs.DiagnosisRow
import cn.lemonit.paperplane.features.runs.RelativeTime
import cn.lemonit.paperplane.features.runs.RunLinks
import cn.lemonit.paperplane.features.runs.RunSelection
import cn.lemonit.paperplane.features.runs.rememberNow
import cn.lemonit.paperplane.kit.model.ActionLocation
import cn.lemonit.paperplane.kit.model.Diagnosis
import cn.lemonit.paperplane.kit.model.FacetBadge
import cn.lemonit.paperplane.kit.model.LastRun
import cn.lemonit.paperplane.kit.model.ProjectSummary
import cn.lemonit.paperplane.kit.model.RunState
import cn.lemonit.paperplane.kit.model.Severity
import cn.lemonit.paperplane.kit.model.VCSBrief
import cn.lemonit.paperplane.kit.projectProblems
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.kit.util.IconSource
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperBadge
import cn.lemonit.paperplane.ui.components.PaperBottomSheet
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperLongPressArea
import cn.lemonit.paperplane.ui.components.PaperMoreButton
import cn.lemonit.paperplane.ui.components.PaperSelect
import cn.lemonit.paperplane.ui.components.PaperSelectSheet
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.components.PaperStatusDot
import cn.lemonit.paperplane.ui.components.SelectOption
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.icons.RefIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.RunStates
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException

// 工程卡片（对照 Mac Features/Workspace/ProjectCard.swift）：状态点、名称、路径、技术标签、分析信息、
// 运行配置选择与运行 / 停止 / 重启、运行地址与资源（没在运行时「未运行 · 上次运行 …」占同一行）、
// 版本控制简况、正在进行的任务、问题（缺工具链 / 依赖未装 / 诊断，点开可一键修复）。点卡片打开工程，长按出菜单。

/**
 * @param showWorkspace 路径前面带上工作区名（跨工作区的列表用）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectCard(p: ProjectSummary, modifier: Modifier = Modifier, showWorkspace: Boolean = false) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val shape = RoundedCornerShape(PaperTheme.radius.lg)
    PaperLongPressArea(
        onClick = { AppModel.nav.openProject(p.id) },
        menu = { CardMenu.entries(p) },
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.bgSurface, shape)
            .border(PaperTheme.sizes.border, c.border, shape),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = sp.lg, end = sp.xs, top = sp.xs, bottom = sp.sm)) {
            CardHeader(p, showWorkspace)
            Column(Modifier.padding(end = sp.md), verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                if (p.facets.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                        for (f in p.facets.take(6)) FacetChip(f)
                    }
                }
                if (p.info.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.md), verticalArrangement = Arrangement.spacedBy(sp.xxs)) {
                        for (i in p.info.take(3)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
                                Text(i.label, style = PaperTheme.typography.small, color = c.textTertiary, maxLines = 1)
                                Text(i.value, style = PaperTheme.typography.small, color = c.textSecondary, maxLines = 1)
                            }
                        }
                    }
                }
                PaperDivider(subtle = true, modifier = Modifier.padding(top = sp.xs))
            }
            RunArea(p)
            Column(Modifier.padding(end = sp.md), verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                p.vcs?.takeIf { it.branch.isNotEmpty() }?.let { VcsLine(it) }
                p.busy?.takeIf { it.isNotEmpty() }?.let { busy ->
                    Row(horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalAlignment = Alignment.CenterVertically) {
                        PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = c.accent)
                        Text(busy, style = PaperTheme.typography.small, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardHeader(p: ProjectSummary, showWorkspace: Boolean) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    Row(verticalAlignment = Alignment.Top) {
        PaperStatusDot(p.status, Modifier.padding(top = 18.dp))
        Spacer(Modifier.padding(start = sp.md))
        Column(Modifier.weight(1f).padding(top = sp.md, bottom = sp.sm), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(p.name, style = PaperTheme.typography.title, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val ws = if (showWorkspace) AppModel.data.workspace(p.workspaceId)?.name else null
            Text(
                listOfNotNull(ws, Format.abbreviatePath(p.path)).joinToString(" · "),
                style = PaperTheme.typography.small,
                color = c.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        ProblemsIndicator(p, Modifier.padding(top = sp.xxs))
        PaperMoreButton({ CardMenu.entries(p) })
    }
}

/** 技术标签：插件图标 + 名称 */
@Composable
internal fun FacetChip(f: FacetBadge) {
    val c = PaperTheme.colors
    Row(
        Modifier
            .height(24.dp)
            .background(c.neutralSubtle, RoundedCornerShape(PaperTheme.radius.sm))
            .padding(horizontal = PaperTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RefIcon(IconSource.parse(f.icon, f.plugin), size = 14.dp, tint = c.textSecondary, fallback = "box")
        Text(f.name, style = PaperTheme.typography.caption, color = c.textSecondary, maxLines = 1)
    }
}

/** 运行区：配置选择 + 运行控制；下一行是地址与资源，没在运行时是「未运行 · 上次运行 …」（同一高度，启停时卡片不跳） */
@Composable
private fun RunArea(p: ProjectSummary) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    if (p.configs.isEmpty()) {
        Box(Modifier.heightIn(min = PaperTheme.sizes.touchTarget).padding(end = sp.md), contentAlignment = Alignment.CenterStart) {
            Text(tr("card.noConfig"), style = PaperTheme.typography.small, color = c.textTertiary)
        }
        return
    }
    val cid = RunSelection.configId(p)
    val active = p.runs.firstOrNull { it.configId == cid && RunState.isActive(it.state) }
    Row(Modifier.padding(top = sp.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
        val multiModule = p.configs.map { it.moduleId }.distinct().size > 1
        // 选择框宽度不随启停变化：右边总是留两个按钮的位置
        PaperSelect(
            options = p.configs.map { cfg ->
                SelectOption(
                    cfg.id,
                    cfg.name,
                    icon = (IconSource.parse(cfg.icon) as? IconSource.Builtin)?.name,
                    detail = cfg.moduleName.takeIf { multiModule && it.isNotEmpty() },
                )
            },
            selected = cid,
            onSelect = { RunSelection.select(p.id, it) },
            title = tr("runconfig.title"),
            modifier = Modifier.weight(1f),
        )
        if (active != null) {
            PaperIconButton("rotate-cw", tr("action.restart"), onClick = { Actions.restart(active.id) })
            PaperIconButton("square", tr("action.stop"), onClick = { Actions.stop(active.id) }, tint = c.danger)
        } else {
            Spacer(Modifier.size(PaperTheme.sizes.iconButton))
            PaperIconButton("play", tr("action.run"), onClick = { Actions.run(p.id, cid) }, tint = c.success)
        }
    }
    val current = p.runs.firstOrNull { it.configId == cid } ?: p.runs.firstOrNull()
    Row(Modifier.heightIn(min = PaperTheme.sizes.touchTarget).padding(end = sp.xs), verticalAlignment = Alignment.CenterVertically) {
        if (current != null && RunState.isActive(current.state)) {
            val url = current.urls.firstOrNull()
            Box(Modifier.weight(1f)) {
                if (url != null) {
                    RunLinks(url, p.id, current.configId, runId = current.id, maxLabelWidth = 150.dp)
                } else {
                    Text(RunStates.title(current.state), style = PaperTheme.typography.small, color = c.textSecondary)
                }
            }
            RunResourceInline(current.id, AppModel.runs.runs[current.id]?.startedAt)
        } else {
            IdleRunLine(p.lastRun, Modifier.weight(1f))
        }
    }
}

/** 没在运行：未运行 · 上次运行 3 小时前；上次异常退出用红色写退出码。每分钟刷新相对时间。 */
@Composable
private fun IdleRunLine(lastRun: LastRun?, modifier: Modifier = Modifier) {
    val c = PaperTheme.colors
    val now = rememberNow(60_000)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        PaperIcon("power", size = 14.dp, tint = c.iconSecondary)
        Text(tr("card.idle"), style = PaperTheme.typography.small, color = c.textTertiary, maxLines = 1)
        val at = lastRun?.endedAt
        if (lastRun != null && at != null) {
            val ago = RelativeTime.short(at, java.time.Instant.ofEpochMilli(now))
            Text("·", style = PaperTheme.typography.small, color = c.textTertiary)
            val (text, color) = when {
                lastRun.failed && lastRun.exitCode != 0 -> tr("card.lastFailed", ago, lastRun.exitCode) to c.danger
                lastRun.failed -> tr("card.lastStartFailed", ago) to c.danger
                else -> tr("card.lastRun", ago) to c.textTertiary
            }
            Text(text, style = PaperTheme.typography.small, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 版本控制简况：分支、领先 / 落后、改动数、冲突 */
@Composable
private fun VcsLine(v: VCSBrief) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val st = PaperTheme.typography.small
    Row(horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalAlignment = Alignment.CenterVertically) {
        PaperIcon("git-branch", size = 14.dp, tint = c.iconSecondary)
        Text(v.branch, style = st, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
        if (v.ahead > 0) Row(verticalAlignment = Alignment.CenterVertically) {
            PaperIcon("arrow-up", size = 12.dp, tint = c.textSecondary)
            Text("${v.ahead}", style = st, color = c.textSecondary)
        }
        if (v.behind > 0) Row(verticalAlignment = Alignment.CenterVertically) {
            PaperIcon("arrow-down", size = 12.dp, tint = c.textSecondary)
            Text("${v.behind}", style = st, color = c.textSecondary)
        }
        if (v.changes > 0) Text(tr("card.changes", v.changes), style = st, color = c.textTertiary, maxLines = 1)
        if (v.conflicts > 0) PaperBadge(tr("card.conflicts", v.conflicts), tone = Tone.Danger)
    }
}

// ---------------------------------------------------------------- 问题

/** 问题数：静态问题 + 缺工具链 + 依赖未装 / 需要更新 */
internal fun problemCount(p: ProjectSummary): Int =
    p.problems + p.missingToolchains.size + if (p.dependencies == "missing" || p.dependencies == "outdated") 1 else 0

/** 问题提示：点开列出问题并可一键修复 */
@Composable
private fun ProblemsIndicator(p: ProjectSummary, modifier: Modifier = Modifier) {
    val count = problemCount(p)
    if (count <= 0) return
    val c = PaperTheme.colors
    val sev = p.problemSeverity ?: if (p.missingToolchains.isEmpty()) Severity.WARNING else Severity.ERROR
    val tone = c.tone(Tone.ofSeverity(sev))
    var open by remember { mutableStateOf(false) }
    Box(
        modifier
            .heightIn(min = PaperTheme.sizes.touchTarget)
            .clip(RoundedCornerShape(PaperTheme.radius.md))
            .clickable(role = Role.Button) { open = true }
            .padding(horizontal = PaperTheme.spacing.xxs),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(28.dp)
                .background(tone.bg, RoundedCornerShape(PaperTheme.radius.sm))
                .padding(horizontal = PaperTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaperIcon(if (sev == Severity.ERROR) "circle-alert" else "triangle-alert", size = 16.dp, tint = tone.fg)
            Text("${maxOf(count, p.problems)}", style = PaperTheme.typography.small, color = tone.fg)
        }
    }
    if (open) ProblemsSheet(p.id, onDismiss = { open = false })
}

/** 问题面板（project.problems）：每条可展开详情、一键修复 */
@Composable
internal fun ProblemsSheet(projectId: String, onDismiss: () -> Unit) {
    var problems by remember { mutableStateOf<List<Diagnosis>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(projectId) {
        try {
            problems = AppModel.api().projectProblems(projectId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        }
    }
    val c = PaperTheme.colors
    PaperBottomSheet(onDismiss = onDismiss, title = tr("problems.title")) { close ->
        val list = problems
        when {
            error != null -> Text(error.orEmpty(), style = PaperTheme.typography.body, color = c.danger)
            list == null -> Box(Modifier.fillMaxWidth().padding(PaperTheme.spacing.xl), contentAlignment = Alignment.Center) { PaperSpinner() }
            list.isEmpty() -> Text(tr("problems.none"), style = PaperTheme.typography.body, color = c.textSecondary)
            else -> for (d in list) DiagnosisRow(d, projectId = projectId, onFixed = close)
        }
    }
}

// ---------------------------------------------------------------- 菜单

/** 卡片长按 / 更多菜单（对照 Mac CardMenu；手机上没有子菜单，「移到工作区」弹出选择面板） */
object CardMenu {
    fun entries(p: ProjectSummary): List<MenuEntry> {
        val model = AppModel
        val out = ArrayList<MenuEntry>()
        out += MenuEntry.Item(tr("card.open"), icon = "arrow-right") { model.nav.openProject(p.id) }
        out += MenuEntry.Divider
        val cid = RunSelection.configId(p)
        if (cid != null && p.runs.none { it.configId == cid && RunState.isActive(it.state) }) {
            out += MenuEntry.Item(tr("action.run"), icon = "play") { Actions.run(p.id, cid) }
        }
        if (p.hasActiveRun) out += MenuEntry.Item(tr("card.stopAll"), icon = "square") { Actions.stopProject(p.id) }
        out += MenuEntry.Divider
        out += MenuEntry.Item(tr("project.reimport"), icon = "refresh-cw") { Actions.reimport(p.id) }
        out += MenuEntry.Item(tr("project.sync"), icon = "rotate-ccw") { Actions.sync(p.id) }
        val deps = p.dependencies
        if (deps != null && deps != "none") {
            out += MenuEntry.Item(if (deps == "ok") tr("deps.reinstall") else tr("deps.install"), icon = "download") { Actions.installDeps(p.id, null) }
        }
        val plugin = PluginActions.menuEntries(ActionLocation.CARD_MENU, p.id)
        if (plugin.isNotEmpty()) {
            out += MenuEntry.Divider
            out += plugin
        }
        out += MenuEntry.Divider
        val others = model.data.workspaces.filter { it.id != p.workspaceId }
        if (others.isNotEmpty()) out += MenuEntry.Item(tr("card.moveTo"), icon = "folder-symlink") { pickWorkspace(p) }
        out += MenuEntry.Item(tr("project.rename"), icon = "pencil") { Actions.rename(p) }
        out += MenuEntry.Item(tr("project.remove"), icon = "trash-2", destructive = true) { Actions.remove(p) }
        return out
    }

    /** 移到其他工作区：底部弹出选择 */
    private fun pickWorkspace(p: ProjectSummary) {
        val others = AppModel.data.workspaces.filter { it.id != p.workspaceId }
        Dialogs.present { dismiss ->
            PaperSelectSheet(
                options = others.map { SelectOption(it.id, it.name, icon = "layers") },
                selected = null,
                onDismiss = dismiss,
                title = tr("card.moveTo"),
            ) { Actions.move(p.id, it) }
        }
    }
}
