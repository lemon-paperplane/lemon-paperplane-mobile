package cn.lemonit.paperplane.features.vcs

import androidx.compose.runtime.Composable
import cn.lemonit.paperplane.ui.shell.PendingPage

/** 「更多 → 版本控制」：各工程仓库状态总览 */
@Composable
fun VcsHomePage() = PendingPage("版本控制")

/** 工程详情的「版本控制」标签（对照 Mac Features/VCS/VCSTabView） */
@Composable
fun ProjectVcsTab(projectId: String) = PendingPage("版本控制")

/** 克隆仓库并导入（顶栏「+」菜单） */
object VcsCloneFlow {
    fun start() {}
}
