package cn.lemonit.paperplane.features.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.features.importflow.ImportFlow
import cn.lemonit.paperplane.features.resources.ResourceStore
import cn.lemonit.paperplane.kit.model.ProjectSummary
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.shell.LocalWideLayout
import cn.lemonit.paperplane.ui.theme.PaperTheme

/**
 * 「工程」标签：当前工作区的工程卡片（对照 Mac Features/Workspace/WorkspacePage.swift），按顶栏搜索过滤。
 * 手机一列，平板多列网格。没有标题栏（顶栏由壳提供）。
 */
@Composable
fun ProjectsTab() {
    LaunchedEffect(Unit) { ResourceStore.ensureStarted() }
    val data = AppModel.data
    val ws = data.workspace(AppModel.nav.workspaceId)
    if (ws == null) {
        Box(Modifier.fillMaxSize().background(PaperTheme.colors.bgContent)) {
            PaperEmptyState(
                "layers",
                tr("mobile.workspace.none"),
                Modifier.align(Alignment.Center),
                actionText = tr("workspace.new").trimEnd('…', '.'),
                actionIcon = "plus",
            ) { Actions.createWorkspace() }
        }
        return
    }
    val q = AppModel.nav.search.trim()
    val list = data.projects(ws.id).filter { p -> matches(p, q) }
    ProjectsContent(list, searching = q.isNotEmpty())
}

/** 搜索：名称、路径、技术标签 */
internal fun matches(p: ProjectSummary, q: String): Boolean =
    q.isEmpty() || p.name.contains(q, ignoreCase = true) || p.path.contains(q, ignoreCase = true) ||
        p.facets.any { it.name.contains(q, ignoreCase = true) }

/** 工程列表的外观（数据作为参数，截图测试直接画） */
@Composable
internal fun ProjectsContent(list: List<ProjectSummary>, searching: Boolean) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    Box(Modifier.fillMaxSize().background(c.bgContent)) {
        if (list.isEmpty()) {
            if (searching) {
                PaperEmptyState("search", tr("workspace.noMatch"), Modifier.align(Alignment.Center))
            } else {
                PaperEmptyState(
                    "folder-open",
                    tr("workspace.empty"),
                    Modifier.align(Alignment.Center),
                    actionText = tr("import.folder").trimEnd('…', '.'),
                    actionIcon = "folder-input",
                ) { ImportFlow.importFolder() }
            }
            return@Box
        }
        if (LocalWideLayout.current) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 320.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(sp.xl),
                horizontalArrangement = Arrangement.spacedBy(sp.lg),
                verticalArrangement = Arrangement.spacedBy(sp.lg),
            ) {
                items(list, key = { it.id }) { p -> ProjectCard(p) }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = sp.lg, vertical = sp.lg),
                verticalArrangement = Arrangement.spacedBy(sp.lg),
            ) {
                items(list, key = { it.id }) { p -> ProjectCard(p) }
            }
        }
    }
}
