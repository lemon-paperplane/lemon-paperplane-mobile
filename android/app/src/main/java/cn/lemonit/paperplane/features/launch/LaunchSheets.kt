package cn.lemonit.paperplane.features.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.features.importflow.ServerPathPicker
import cn.lemonit.paperplane.kit.launchAdd
import cn.lemonit.paperplane.kit.launchAddCommand
import cn.lemonit.paperplane.kit.launchAddFile
import cn.lemonit.paperplane.kit.launchCandidates
import cn.lemonit.paperplane.kit.launchRemove
import cn.lemonit.paperplane.kit.launchSetAlias
import cn.lemonit.paperplane.kit.model.FileIcon
import cn.lemonit.paperplane.kit.model.ProjectSummary
import cn.lemonit.paperplane.kit.model.RunnableFile
import cn.lemonit.paperplane.kit.model.RunnableList
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.kit.util.IconSource
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperBadge
import cn.lemonit.paperplane.ui.components.PaperBottomSheet
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperCheckbox
import cn.lemonit.paperplane.ui.components.PaperInfoButton
import cn.lemonit.paperplane.ui.components.PaperSearchField
import cn.lemonit.paperplane.ui.components.PaperSelect
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.components.PaperSwitch
import cn.lemonit.paperplane.ui.components.PaperTextArea
import cn.lemonit.paperplane.ui.components.PaperTextField
import cn.lemonit.paperplane.ui.components.SelectOption
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.icons.RefIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// 启动面板的几个底部面板（对照 Mac LaunchPickerDialog / AddRunnableDialog / AddCommandDialog / 别名表单）。

object LaunchSheets {
    /** 选择启动项：按工程列出全部运行配置，勾选的就在启动里 */
    fun choose(workspaceId: String) {
        Dialogs.present(id = "launch-picker-$workspaceId") { dismiss -> ChooseSheet(workspaceId, dismiss) }
    }

    /** 添加脚本或程序：只列内核判定能运行的文件，按目录分组，可筛选、多选 */
    fun addRunnable(workspaceId: String, projectId: String? = null) {
        Dialogs.present(id = "launch-add-runnable-$workspaceId") { dismiss -> AddRunnableSheet(workspaceId, projectId, dismiss) }
    }

    /** 添加命令：名称、所属工程、执行目录、命令（可多行） */
    fun addCommand(workspaceId: String, projectId: String? = null) {
        val projects = AppModel.data.projects(workspaceId)
        val draft = CommandDraft(projectId?.takeIf { id -> projects.any { it.id == id } } ?: projects.firstOrNull()?.id.orEmpty())
        presentCommand(workspaceId, draft)
    }

    internal fun presentCommand(workspaceId: String, draft: CommandDraft) {
        Dialogs.present(id = "launch-add-command-$workspaceId") { dismiss -> AddCommandSheet(workspaceId, draft, dismiss) }
    }

    /** 设置别名（只在「启动」里显示；清空恢复原名） */
    fun editAlias(projectId: String, configId: String, originalName: String, current: String?) {
        Dialogs.present(id = "launch-alias-$projectId/$configId") { dismiss -> AliasSheet(projectId, configId, originalName, current.orEmpty(), dismiss) }
    }
}

/** 面板底部的「取消 / 确定」 */
@Composable
private fun SheetButtons(okText: String, onCancel: () -> Unit, onOk: () -> Unit, busy: Boolean = false, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md)) {
        PaperButton(tr("common.cancel"), onClick = onCancel, modifier = Modifier.weight(1f))
        PaperButton(okText, onClick = onOk, kind = ButtonKind.Primary, loading = busy, enabled = enabled, modifier = Modifier.weight(1f))
    }
}

/** 可勾选的一行（整行可点，至少 48dp 高） */
@Composable
private fun CheckRow(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = PaperTheme.sizes.rowHeight)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .alpha(if (enabled) 1f else 0.6f)
            .padding(horizontal = PaperTheme.spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md),
    ) {
        PaperCheckbox(checked, onCheckedChange = null, enabled = enabled)
        content()
    }
}

/** 面板里列表的高度：屏幕高度的四成（220–360dp），小屏手机上面板也放得下 */
@Composable
internal fun listHeight(): androidx.compose.ui.unit.Dp =
    (LocalConfiguration.current.screenHeightDp * 0.4f).coerceIn(220f, 360f).dp

