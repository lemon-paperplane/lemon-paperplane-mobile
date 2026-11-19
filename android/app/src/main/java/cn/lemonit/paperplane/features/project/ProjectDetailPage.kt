package cn.lemonit.paperplane.features.project

import androidx.compose.runtime.Composable
import cn.lemonit.paperplane.core.ProjectRoute
import cn.lemonit.paperplane.ui.shell.PendingPage

/** 工程详情：概览 / 运行 / 文件 / 版本控制 / 配置 / 插件视图（对照 Mac Features/Project/ProjectDetailPage） */
@Composable
fun ProjectDetailPage(route: ProjectRoute) = PendingPage("工程详情")
