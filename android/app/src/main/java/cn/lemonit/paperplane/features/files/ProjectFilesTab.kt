package cn.lemonit.paperplane.features.files

import androidx.compose.runtime.Composable
import cn.lemonit.paperplane.ui.shell.PendingPage

/** 工程详情的「文件」标签（对照 Mac Features/Files/FilesTab） */
@Composable
fun ProjectFilesTab(projectId: String) = PendingPage("文件")
