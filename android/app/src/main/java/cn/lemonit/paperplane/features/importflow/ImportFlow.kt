package cn.lemonit.paperplane.features.importflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.features.workspace.FacetChip
import cn.lemonit.paperplane.kit.importProject
import cn.lemonit.paperplane.kit.importProjects
import cn.lemonit.paperplane.kit.model.ScanEntry
import cn.lemonit.paperplane.kit.model.TaskInfo
import cn.lemonit.paperplane.kit.scanProjects
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperBadge
import cn.lemonit.paperplane.ui.components.PaperBottomSheet
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperCheckbox
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperProgressBar
import cn.lemonit.paperplane.ui.components.PaperSegmented
import cn.lemonit.paperplane.ui.components.PaperSelect
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.components.PaperTextField
import cn.lemonit.paperplane.ui.components.SelectOption
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 导入工程（顶栏「+」菜单、工程标签的空状态）：选服务器上的文件夹 → 预览（识别结果、名称、工作区）→ 导入；
 * 或扫描文件夹 → 勾选 → 批量导入。对照 Mac Features/Import/ImportFlow。
 */
object ImportFlow {
    fun importFolder() {
        ServerPathPicker.pick(title = tr("import.folder")) { path -> showPreview(path) }
    }

    fun scanFolder() {
        ServerPathPicker.pick(title = tr("import.scan")) { path -> showScan(path) }
    }

    /** 已经选好文件夹时直接预览导入（如文件页的「导入为工程」） */
    fun showPreview(path: String) {
        Dialogs.present(id = "import-$path") { dismiss -> ImportPreviewSheet(path, dismiss) }
    }

    fun showScan(path: String) {
        Dialogs.present(id = "scan-$path") { dismiss -> ScanSheet(path, dismiss) }
    }

    /** 导入成功：切到目标工作区并打开工程 */
    internal fun finish(projectId: String, workspaceId: String) {
        if (AppModel.nav.workspaceId != workspaceId) AppModel.selectWorkspace(workspaceId)
        AppModel.nav.openProject(projectId)
    }
}

/** 导入 / 扫描期间服务器上正在进行的导入任务（显示进度） */
private fun importTask(since: Long): TaskInfo? =
    AppModel.runs.activeTasks.firstOrNull { (it.kind == "import" || it.kind == "reimport") && (it.startedAt?.toEpochMilli() ?: 0) >= since - 2000 }

