package cn.lemonit.paperplane.features.vcs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.ProjectTab
import cn.lemonit.paperplane.features.importflow.ServerPathPicker
import cn.lemonit.paperplane.features.runs.TaskLog
import cn.lemonit.paperplane.kit.cloneProject
import cn.lemonit.paperplane.kit.model.Project
import cn.lemonit.paperplane.kit.model.TaskInfo
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperBottomSheet
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperLink
import cn.lemonit.paperplane.ui.components.PaperProgressBar
import cn.lemonit.paperplane.ui.components.PaperSelect
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.components.PaperTextField
import cn.lemonit.paperplane.ui.components.SelectOption
import cn.lemonit.paperplane.ui.components.ToastKind
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant

// 从 Git 克隆（顶栏「+」菜单）：地址、目标目录（服务器上的父目录 + 文件夹名）、分支、工作区 → project.clone（克隆后自动导入）。
// 克隆在 AppModel 的作用域里跑：收起面板不会中断，完成后提示并可打开。对照 Mac VCSCloneDialog.swift。

/** 克隆仓库并导入（顶栏「+」菜单） */
object VcsCloneFlow {
    private const val ID = "vcs-clone"

    /** 正在进行的克隆（面板收起后仍在） */
    private class Running(val dest: String, val started: Instant) {
        var error by mutableStateOf<String?>(null)
        var done by mutableStateOf(false)
    }

    private var running by mutableStateOf<Running?>(null)

    fun start() {
        Dialogs.present(ID) { dismiss -> CloneSheet(dismiss) }
    }

    /** 由地址推出文件夹名：…/name.git → name */
    fun folderName(url: String): String {
        var s = url.trim().trimEnd('/')
        if (s.endsWith(".git")) s = s.dropLast(4)
        return s.split('/', ':').lastOrNull { it.isNotEmpty() }.orEmpty()
    }

    private fun defaultParent(): String {
        val sid = AppModel.currentServerId.orEmpty()
        VcsPrefs.cloneParent(sid)?.let { return it }
        val home = AppModel.systemInfo?.home.orEmpty()
        return if (home.isEmpty()) "" else AppModel.serverPath.join(home, "Code")
    }

    /** 内核为克隆建的任务（没有工程 id） */
    private fun task(r: Running): TaskInfo? = AppModel.runs.tasks.values
        .filter { it.kind == "vcs" && it.projectId == null && (it.startedAt ?: Instant.EPOCH) >= r.started.minusSeconds(2) }
        .maxByOrNull { it.startedAt ?: Instant.EPOCH }

    private fun open(p: Project) {
        if (p.workspaceId.isNotEmpty()) AppModel.selectWorkspace(p.workspaceId)
        AppModel.nav.openProject(p.id, ProjectTab.VCS)
    }

