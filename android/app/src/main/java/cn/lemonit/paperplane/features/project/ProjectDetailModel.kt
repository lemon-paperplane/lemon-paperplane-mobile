package cn.lemonit.paperplane.features.project

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.kit.getProject
import cn.lemonit.paperplane.kit.model.Diagnosis
import cn.lemonit.paperplane.kit.model.Project
import cn.lemonit.paperplane.kit.model.ToolchainOverride
import cn.lemonit.paperplane.kit.projectProblems
import cn.lemonit.paperplane.kit.updateProjectSettings
import cn.lemonit.paperplane.ui.i18n.tr
import kotlinx.coroutines.CancellationException

/**
 * 工程详情的数据（对照 Mac ProjectDetailModel）：project.get + project.problems。
 * 收到 project.changed / 回到前台时由页面调 [reload]（合并 300 毫秒内的多次变化）。
 */
@Stable
class ProjectDetailModel(val projectId: String, initial: Project? = null) {
    var project by mutableStateOf(initial)
    var error by mutableStateOf<String?>(null)
        private set
    var problems by mutableStateOf<List<Diagnosis>>(emptyList())

    suspend fun reload() {
        try {
            project = AppModel.api().getProject(projectId)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (project == null) error = e.message ?: tr("common.failed")
        }
    }

    suspend fun loadProblems() {
        try {
            problems = AppModel.api().projectProblems(projectId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** 改工具链覆盖：null 表示改回自动 */
    fun setToolchainOverride(kind: String, o: ToolchainOverride?) = AppModel.perform(tr("settings.saveFailed")) { api ->
        project = api.updateProjectSettings(projectId, toolchains = mapOf(kind to o))
    }

    /** 整体替换工程环境变量 */
    suspend fun saveEnv(env: Map<String, String>) {
        project = AppModel.api().updateProjectSettings(projectId, env = env)
    }
}