@Composable
private fun ImportProgress(since: Long) {
    val t = importTask(since)
    val c = PaperTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs)) {
        PaperProgressBar(t?.percent?.takeIf { it >= 0 }?.let { (it / 100).toFloat() })
        val m = t?.message ?: t?.title
        if (!m.isNullOrEmpty()) Text(m, style = PaperTheme.typography.small, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WorkspaceSelect(workspaceId: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    PaperSelect(
        AppModel.data.workspaces.map { SelectOption(it.id, it.name, icon = "layers") },
        selected = workspaceId,
        onSelect = onSelect,
        label = tr("import.workspace"),
        modifier = modifier,
    )
}

// ---------------------------------------------------------------- 导入一个文件夹

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportPreviewSheet(path: String, onDismiss: () -> Unit) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val sep = AppModel.serverPath
    var entry by remember { mutableStateOf<ScanEntry?>(null) }
    var children by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf(sep.lastComponent(path)) }
    var workspaceId by remember { mutableStateOf(Actions.targetWorkspaceId.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var since by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(path) {
        try {
            // maxDepth 0：所选文件夹不是工程时返回它下面的工程
            val list = AppModel.api().scanProjects(path, maxDepth = 0)
            entry = list.firstOrNull { sep.same(it.path, path) }
            children = list.count { !sep.same(it.path, path) }
            entry?.name?.takeIf { it.isNotEmpty() }?.let { name = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            loading = false
        }
    }
    PaperBottomSheet(onDismiss = onDismiss, title = tr("import.folder").trimEnd('…', '.'), showClose = false) { close ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.md)) {
            PaperIcon("folder", size = PaperTheme.sizes.iconLarge, tint = c.iconSecondary)
            Text(Format.abbreviatePath(path), style = PaperTheme.typography.mono, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
        }
        val e = entry
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.sm)) {
                PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
                Text(tr("import.detecting"), style = PaperTheme.typography.body, color = c.textSecondary)
            }
            e != null && e.facets.isNotEmpty() -> FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                for (f in e.facets) FacetChip(f)
            }
            children > 0 -> {
                PaperAlert(Tone.Info, tr("import.containsProjects", children))
                PaperButton(tr("import.scanInstead"), onClick = {
                    close()
                    ImportFlow.showScan(path)
                }, size = ButtonSize.Small, icon = "scan-search")
            }
            (AppModel.systemInfo?.plugins ?: 1) == 0 -> PaperAlert(Tone.Danger, tr("import.noPlugins"))
            else -> PaperAlert(Tone.Warning, tr("import.nothingDetected"))
        }
        if (e?.imported == true) PaperAlert(Tone.Info, tr("import.alreadyImported"))
        PaperTextField(name, { name = it }, label = tr("common.name"), clearable = true)
        WorkspaceSelect(workspaceId, { workspaceId = it })
        if (busy) ImportProgress(since)
        error?.let { PaperAlert(Tone.Danger, it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sp.md)) {
            PaperButton(tr("common.cancel"), onClick = close, modifier = Modifier.weight(1f))
            val pid = e?.projectId
            if (e?.imported == true && pid != null) {
                PaperButton(tr("card.open"), onClick = {
                    close()
                    AppModel.nav.openProject(pid)
                }, kind = ButtonKind.Primary, modifier = Modifier.weight(1f))
            } else {
                PaperButton(
                    tr("import.import"),
                    kind = ButtonKind.Primary,
                    loading = busy,
                    enabled = workspaceId.isNotEmpty() && !loading,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        busy = true
                        error = null
                        since = System.currentTimeMillis()
                        val ws = workspaceId
                        val n = name.trim()
                        scope.launch {
                            try {
                                val p = AppModel.api().importProject(path, ws, n.ifEmpty { null })
                                close()
                                ImportFlow.finish(p.id, ws)
                            } catch (ex: CancellationException) {
                                throw ex
                            } catch (ex: Exception) {
                                // 请求出错不等于没导入（可能已经导入成功、只是连接中途断了）：以服务器的实际状态为准
                                val done = runCatching {
                                    AppModel.api().scanProjects(path, maxDepth = 0).firstOrNull { sep.same(it.path, path) && it.imported }?.projectId
                                }.getOrNull()
                                if (done != null) {
                                    close()
                                    ImportFlow.finish(done, ws)
                                } else {
                                    error = ex.message
                                }
                            } finally {
                                busy = false
                            }
                        }
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 扫描文件夹

@Composable
private fun ScanSheet(path: String, onDismiss: () -> Unit) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val sep = AppModel.serverPath
    var depth by remember { mutableStateOf(2) }
    var entries by remember { mutableStateOf<List<ScanEntry>>(emptyList()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var loading by remember { mutableStateOf(true) }
    var workspaceId by remember { mutableStateOf(Actions.targetWorkspaceId.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var since by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(depth) {
        loading = true
        try {
            entries = AppModel.api().scanProjects(path, maxDepth = depth)
            selected = entries.filter { !it.imported }.map { it.path }.toSet()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            entries = emptyList()
            error = e.message
        } finally {
            loading = false
        }
    }
    val selectable = entries.filter { !it.imported }
    PaperBottomSheet(onDismiss = onDismiss, title = tr("import.scan").trimEnd('…', '.'), showClose = false, scrollable = false) { close ->
        Text(Format.abbreviatePath(path), style = PaperTheme.typography.mono, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.md)) {
            Text(tr("import.depth"), style = PaperTheme.typography.body, color = c.textSecondary)
            PaperSegmented(listOf(1 to "1", 2 to "2", 3 to "3", 4 to "4"), selected = depth, onSelect = { depth = it }, modifier = Modifier.weight(1f), fill = true)
        }
        val shape = RoundedCornerShape(PaperTheme.radius.md)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.bgSurface, shape)
                .border(PaperTheme.sizes.border, c.border, shape),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.bgSubtle)
                    .toggleable(
                        value = selectable.isNotEmpty() && selected.size == selectable.size,
                        enabled = selectable.isNotEmpty(),
                        role = Role.Checkbox,
                    ) { on -> selected = if (on) selectable.map { it.path }.toSet() else emptySet() }
                    .heightIn(min = PaperTheme.sizes.rowHeight)
                    .padding(horizontal = sp.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.md),
            ) {
                PaperCheckbox(selectable.isNotEmpty() && selected.size == selectable.size, onCheckedChange = null, enabled = selectable.isNotEmpty())
                Text(tr("import.selectedCount", selected.size, entries.size), style = PaperTheme.typography.body, color = c.textSecondary)
            }
            PaperDivider(subtle = true)
            Box(Modifier.fillMaxWidth().height(cn.lemonit.paperplane.features.launch.listHeight() - 40.dp)) {
                when {
                    loading -> Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalAlignment = Alignment.CenterVertically) {
                        PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
                        Text(tr("import.detecting"), style = PaperTheme.typography.body, color = c.textSecondary)
                    }
                    entries.isEmpty() -> Text(tr("import.scanEmpty"), style = PaperTheme.typography.body, color = c.textSecondary, modifier = Modifier.align(Alignment.Center))
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(entries, key = { it.path }) { e ->
                            ScanRow(e, path, sep, e.path in selected) { on -> selected = if (on) selected + e.path else selected - e.path }
                        }
                    }
                }
            }
        }
        WorkspaceSelect(workspaceId, { workspaceId = it })
        if (busy) ImportProgress(since)
        error?.let { PaperAlert(Tone.Danger, it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sp.md)) {
            PaperButton(tr("common.cancel"), onClick = close, modifier = Modifier.weight(1f))
            PaperButton(
                tr("import.importN", selected.size),
                kind = ButtonKind.Primary,
                loading = busy,
                enabled = selected.isNotEmpty() && workspaceId.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = {
                    busy = true
                    error = null
                    since = System.currentTimeMillis()
                    val ws = workspaceId
                    val paths = entries.map { it.path }.filter { it in selected }
                    scope.launch {
                        try {
                            val res = AppModel.api().importProjects(paths, ws)
                            close()
                            if (AppModel.nav.workspaceId != ws) AppModel.selectWorkspace(ws)
                            Toasts.success(tr("import.done", res.size))
                        } catch (ex: CancellationException) {
                            throw ex
                        } catch (ex: Exception) {
                            // 先核实哪些其实已经导入了：全部已导入按成功处理；否则刷新列表（已导入的会标出来）再报错
                            val fresh = runCatching { AppModel.api().scanProjects(path, maxDepth = depth) }.getOrDefault(emptyList())
                            val done = fresh.filter { it.imported }.map { sep.normalize(it.path) }.toSet()
                            if (paths.all { sep.normalize(it) in done }) {
                                close()
                                if (AppModel.nav.workspaceId != ws) AppModel.selectWorkspace(ws)
                                Toasts.success(tr("import.done", paths.size))
                            } else {
                                if (fresh.isNotEmpty()) {
                                    entries = fresh
                                    selected = fresh.filter { !it.imported && it.path in selected }.map { it.path }.toSet()
                                }
                                error = ex.message
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanRow(e: ScanEntry, root: String, sep: cn.lemonit.paperplane.kit.util.ServerPath, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val rel = sep.relative(e.path, root)?.let { if (it.isEmpty()) "." else "./$it" } ?: Format.abbreviatePath(e.path)
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = !e.imported, role = Role.Checkbox, onValueChange = onChange)
            .alpha(if (e.imported) 0.6f else 1f)
            .padding(horizontal = sp.lg, vertical = sp.md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(sp.md),
    ) {
        PaperCheckbox(checked, onCheckedChange = null, enabled = !e.imported)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.sm)) {
                Text(e.name, style = PaperTheme.typography.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (e.imported) PaperBadge(tr("import.imported"))
            }
            Text(rel, style = PaperTheme.typography.small, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            if (e.facets.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                    for (f in e.facets.take(4)) FacetChip(f)
                }
            }
        }
    }
}
