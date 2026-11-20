package cn.lemonit.paperplane.features.project

import cn.lemonit.paperplane.kit.model.ActionInfo
import cn.lemonit.paperplane.ui.components.MenuEntry

/** 插件贡献的工程动作（project.menu 等位置）：按 facet / 插件筛选、生成菜单项、执行（有参数时先弹表单）。对照 Mac Actions.invoke / menuEntries */
object PluginActions {
    fun invoke(action: ActionInfo, projectId: String, moduleId: String? = null, runId: String? = null) {}

    /** 某个位置（ViewLocation / ActionLocation）上对这个工程适用的动作菜单项 */
    fun menuEntries(location: String, projectId: String, moduleId: String? = null): List<MenuEntry> = emptyList()
}
