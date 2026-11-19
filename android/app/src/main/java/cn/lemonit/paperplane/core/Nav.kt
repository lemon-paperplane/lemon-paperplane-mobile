package cn.lemonit.paperplane.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// 导航：底部标签（手机）/ 侧栏（平板）选根页面，详情页压栈盖在上面（手机全屏、隐藏底栏；系统返回键出栈）。
// 页面就是一个 Route：自带 Content()，壳不需要知道各功能有哪些页面。跨功能会跳转的页面在 Routes.kt 里统一定义；
// 功能内部的二级页面由各功能自己实现 Route。

/** 手机底部标签（平板侧栏里同样这几项，「更多」展开成侧栏条目） */
enum class MainTab { PROJECTS, LAUNCH, RUNNING, TERMINAL, MORE }

/** 一个可压栈的页面 */
interface Route {
    /** 同一页面的唯一键：栈里已有同键页面时回到它，而不是再压一层 */
    val key: String

    @Composable
    fun Content()
}

class NavStore {
    var tab by mutableStateOf(MainTab.PROJECTS)
    /** 压栈的详情页（栈底在前） */
    val stack = mutableStateListOf<Route>()
    /** 当前工作区（工程、启动、终端标签都按它过滤）；按服务器分开记在偏好里 */
    var workspaceId by mutableStateOf<String?>(null)
    /** 工程 / 运行中列表的搜索词 */
    var search by mutableStateOf("")
    /** 平板：「更多」里选中的条目（工具链 / 插件 / 设置 / 版本控制 / 插件视图），手机上这些直接压栈 */
    var moreSelection by mutableStateOf<Route?>(null)

    val top: Route? get() = stack.lastOrNull()

    /** 打开一个页面；栈里已有同键页面时退回到它（并替换成新参数） */
    fun push(route: Route) {
        val i = stack.indexOfFirst { it.key == route.key }
        if (i >= 0) {
            while (stack.size > i + 1) stack.removeAt(stack.lastIndex)
            stack[i] = route
        } else {
            stack.add(route)
        }
    }

    /** 替换栈顶 */
    fun replace(route: Route) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        push(route)
    }

    /** 返回；栈已空时返回 false（交给系统处理） */
    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun popToRoot() = stack.clear()

    /** 去掉某些页面（例如工程被删除后关掉它的详情页） */
    fun removeWhere(predicate: (Route) -> Boolean) {
        stack.removeAll(predicate)
        if (moreSelection?.let(predicate) == true) moreSelection = null
    }

    fun selectTab(t: MainTab) {
        tab = t
        stack.clear()
    }

    // 常用跳转
    fun openProject(projectId: String, tab: ProjectTab = ProjectTab.OVERVIEW, configId: String? = null) =
        push(ProjectRoute(projectId, tab, configId))

    fun openRunConsole(projectId: String, configId: String) = openProject(projectId, ProjectTab.RUN, configId)

    /** 切换服务器：栈、工作区选择、搜索都属于上一台 */
    fun resetForServerSwitch() {
        stack.clear()
        moreSelection = null
        workspaceId = null
        search = ""
        if (tab != MainTab.MORE) tab = MainTab.PROJECTS
    }
}
