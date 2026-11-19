package cn.lemonit.paperplane.features.runs

import androidx.compose.runtime.Composable
import cn.lemonit.paperplane.ui.shell.PendingPage

/** 「运行中」标签：全部运行中的服务与后台任务 */
@Composable
fun RunningTab() = PendingPage("运行中")

/** 工程详情的「运行」标签：运行配置选择 + 控制台（configId 为空时用默认配置） */
@Composable
fun ProjectRunTab(projectId: String, configId: String?) = PendingPage("运行")
