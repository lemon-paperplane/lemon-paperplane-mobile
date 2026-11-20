package cn.lemonit.paperplane.features.launch

import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.kit.launchAdd
import cn.lemonit.paperplane.kit.launchAddFile
import cn.lemonit.paperplane.kit.launchRemove
import cn.lemonit.paperplane.kit.launchRun
import cn.lemonit.paperplane.kit.launchStop
import cn.lemonit.paperplane.kit.model.LaunchRunError
import cn.lemonit.paperplane.ui.components.ToastKind
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.tr

/** 启动面板的操作（对照 Mac LaunchBoard.swift 里的 extension Actions）。数据存在服务器内核（launch.*），变化经 project.changed 推回来。 */
object LaunchActions {
    /** 加入或移出工作区「启动」 */
    fun setLaunch(projectId: String, configIds: List<String>, on: Boolean) = AppModel.perform { api ->
        if (on) api.launchAdd(projectId, configIds) else api.launchRemove(projectId, configIds)
    }

    /** 把工程里的脚本 / 可执行文件加到启动（path 相对工程根），文件列表的长按菜单也可以调 */
    fun addFile(projectId: String, path: String) = AppModel.perform { api ->
        val r = api.launchAddFile(projectId, path)
        Toasts.success(if (r.created) tr("launch.fileAdded", r.name) else tr("launch.fileAlready", r.name))
    }

    /** 运行启动面板里还没在运行的项（可限定到一个工程） */
    fun runAll(workspaceId: String, projectId: String? = null) = AppModel.perform(tr("action.run.failed")) { api ->
        val res = api.launchRun(workspaceId = workspaceId, projectId = projectId)
        for (r in res.started) AppModel.runs.runs[r.id] = r
        reportErrors(res.errors)
    }

    /** 停止启动面板里正在运行的项（可限定到一个工程） */
    fun stopAll(workspaceId: String, projectId: String? = null) = AppModel.perform(tr("action.stop.failed")) { api ->
        val res = api.launchStop(workspaceId = workspaceId, projectId = projectId)
        reportErrors(res.errors)
    }

    private fun reportErrors(errors: List<LaunchRunError>) {
        val first = errors.firstOrNull() ?: return
        val name = AppModel.data.projects[first.projectId]?.configs?.firstOrNull { it.id == first.configId }?.name ?: first.configId
        Toasts.show(tr("launch.someFailed", errors.size), ToastKind.Error, "$name: ${first.error}")
    }
}