// ---------------------------------------------------------------- 选择启动项

@Composable
private fun ChooseSheet(workspaceId: String, onDismiss: () -> Unit) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val projects = AppModel.data.projects(workspaceId).filter { it.configs.isNotEmpty() }
    // "工程id/配置id"
    var selected by remember { mutableStateOf(projects.flatMap { p -> p.launch.map { p.id + "/" + it } }.toSet()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PaperBottomSheet(onDismiss = onDismiss, title = tr("launch.choose"), scrollable = false, edgeToEdge = true) { close ->
        if (projects.isEmpty()) {
            Text(tr("launch.choose.none"), style = PaperTheme.typography.body, color = c.textSecondary, modifier = Modifier.padding(sp.xl))
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = listHeight() + 100.dp)) {
                for (p in projects) {
                    val all = p.configs.map { p.id + "/" + it.id }
                    val allOn = all.all { it in selected }
                    item(key = "h:" + p.id) {
                        Row(Modifier.fillMaxWidth().padding(start = sp.xl, end = sp.sm, top = sp.md), verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, style = PaperTheme.typography.label, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            PaperButton(
                                if (allOn) tr("launch.choose.clear") else tr("launch.choose.all"),
                                onClick = { selected = if (allOn) selected - all.toSet() else selected + all },
                                kind = ButtonKind.Text,
                                size = ButtonSize.Small,
                            )
                        }
                    }
                    val multiModule = p.configs.map { it.moduleId }.distinct().size > 1
                    for (cfg in p.configs) {
                        val key = p.id + "/" + cfg.id
                        item(key = "c:$key") {
                            CheckRow(key in selected, onChange = { on -> selected = if (on) selected + key else selected - key }) {
                                RefIcon(IconSource.parse(cfg.icon), size = PaperTheme.sizes.iconSmall, tint = c.iconPrimary, fallback = "play")
                                Text(cfg.name, style = PaperTheme.typography.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (multiModule && cfg.moduleName.isNotEmpty()) {
                                    Text(cfg.moduleName, style = PaperTheme.typography.small, color = c.textTertiary, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.padding(horizontal = sp.xl, vertical = sp.md)) {
            SheetButtons(tr("common.ok"), onCancel = close, busy = busy, enabled = projects.isNotEmpty(), onOk = {
                busy = true
                val chosen = selected
                scope.launch {
                    try {
                        val api = AppModel.api()
                        for (p in projects) {
                            val want = p.configs.map { it.id }.filter { (p.id + "/" + it) in chosen }
                            val add = want.filter { it !in p.launch }
                            val remove = p.launch.filter { it !in want }
                            if (add.isNotEmpty()) api.launchAdd(p.id, add)
                            if (remove.isNotEmpty()) api.launchRemove(p.id, remove)
                        }
                        close()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Toasts.error(e)
                    } finally {
                        busy = false
                    }
                }
            })
        }
    }
}

// ---------------------------------------------------------------- 添加脚本或程序

@Composable
private fun AddRunnableSheet(workspaceId: String, initialProject: String?, onDismiss: () -> Unit) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val projects = AppModel.data.projects(workspaceId)
    var projectId by remember { mutableStateOf(initialProject?.takeIf { id -> projects.any { it.id == id } } ?: projects.firstOrNull()?.id.orEmpty()) }
    var includeSources by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<RunnableList?>(null) }
    var loading by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(projectId, includeSources) {
        if (projectId.isEmpty()) return@LaunchedEffect
        loading = true
        try {
            val r = AppModel.api().launchCandidates(projectId, includeSources)
            list = r
            val paths = r.files.map { it.path }.toSet()
            selected = selected.filter { it in paths }.toSet()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }
    PaperBottomSheet(onDismiss = onDismiss, title = tr("launch.pick.title"), scrollable = false) { close ->
        if (projects.isEmpty()) {
            Text(tr("launch.addCommand.noProject"), style = PaperTheme.typography.body, color = c.textSecondary)
            return@PaperBottomSheet
        }
        if (projects.size > 1) {
            PaperSelect(
                projects.map { SelectOption(it.id, it.name) },
                selected = projectId,
                onSelect = {
                    if (it != projectId) {
                        projectId = it
                        selected = emptySet()
                        list = null
                    }
                },
                title = tr("launch.addCommand.project"),
            )
        }
        PaperSearchField(filter, { filter = it }, placeholder = tr("launch.pick.filter"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.sm)) {
            Text(tr("launch.pick.sources"), style = PaperTheme.typography.small, color = c.textSecondary, modifier = Modifier.weight(1f, fill = false))
            PaperInfoButton(tr("launch.pick.sources.info"))
            Spacer(Modifier.weight(1f))
            PaperSwitch(includeSources, onCheckedChange = { includeSources = it })
        }
        val q = filter.trim()
        val files = list?.files.orEmpty().filter { q.isEmpty() || it.path.contains(q, ignoreCase = true) }
        val groups = files.groupBy { it.directory }
        val shape = RoundedCornerShape(PaperTheme.radius.md)
        Box(
            Modifier
                .fillMaxWidth()
                .height(listHeight())
                .clip(shape)
                .background(c.bgSubtle, shape)
                .border(PaperTheme.sizes.border, c.border, shape),
        ) {
            when {
                loading && list == null -> PaperSpinner(Modifier.align(Alignment.Center), tint = c.iconSecondary)
                files.isEmpty() -> Text(
                    if (q.isEmpty()) tr("launch.pick.empty") else tr("workspace.noMatch"),
                    style = PaperTheme.typography.body,
                    color = c.textSecondary,
                    modifier = Modifier.align(Alignment.Center).padding(sp.xl),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    for ((dir, fs) in groups) {
                        item(key = "d:$dir") {
                            Row(Modifier.padding(start = sp.lg, end = sp.lg, top = sp.md, bottom = sp.xxs), horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalAlignment = Alignment.CenterVertically) {
                                PaperIcon("folder", size = 14.dp, tint = c.iconSecondary)
                                Text(dir.ifEmpty { tr("launch.pick.root") }, style = PaperTheme.typography.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                            }
                        }
                        for (f in fs) item(key = "f:" + f.path) { RunnableRow(f, f.launch || f.path in selected) { on -> selected = if (on) selected + f.path else selected - f.path } }
                    }
                }
            }
        }
        if (list?.truncated == true) {
            Text(tr("launch.pick.truncated", list?.files?.size ?: 0), style = PaperTheme.typography.small, color = c.textTertiary)
        }
        error?.let { PaperAlert(Tone.Danger, it) }
        SheetButtons(tr("launch.pick.add", selected.size), onCancel = close, busy = busy, enabled = selected.isNotEmpty(), onOk = {
            busy = true
            val pid = projectId
            val paths = list?.files.orEmpty().map { it.path }.filter { it in selected }
            scope.launch {
                try {
                    val api = AppModel.api()
                    for (p in paths) api.launchAddFile(pid, p)
                    close()
                    Toasts.success(tr("launch.pick.added", paths.size))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message
                } finally {
                    busy = false
                }
            }
        })
    }
}

@Composable
private fun RunnableRow(f: RunnableFile, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = PaperTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = PaperTheme.sizes.rowHeight)
            .toggleable(value = checked, enabled = !f.launch, role = Role.Checkbox, onValueChange = onChange)
            .alpha(if (f.launch) 0.6f else 1f)
            .padding(start = PaperTheme.spacing.xl, end = PaperTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm),
    ) {
        PaperCheckbox(checked, onCheckedChange = null, enabled = !f.launch)
        PaperIcon(FileIcon.builtinName(f.name), size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
        Text(f.name, style = PaperTheme.typography.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
        PaperBadge(tr("launch.pick.kind." + f.kind))
        Spacer(Modifier.weight(1f))
        if (f.launch) Text(tr("launch.pick.inLaunch"), style = PaperTheme.typography.small, color = c.textTertiary, maxLines = 1)
    }
}

// ---------------------------------------------------------------- 添加命令

/** 「添加命令」表单的内容：选执行目录时面板要先收起（选择页是全屏页面），选完带着内容重新打开 */
@Stable
internal class CommandDraft(projectId: String) {
    var projectId by mutableStateOf(projectId)
    var name by mutableStateOf("")
    var cwd by mutableStateOf("")
    var command by mutableStateOf("")
    var showErrors by mutableStateOf(false)
}

@Composable
private fun AddCommandSheet(workspaceId: String, draft: CommandDraft, onDismiss: () -> Unit) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val projects = AppModel.data.projects(workspaceId)
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    PaperBottomSheet(onDismiss = onDismiss, title = tr("launch.addCommand"), showClose = false) { close ->
        if (projects.isEmpty()) {
            Text(tr("launch.addCommand.noProject"), style = PaperTheme.typography.body, color = c.textSecondary)
            return@PaperBottomSheet
        }
        val project: ProjectSummary? = projects.firstOrNull { it.id == draft.projectId }
        PaperTextField(draft.name, { draft.name = it }, label = tr("common.name"), placeholder = tr("launch.addCommand.namePlaceholder"), clearable = true)
        PaperSelect(
            projects.map { SelectOption(it.id, it.name, detail = Format.abbreviatePath(it.path)) },
            selected = draft.projectId,
            onSelect = { draft.projectId = it },
            label = tr("launch.addCommand.project"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(sp.xs)) {
            Text(tr("launch.addCommand.cwd"), style = PaperTheme.typography.label, color = c.textSecondary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.sm)) {
                PaperTextField(draft.cwd, { draft.cwd = it }, placeholder = tr("launch.addCommand.cwdPlaceholder"), mono = true, clearable = true, modifier = Modifier.weight(1f))
                PaperButton(tr("form.browse").trimEnd('…', '.'), onClick = {
                    val p = project ?: return@PaperButton
                    val sep = AppModel.serverPath
                    val start = when {
                        draft.cwd.isEmpty() -> p.path
                        sep.isAbsolute(draft.cwd) -> draft.cwd
                        else -> sep.join(p.path, draft.cwd)
                    }
                    close()
                    ServerPathPicker.pick(
                        title = tr("launch.addCommand.cwd"),
                        start = start,
                        onCancel = { LaunchSheets.presentCommand(workspaceId, draft) },
                    ) { path ->
                        draft.cwd = sep.relative(path, p.path) ?: sep.normalize(path)
                        LaunchSheets.presentCommand(workspaceId, draft)
                    }
                })
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(sp.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("launch.addCommand.command"), style = PaperTheme.typography.label, color = c.textSecondary)
                Text(" *", style = PaperTheme.typography.label, color = c.danger)
                PaperInfoButton(tr("launch.addCommand.commandInfo"))
            }
            PaperTextArea(
                draft.command,
                { draft.command = it },
                placeholder = tr("launch.addCommand.commandPlaceholder"),
                mono = true,
                minLines = 4,
                maxLines = 10,
                error = if (draft.showErrors && draft.command.isBlank()) "" else null,
            )
        }
        error?.let { PaperAlert(Tone.Danger, it) }
        SheetButtons(tr("launch.addCommand.add"), onCancel = close, busy = busy, onOk = {
            if (busy) return@SheetButtons
            if (draft.command.isBlank() || draft.projectId.isEmpty()) {
                draft.showErrors = true
                return@SheetButtons
            }
            busy = true
            error = null
            scope.launch {
                try {
                    val r = AppModel.api().launchAddCommand(draft.projectId, draft.name.trim(), draft.command, draft.cwd.trim())
                    close()
                    Toasts.success(tr("launch.commandAdded", r.name))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message
                } finally {
                    busy = false
                }
            }
        })
    }
}

// ---------------------------------------------------------------- 别名

@Composable
private fun AliasSheet(projectId: String, configId: String, original: String, current: String, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    PaperBottomSheet(onDismiss = onDismiss, title = tr("launch.alias.title", original), showClose = false) { close ->
        fun save() {
            if (busy) return
            busy = true
            scope.launch {
                try {
                    AppModel.api().launchSetAlias(projectId, configId, text.trim())
                    close()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message
                } finally {
                    busy = false
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("launch.alias"), style = PaperTheme.typography.label, color = PaperTheme.colors.textSecondary)
            PaperInfoButton(tr("launch.alias.info"))
        }
        PaperTextField(text, { text = it.take(80) }, placeholder = original, clearable = true, onSubmit = { save() })
        error?.let { PaperAlert(Tone.Danger, it) }
        SheetButtons(tr("common.save"), onCancel = close, busy = busy, onOk = { save() })
    }
}
