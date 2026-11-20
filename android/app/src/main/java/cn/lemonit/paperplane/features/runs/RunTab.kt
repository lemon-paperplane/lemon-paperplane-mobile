package cn.lemonit.paperplane.features.runs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.features.launch.LaunchActions
import cn.lemonit.paperplane.features.project.PluginActions
import cn.lemonit.paperplane.features.resources.RunResourcePanel
import cn.lemonit.paperplane.kit.model.ActionLocation
import cn.lemonit.paperplane.kit.model.ProjectSummary
import cn.lemonit.paperplane.kit.model.Run
import cn.lemonit.paperplane.kit.model.RunState
import cn.lemonit.paperplane.kit.setDefaultRunConfig
import cn.lemonit.paperplane.kit.setRemoteLinks
import cn.lemonit.paperplane.kit.util.IconSource
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperLoadingPage
import cn.lemonit.paperplane.ui.components.PaperMoreButton
import cn.lemonit.paperplane.ui.components.PaperSelect
import cn.lemonit.paperplane.ui.components.PaperStatusDot
import cn.lemonit.paperplane.ui.components.SelectOption
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone

/**
 * 工程详情的「运行」标签（对照 Mac Features/Project/RunTab.swift，手机上改成上下排）：
 * 运行配置选择 + 运行 / 停止 / 重启 + 更多；运行地址；诊断与一键修复；资源面板；控制台（已结束的运行也能看日志）。
 *
 * @param configId 要显示的运行配置（「看日志」跳过来时给）；为空时用上次选的或默认配置
 */
@Composable
fun ProjectRunTab(projectId: String, configId: String?) {
    val p = AppModel.data.projects[projectId]
    if (p == null) {
        PaperLoadingPage()
        return
    }
    // 跳转时指定的配置优先；之后以用户在这里的选择为准，并同步给工程卡片（两处显示同一个）
    var routeConfig by remember(projectId, configId) { mutableStateOf(configId) }
    LaunchedEffect(projectId, configId) { if (configId != null) RunSelection.select(projectId, configId) }
    val cid = routeConfig?.takeIf { id -> p.configs.any { it.id == id } } ?: RunSelection.configId(p)
    if (cid == null) {
        Box(Modifier.fillMaxSize().background(PaperTheme.colors.bgContent)) {
            PaperEmptyState("play", tr("run.noConfig"), Modifier.align(Alignment.Center))
        }
        return
    }
    val runs = AppModel.runs
    val run = runs.activeRun(projectId, cid) ?: runs.latestRun(projectId, cid)
    RunTabContent(p, cid, run, onSelect = {
        routeConfig = null
        RunSelection.select(projectId, it)
    })
}

/** 运行标签的外观（数据作为参数；console 给了就用它，截图测试用） */
@Composable
internal fun RunTabContent(
    p: ProjectSummary,
    configId: String,
    run: Run?,
    onSelect: (String) -> Unit,
    console: ConsoleState? = null,
) {
    val c = PaperTheme.colors
    // 底部的输入行要让开系统导航栏和键盘（外层已经处理过的不会重复留白）
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bgContent)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom)),
    ) {
        RunHeader(p, configId, run, onSelect)
        val urls = run?.takeIf { it.isActive }?.urls.orEmpty()
        if (urls.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = PaperTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (u in urls.take(2)) RunLinks(u, run!!.projectId, run.configId, runId = run.id)
            }
        }
        if (run != null) RunDiagnoses(run, p.id)
        PaperDivider(subtle = true)
        if (run == null) {
            Box(Modifier.weight(1f).fillMaxWidth().background(c.consoleBg)) {
                PaperEmptyState(
                    "terminal",
                    tr("run.notStarted"),
                    Modifier.align(Alignment.Center),
                    actionText = tr("action.run"),
                    actionIcon = "play",
                ) { Actions.run(p.id, configId) }
            }
            return@Column
        }
        RunResourcePanel(run)
        val leading: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = { RunStatusLabel(run) }
        if (console != null) {
            ConsoleView(console, Modifier.weight(1f).fillMaxWidth(), runId = run.id, inputEnabled = run.isActive, projectId = run.projectId, configId = run.configId, toolbarLeading = leading)
        } else {
            ConsoleView(run.id, Modifier.weight(1f).fillMaxWidth(), runId = run.id, inputEnabled = run.isActive, projectId = run.projectId, configId = run.configId, toolbarLeading = leading)
        }
    }
}