    private fun clone(url: String, dest: String, branch: String?, workspaceId: String?) {
        val r = Running(dest, Instant.now())
        running = r
        VcsPrefs.setCloneParent(AppModel.currentServerId.orEmpty(), AppModel.serverPath.parent(dest) ?: dest)
        AppModel.scope.launch {
            try {
                val p = AppModel.api().cloneProject(url, dest, branch, workspaceId)
                r.done = true
                if (running === r) running = null
                if (Dialogs.contains(ID)) {
                    Dialogs.dismiss(ID)
                    Toasts.show(tr("vcs.clone.done", p.name), ToastKind.Success)
                    open(p)
                } else {
                    Toasts.show(tr("vcs.clone.done", p.name), ToastKind.Success, actionText = tr("vcs.mobile.open")) { open(p) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                r.error = e.message ?: tr("common.failed")
                if (!Dialogs.contains(ID)) {
                    running = null
                    Toasts.error(e, tr("vcs.clone.title"))
                }
            }
        }
    }

    @Composable
    private fun CloneSheet(onDismiss: () -> Unit) {
        val data = AppModel.data
        var url by remember { mutableStateOf("") }
        var parent by remember { mutableStateOf(defaultParent()) }
        var folder by remember { mutableStateOf("") }
        var folderEdited by remember { mutableStateOf(false) }
        var branch by remember { mutableStateOf("") }
        var workspaceId by remember { mutableStateOf(Actions.targetWorkspaceId.orEmpty()) }
        val r = running
        val active = r?.takeIf { !it.done && it.error == null }
        val busy = active != null
        val dest = if (parent.isEmpty() || folder.isEmpty()) "" else AppModel.serverPath.join(parent, folder)
        val c = PaperTheme.colors
        val sp = PaperTheme.spacing
        PaperBottomSheet(onDismiss = {
            // 失败的克隆收起面板时清掉；进行中的继续在后台跑
            if (running?.error != null) running = null
            onDismiss()
        }, title = tr("vcs.clone.title"), showClose = false) { dismiss ->
            PaperTextField(
                url,
                { v ->
                    url = v
                    if (!folderEdited) folder = folderName(v)
                },
                label = tr("vcs.clone.url"),
                placeholder = "https://github.com/… / git@…",
                mono = true,
                keyboardType = KeyboardType.Uri,
                enabled = !busy,
            )
            Column(verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                Text(tr("vcs.clone.directory"), style = PaperTheme.typography.label, color = c.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalAlignment = Alignment.CenterVertically) {
                    PaperTextField(parent, { parent = it }, mono = true, enabled = !busy, modifier = Modifier.weight(1f))
                    PaperButton(tr("vcs.clone.choose").plain(), onClick = {
                        ServerPathPicker.pick(tr("vcs.clone.directory"), start = parent.ifEmpty { null }) { parent = it }
                    }, enabled = !busy)
                }
            }
            PaperTextField(folder, {
                folder = it
                folderEdited = true
            }, label = tr("vcs.clone.folder"), mono = true, enabled = !busy)
            PaperTextField(branch, { branch = it }, label = tr("vcs.clone.branch"), placeholder = tr("vcs.clone.branchPlaceholder"), mono = true, enabled = !busy)
            if (data.workspaces.size > 1) {
                PaperSelect(
                    data.workspaces.map { SelectOption(it.id, it.name, "layers") },
                    workspaceId,
                    { workspaceId = it },
                    label = tr("import.workspace"),
                    title = tr("import.workspace"),
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (dest.isNotEmpty()) {
                Text(Format.abbreviatePath(dest), style = PaperTheme.typography.monoSmall, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
            if (active != null) {
                val t = task(active)
                Column(verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalAlignment = Alignment.CenterVertically) {
                        PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = c.accent)
                        Text(
                            t?.message?.takeIf { it.isNotEmpty() } ?: tr("vcs.clone.running"),
                            style = PaperTheme.typography.small,
                            color = c.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (t != null) PaperLink(tr("vcs.output"), onClick = { TaskLog.show(t.id) })
                    }
                    PaperProgressBar(t?.percent?.takeIf { it > 0 }?.let { (it / 100.0).toFloat() })
                }
            }
            r?.error?.let { PaperAlert(Tone.Danger, it) }
            Row(Modifier.fillMaxWidth().padding(top = sp.xs), horizontalArrangement = Arrangement.spacedBy(sp.md)) {
                PaperButton(if (busy) tr("vcs.mobile.clone.background") else tr("common.cancel"), onClick = { dismiss() }, modifier = Modifier.weight(1f))
                PaperButton(
                    tr("vcs.clone"),
                    onClick = {
                        running = null
                        clone(url.trim(), dest, branch.trim().ifEmpty { null }, workspaceId.ifEmpty { null })
                    },
                    kind = ButtonKind.Primary,
                    icon = "download",
                    loading = busy,
                    enabled = url.isNotBlank() && folder.isNotBlank() && parent.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
