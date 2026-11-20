package cn.lemonit.paperplane.features.project

import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.features.forms.FormSheets
import cn.lemonit.paperplane.kit.getProject
import cn.lemonit.paperplane.kit.invokeAction
import cn.lemonit.paperplane.kit.model.ActionInfo
import cn.lemonit.paperplane.kit.model.FormField
import cn.lemonit.paperplane.kit.model.Module
import cn.lemonit.paperplane.kit.model.Option
import cn.lemonit.paperplane.kit.model.Project
import cn.lemonit.paperplane.kit.model.stringValue
import cn.lemonit.paperplane.kit.util.IconSource
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 插件贡献的工程动作（project.menu、project.toolbar、card.menu、run.toolbar 等位置）：按 facet / 插件筛选、生成菜单项、
 * 执行（多模块时先选模块 → 确认 → 有参数先弹表单 → project.action，跟踪返回的任务）。对照 Mac Actions.invoke / menuEntries。
 */
object PluginActions {
    /** 动作是否适用于这个工程：声明了 facet 的看 facet 是否有交集，没声明的看插件是否参与了这个工程 */
    fun applicable(actions: List<ActionInfo>, facets: Set<String>, plugins: Set<String>): List<ActionInfo> =
        actions.filter { a -> if (a.facets.isEmpty()) a.plugin in plugins else a.facets.any { it in facets } }

    /** 某个位置上对这个工程适用的动作（工程的 facet / 插件取卡片摘要；有完整工程时用另一个重载） */
    fun actions(location: String, projectId: String): List<ActionInfo> {
        val s = AppModel.data.projects[projectId] ?: return emptyList()
        return applicable(AppModel.data.contributions.actions(location), s.facets.map { it.id }.toSet(), s.facets.map { it.plugin }.toSet())
    }

    /** 某个位置上对这个工程适用的动作（按完整工程的全部模块 facet / 参与插件） */
    fun actions(location: String, project: Project): List<ActionInfo> =
        applicable(AppModel.data.contributions.actions(location), project.facetIds, project.pluginIds)

    /** 某个位置（ViewLocation / ActionLocation）上对这个工程适用的动作菜单项 */
    fun menuEntries(location: String, projectId: String, moduleId: String? = null): List<MenuEntry> =
        menuEntries(actions(location, projectId), projectId, moduleId)

    /** 把动作做成菜单项（插件自带图标的用 zap 代替：菜单只画内置图标） */
    fun menuEntries(actions: List<ActionInfo>, projectId: String, moduleId: String? = null, runId: String? = null): List<MenuEntry> =
        actions.map { a ->
            val icon = (IconSource.parse(a.icon, a.plugin) as? IconSource.Builtin)?.name ?: "zap"
            MenuEntry.Item(a.title, icon) { invoke(a, projectId, moduleId, runId) }
        }

    /** 执行插件动作：确定模块 → 确认 → 表单 → project.action */
    fun invoke(action: ActionInfo, projectId: String, moduleId: String? = null, runId: String? = null) {
        AppModel.scope.launch {
            try {
                val api = AppModel.api()
                if (moduleId != null) {
                    continueInvoke(action, projectId, moduleId, runId, null)
                    return@launch
                }
                val p = api.getProject(projectId)
                val candidates = p.modules.filter { m ->
                    m.hasAnyFacet(action.facets) && (action.facets.isNotEmpty() || m.facets.any { it.plugin == action.plugin } || m.analysis.containsKey(action.plugin))
                }
                if (candidates.size > 1) {
                    pickModule(candidates, action.title) { picked -> continueInvoke(action, projectId, picked, runId, p) }
                    return@launch
                }
                val mid = candidates.firstOrNull()?.id ?: p.modules.firstOrNull()?.id ?: return@launch
                continueInvoke(action, projectId, mid, runId, p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toasts.error(e, action.title)
            }
        }
    }

    private fun continueInvoke(action: ActionInfo, projectId: String, moduleId: String, runId: String?, project: Project?) {
        val send: suspend (Map<String, JsonElement>?) -> Unit = { args ->
            val t = AppModel.api().invokeAction(projectId, moduleId, action.plugin, action.id, args, runId)
            AppModel.track(t)
        }
        when {
            action.form.isNotEmpty() -> {
                // 带 optionsFrom 的下拉框取模块分析结果里的动态选项（如 iOS 插件的 scheme 列表）
                if (action.form.none { !it.optionsFrom.isNullOrEmpty() }) {
                    FormSheets.show(action.title, action.form, message = action.confirm, confirmText = action.title) { v -> send(v) }
                    return
                }
                AppModel.scope.launch {
                    val p = project ?: runCatching { AppModel.api().getProject(projectId) }.getOrNull()
                    val opts: Map<String, List<Option>> = (p?.module(moduleId) ?: p?.modules?.firstOrNull())?.optionValues.orEmpty()
                    FormSheets.show(action.title, action.form, message = action.confirm, confirmText = action.title, optionValues = opts) { v -> send(v) }
                }
            }
            !action.confirm.isNullOrEmpty() -> Dialogs.confirm(action.title, message = action.confirm, confirmText = action.title) { send(null) }
            else -> AppModel.perform(action.title) { send(null) }
        }
    }

    /** 多个模块都适用时先选模块 */
    fun pickModule(modules: List<Module>, title: String, then: (String) -> Unit) {
        val field = FormField(
            key = "module",
            title = tr("project.module"),
            type = "select",
            options = modules.map { Option(it.id, if (it.relDir.isEmpty()) it.name else "${it.name}  (${it.relDir})") },
            required = true,
        )
        FormSheets.show(title, listOf(field), values = mapOf("module" to JsonPrimitive(modules[0].id)), confirmText = tr("common.continue")) { v ->
            v["module"]?.stringValue?.let { id -> AppModel.scope.launch { then(id) } }
        }
    }
}