/** 状态点 + 「运行中 · 2m 5s」（运行时长每秒刷新，只重组这一小块） */
@Composable
internal fun RunStatusLabel(run: Run, modifier: Modifier = Modifier, showDot: Boolean = true) {
    val c = PaperTheme.colors
    val now = if (run.isActive) rememberNow(1000) else System.currentTimeMillis()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm)) {
        if (showDot) PaperStatusDot(run.state)
        Text(
            runStatusText(run, now),
            style = PaperTheme.typography.small,
            color = if (run.state == RunState.FAILED) c.danger else c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 运行配置选择 + 运行控制 + 更多 */
@Composable
private fun RunHeader(p: ProjectSummary, configId: String, run: Run?, onSelect: (String) -> Unit) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val multiModule = p.configs.map { it.moduleId }.distinct().size > 1
    val options = p.configs.map { cfg ->
        SelectOption(
            cfg.id,
            cfg.name,
            icon = (IconSource.parse(cfg.icon) as? IconSource.Builtin)?.name,
            detail = cfg.moduleName.takeIf { multiModule && it.isNotEmpty() },
        )
    }
    val active = run?.takeIf { it.isActive }
    Row(
        Modifier.fillMaxWidth().padding(start = sp.lg, end = sp.xs, top = sp.sm, bottom = sp.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.xs),
    ) {
        PaperSelect(options, selected = configId, onSelect = onSelect, title = tr("runconfig.title"), modifier = Modifier.weight(1f))
        if (active != null) {
            PaperIconButton("rotate-cw", tr("action.restart"), onClick = { Actions.restart(active.id) })
            PaperIconButton("square", tr("action.stop"), onClick = { Actions.stop(active.id) }, tint = c.danger)
        } else {
            Spacer(Modifier.size(PaperTheme.sizes.iconButton))
            PaperIconButton("play", tr("action.run"), onClick = { Actions.run(p.id, configId) }, tint = c.success)
        }
        PaperMoreButton({ runMenu(p, configId, run) })
    }
}

/** 运行标签的「更多」：设为默认、加入 / 移出启动、远程访问方式、插件的运行工具栏动作 */
private fun runMenu(p: ProjectSummary, configId: String, run: Run?): List<MenuEntry> {
    val out = ArrayList<MenuEntry>()
    val isDefault = p.defaultConfigId == configId
    out += MenuEntry.Item(tr("runconfig.setDefault"), icon = "pin", enabled = !isDefault) {
        AppModel.perform { api -> api.setDefaultRunConfig(p.id, configId) }
    }
    val inLaunch = configId in p.launch
    out += MenuEntry.Item(if (inLaunch) tr("launch.remove") else tr("launch.add"), icon = "rocket") {
        LaunchActions.setLaunch(p.id, listOf(configId), !inLaunch)
    }
    // 远程访问方式：至少保留一种
    val links = p.remoteLinks(configId)
    out += MenuEntry.Divider
    out += MenuEntry.Header(tr("runconfig.remoteLinks"))
    out += MenuEntry.Item(tr("runconfig.remoteLinks.forward"), checked = links.forward, enabled = !links.forward || links.direct) {
        AppModel.perform { api -> api.setRemoteLinks(p.id, configId, forward = !links.forward, direct = links.direct) }
    }
    out += MenuEntry.Item(tr("runconfig.remoteLinks.direct"), checked = links.direct, enabled = !links.direct || links.forward) {
        AppModel.perform { api -> api.setRemoteLinks(p.id, configId, forward = links.forward, direct = !links.direct) }
    }
    // 插件挂在运行工具栏的动作
    val cfg = p.configs.firstOrNull { it.id == configId }
    val acts = applicableActions(
        AppModel.data.contributions.actions(ActionLocation.RUN_TOOLBAR),
        facets = p.facets.map { it.id }.toSet(),
        plugins = p.facets.map { it.plugin }.toSet(),
    )
    if (acts.isNotEmpty()) {
        out += MenuEntry.Divider
        for (a in acts) {
            out += MenuEntry.Item(a.title, icon = (IconSource.parse(a.icon, a.plugin) as? IconSource.Builtin)?.name ?: "zap") {
                PluginActions.invoke(a, p.id, cfg?.moduleId, run?.id)
            }
        }
    }
    return out
}

/** 运行的诊断（可关掉，带一键修复）；准备阶段失败且没有诊断时显示失败原因 */
@Composable
private fun RunDiagnoses(run: Run, projectId: String) {
    var dismissed by rememberSaveable(run.id) { mutableStateOf(emptySet<String>()) }
    val list = remember(run.diagnoses, dismissed) { run.diagnoses.filter { it.stableKey !in dismissed } }
    val failedText = run.error?.takeIf { run.state == RunState.FAILED && it.isNotEmpty() && list.isEmpty() }
    if (list.isEmpty() && failedText == null) return
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PaperTheme.spacing.md, vertical = PaperTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm),
    ) {
        if (failedText != null) PaperAlert(Tone.Danger, failedText)
        for (d in list) {
            DiagnosisRow(d, runId = run.id, projectId = projectId, onDismiss = { dismissed = dismissed + d.stableKey })
        }
    }
}
