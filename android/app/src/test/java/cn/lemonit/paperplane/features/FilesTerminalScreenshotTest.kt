package cn.lemonit.paperplane.features

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.features.files.FileSearchMode
import cn.lemonit.paperplane.features.files.FilesScreen
import cn.lemonit.paperplane.features.files.FilesState
import cn.lemonit.paperplane.features.terminal.TerminalKeyBar
import cn.lemonit.paperplane.features.terminal.TerminalSession
import cn.lemonit.paperplane.features.terminal.TerminalTab
import cn.lemonit.paperplane.kit.model.FSEntry
import cn.lemonit.paperplane.kit.model.FSGrepMatch
import cn.lemonit.paperplane.kit.model.FSGrepResult
import cn.lemonit.paperplane.kit.model.FSListResult
import cn.lemonit.paperplane.kit.model.ProjectSummary
import cn.lemonit.paperplane.kit.model.TerminalInfo
import cn.lemonit.paperplane.kit.model.Workspace
import cn.lemonit.paperplane.ui.i18n.I18n
import cn.lemonit.paperplane.ui.shell.PageTitleBar
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.PaperplaneTheme
import cn.lemonit.paperplane.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 文件标签与终端标签截图（只生成图片供目测）。WebView 截不了：终端页只截列表与辅助键栏。
 * 产物：app/build/outputs/roborazzi/feature_<名>_<light|dark>.png
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class FilesTerminalScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val project = ProjectSummary(id = "p1", name = "paperplane-web", path = "/home/dev/paperplane-web", workspaceId = "w1")

    private fun shot(name: String, mode: ThemeMode, content: @Composable () -> Unit) {
        I18n.setSetting(I18n.ZH)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PaperplaneTheme(mode = mode) {
                Box(Modifier.fillMaxSize().background(PaperTheme.colors.bgContent).testTag("page")) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("page").captureRoboImage("build/outputs/roborazzi/feature_${name}_${mode.key}.png")
    }

    private fun filesState(): FilesState = FilesState("p1").apply {
        listings[""] = FSListResult(
            listOf(
                FSEntry(".git", ".git", dir = true, hidden = true),
                FSEntry("node_modules", "node_modules", dir = true, ignored = true),
                FSEntry("public", "public", dir = true),
                FSEntry("scripts", "scripts", dir = true, symlink = true),
                FSEntry("src", "src", dir = true),
                FSEntry(".env.local", ".env.local", size = 220, mtime = "2026-11-02T10:20:00+08:00", hidden = true),
                FSEntry("index.html", "index.html", size = 1180, mtime = "2026-11-12T09:41:00+08:00"),
                FSEntry("package.json", "package.json", size = 1532, mtime = "2026-11-19T22:05:00+08:00"),
                FSEntry("README.md", "README.md", size = 4210, mtime = "2026-10-30T16:02:00+08:00"),
                FSEntry("run.sh", "run.sh", size = 312, mtime = "2026-11-18T11:30:00+08:00", exec = true, runnable = "script"),
                FSEntry("notes.txt", "notes.txt", size = 96, mtime = "2026-11-20T08:12:00+08:00"),
                FSEntry("vite.config.ts", "vite.config.ts", size = 640, mtime = "2026-11-15T14:48:00+08:00"),
            ),
        )
        git = mapOf("package.json" to "M", "notes.txt" to "?", "src/main.ts" to "M")
        changedDirs = setOf("src")
    }

    /** 模拟工程详情页的标题栏，看文件标签在页面里的样子 */
    @Composable
    private fun ProjectFrame(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().background(PaperTheme.colors.bgChrome)) {
                PageTitleBar(title = project.name, subtitle = "文件", onBack = {})
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }

    @Test
    fun filesLight() = shot("files", ThemeMode.Light) { ProjectFrame { FilesScreen(filesState(), project) } }

    @Test
    fun filesDark() = shot("files", ThemeMode.Dark) { ProjectFrame { FilesScreen(filesState(), project) } }

    @Test
    fun grepLight() {
        val st = FilesState("p1").apply {
            searching = true
            mode = FileSearchMode.CONTENT
            query = "useState"
            grep = FSGrepResult(
                listOf(
                    FSGrepMatch("src/App.tsx", 1, 10, "import { useState, useEffect } from \"react\";"),
                    FSGrepMatch("src/App.tsx", 12, 23, "  const [count, setCount] = useState(0);"),
                    FSGrepMatch("src/components/Panel.tsx", 3, 10, "import { useState } from \"react\";"),
                    FSGrepMatch("src/components/Panel.tsx", 18, 25, "  const [open, setOpen] = useState(false);"),
                    FSGrepMatch("src/hooks/useServer.ts", 7, 30, "  const [server, setServer] = useState<Server | null>(null);"),
                ),
            )
        }
        shot("grep", ThemeMode.Light) { ProjectFrame { FilesScreen(st, project) } }
    }

    private fun setUpTerminals() {
        AppModel.data.projects["p1"] = project
        AppModel.data.projects["p2"] = ProjectSummary(id = "p2", name = "api-server", path = "/home/dev/api-server", workspaceId = "w1")
        AppModel.data.workspaces = listOf(Workspace("w1", "日常", listOf("p1", "p2")))
        AppModel.nav.workspaceId = "w1"
        AppModel.terminals.list = listOf(
            TerminalInfo(id = "t1", workspaceId = "w1", projectId = "p1", name = "paperplane-web", cwd = "/home/dev/paperplane-web"),
            TerminalInfo(id = "t2", workspaceId = "w1", projectId = "p2", name = "go test", cwd = "/home/dev/api-server/internal"),
            TerminalInfo(id = "t3", workspaceId = "w1", projectId = "p2", name = "api-server", cwd = "/home/dev/api-server", exited = true, exitCode = 130),
        )
    }

    @Composable
    private fun TerminalsWithKeyBar() {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { TerminalTab() }
            TerminalKeyBar(TerminalSession(), keyboardVisible = true, onPaste = {})
        }
    }

    @Test
    fun terminalsLight() {
        setUpTerminals()
        shot("terminals", ThemeMode.Light) { TerminalsWithKeyBar() }
    }

    @Test
    fun terminalsDark() {
        setUpTerminals()
        shot("terminals", ThemeMode.Dark) { TerminalsWithKeyBar() }
    }
}
