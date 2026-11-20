package cn.lemonit.paperplane.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cn.lemonit.paperplane.PaperplaneApp
import cn.lemonit.paperplane.kit.cancelTask
import cn.lemonit.paperplane.kit.createWorkspace
import cn.lemonit.paperplane.kit.deleteWorkspace
import cn.lemonit.paperplane.kit.installDependencies
import cn.lemonit.paperplane.kit.model.ProjectSummary
import cn.lemonit.paperplane.kit.model.Workspace
import cn.lemonit.paperplane.kit.moveProject
import cn.lemonit.paperplane.kit.reimportProject
import cn.lemonit.paperplane.kit.removeProject
import cn.lemonit.paperplane.kit.renameProject
import cn.lemonit.paperplane.kit.renameWorkspace
import cn.lemonit.paperplane.kit.restartRun
import cn.lemonit.paperplane.kit.startRun
import cn.lemonit.paperplane.kit.stopAll
import cn.lemonit.paperplane.kit.stopRun
import cn.lemonit.paperplane.kit.syncProject
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.PaperBottomSheet
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperRadio
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.theme.PaperTheme
import kotlinx.coroutines.launch

/** 用户操作的统一入口（按钮、菜单都走这里）。对照 Mac LemonPaperplane/Core/Actions.swift。 */
object Actions {
    private val model get() = AppModel

    // ---------------------------------------------------------------- 运行

    fun run(projectId: String, configId: String? = null) = model.perform(tr("action.run.failed")) { api ->
        val r = api.startRun(projectId, configId)
        model.runs.runs[r.id] = r
    }

    fun stop(runId: String) = model.perform(tr("action.stop.failed")) { api ->
        val r = api.stopRun(runId)
        model.runs.runs[r.id] = r
    }

    fun restart(runId: String) = model.perform(tr("action.restart.failed")) { api ->
        val r = api.restartRun(runId)
        model.runs.runs[r.id] = r
    }

    fun restart(projectId: String, configId: String) = model.perform(tr("action.restart.failed")) { api ->
        val r = api.restartRun(projectId, configId)
        model.runs.runs[r.id] = r
    }

    fun stopProject(projectId: String) = model.perform(tr("action.stop.failed")) { api -> api.stopAll(projectId = projectId) }

    fun stopWorkspace(workspaceId: String) = model.perform(tr("action.stop.failed")) { api -> api.stopAll(workspaceId = workspaceId) }

    fun stopAll() = model.perform(tr("action.stop.failed")) { api -> api.stopAll() }

    // ---------------------------------------------------------------- 工程

    fun reimport(projectId: String) = model.perform(tr("action.reimport.failed")) { api -> model.track(api.reimportProject(projectId)) }

    fun sync(projectId: String, moduleId: String? = null) =
        model.perform(tr("action.sync.failed")) { api -> model.track(api.syncProject(projectId, moduleId)) }

    fun installDeps(projectId: String, moduleId: String?, clean: Boolean = false) =
        model.perform(tr("action.deps.failed")) { api -> model.track(api.installDependencies(projectId, moduleId, clean)) }

    fun rename(p: ProjectSummary) {
        Dialogs.input(title = tr("project.rename"), label = tr("common.name"), initial = p.name, confirmText = tr("common.save")) { name ->
            val s = model.api().renameProject(p.id, name)
            model.data.projects[s.id] = s
        }
    }

    fun remove(p: ProjectSummary) {
        Dialogs.confirm(
            title = tr("project.remove.title", p.name),
            message = tr("project.remove.message"),
            confirmText = tr("common.remove"),
            danger = true,
        ) {
            model.api().removeProject(p.id)
            model.data.projects.remove(p.id)
            model.nav.removeWhere { it is ProjectRoute && it.projectId == p.id }
        }
    }

    fun move(projectId: String, workspaceId: String) = model.perform { api -> api.moveProject(projectId, workspaceId) }

    // ---------------------------------------------------------------- 工作区

    fun createWorkspace() {
        Dialogs.input(title = tr("workspace.new").trimEnd('…', '.'), label = tr("common.name"), confirmText = tr("common.create")) { name ->
            val ws = model.api().createWorkspace(name)
            if (model.data.workspaces.none { it.id == ws.id }) model.data.workspaces = model.data.workspaces + ws
            model.selectWorkspace(ws.id)
        }
    }

    fun renameWorkspace(ws: Workspace) {
        Dialogs.input(title = tr("workspace.rename"), label = tr("common.name"), initial = ws.name, confirmText = tr("common.save")) { name ->
            val n = model.api().renameWorkspace(ws.id, name)
            model.data.workspaces = model.data.workspaces.map { if (it.id == n.id) n else it }
        }
    }

    fun deleteWorkspace(ws: Workspace) {
        val others = model.data.workspaces.filter { it.id != ws.id }
        if (ws.projectIds.isEmpty() || others.isEmpty()) {
            Dialogs.confirm(
                title = tr("workspace.delete.title", ws.name),
                message = if (ws.projectIds.isEmpty()) null else tr("workspace.delete.message"),
                confirmText = tr("common.delete"),
                danger = true,
            ) {
                model.api().deleteWorkspace(ws.id)
                afterWorkspaceDeleted(ws.id)
            }
            return
        }
        // 有工程：选择移到哪个工作区，或一并移除（不删文件）
        Dialogs.present { dismiss ->
            var target by remember { mutableStateOf<String?>(others.first().id) }
            var busy by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val s = PaperTheme.spacing
            PaperBottomSheet(onDismiss = dismiss, title = tr("workspace.delete.title", ws.name)) { close ->
                Column(Modifier.fillMaxWidth().padding(horizontal = s.xl), verticalArrangement = Arrangement.spacedBy(s.md)) {
                    Text(tr("workspace.delete.message"), style = PaperTheme.typography.body, color = PaperTheme.colors.textSecondary)
                    Text(tr("workspace.delete.moveTo"), style = PaperTheme.typography.label, color = PaperTheme.colors.textSecondary)
                    for (o in others) PaperRadio(selected = target == o.id, onClick = { target = o.id }, label = o.name)
                    PaperRadio(selected = target == null, onClick = { target = null }, label = tr("workspace.delete.removeProjects"))
                    Row(Modifier.fillMaxWidth().padding(vertical = s.lg), horizontalArrangement = Arrangement.spacedBy(s.md)) {
                        PaperButton(tr("common.cancel"), onClick = close, kind = ButtonKind.Secondary, modifier = Modifier.weight(1f))
                        PaperButton(
                            tr("common.delete"),
                            onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        model.api().deleteWorkspace(ws.id, target)
                                        afterWorkspaceDeleted(ws.id)
                                        close()
                                    } catch (e: Exception) {
                                        Toasts.error(e)
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            kind = ButtonKind.Danger,
                            loading = busy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    private fun afterWorkspaceDeleted(id: String) {
        model.data.workspaces = model.data.workspaces.filter { it.id != id }
        if (model.nav.workspaceId == id) model.selectWorkspace(model.data.workspaces.firstOrNull()?.id)
    }

    /** 导入时的目标工作区：当前选中的，否则第一个 */
    val targetWorkspaceId: String? get() = model.nav.workspaceId ?: model.data.workspaces.firstOrNull()?.id

    // ---------------------------------------------------------------- 任务

    fun cancelTask(id: String) = model.perform { api -> api.cancelTask(id) }

    // ---------------------------------------------------------------- 系统

    private val context: Context get() = PaperplaneApp.instance

    /** 用系统浏览器打开（运行地址请用 RunLinks：转发的地址只能在 App 内置浏览器里打开） */
    fun openExternal(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toasts.error(e)
        }
    }

    fun copy(text: String, toast: String? = null) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
        // Android 13 起系统自己会提示已复制
        if (android.os.Build.VERSION.SDK_INT < 33 || toast != null) Toasts.success(toast ?: tr("common.copied"))
    }

    /** 读剪贴板文本（粘贴连接码等） */
    fun clipboardText(): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }
}
