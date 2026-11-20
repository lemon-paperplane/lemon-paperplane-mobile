package cn.lemonit.paperplane.features.vcs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.lemonit.paperplane.kit.Fixtures
import cn.lemonit.paperplane.kit.model.VCSBranches
import cn.lemonit.paperplane.kit.model.VCSCommit
import cn.lemonit.paperplane.kit.model.VCSDiff
import cn.lemonit.paperplane.kit.model.VCSDiffHunk
import cn.lemonit.paperplane.kit.model.VCSDiffLine
import cn.lemonit.paperplane.kit.model.VCSGraphEdge
import cn.lemonit.paperplane.kit.model.VCSGraphRow
import cn.lemonit.paperplane.kit.model.VCSOperationDetail
import cn.lemonit.paperplane.kit.model.VCSRef
import cn.lemonit.paperplane.kit.model.VCSRepoOverview
import cn.lemonit.paperplane.kit.model.VCSRoot
import cn.lemonit.paperplane.kit.model.VCSStatus
import cn.lemonit.paperplane.ui.i18n.I18n
import cn.lemonit.paperplane.ui.shell.LocalEdgeToEdge
import cn.lemonit.paperplane.ui.shell.LocalWideLayout
import cn.lemonit.paperplane.ui.shell.PageScaffold
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.PaperplaneTheme
import cn.lemonit.paperplane.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 版本控制页面截图（JVM 上用 Robolectric 渲染，Roborazzi 存 PNG，只供目测）。
 * 运行：./gradlew testDebugUnitTest --tests '*VcsScreenshotTest*'
 * 产物：app/build/outputs/roborazzi/vcs_<页>_<light|dark>.png
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class VcsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val status = Fixtures.decode("real/vcs.status.json", VCSStatus.serializer())
    private val branches = Fixtures.decode("real/vcs.branches.json", VCSBranches.serializer())

    private fun shot(name: String, mode: ThemeMode, locale: String = I18n.ZH, content: @Composable () -> Unit) {
        I18n.setSetting(locale)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PaperplaneTheme(mode = mode, systemBars = false) {
                CompositionLocalProvider(LocalEdgeToEdge provides false) {
                    Box(Modifier.fillMaxSize().background(PaperTheme.colors.bgContent).testTag("page")) { content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("page").captureRoboImage("build/outputs/roborazzi/vcs_${name}_${mode.key}.png")
    }

    private fun session(page: VcsPage, st: VCSStatus = status): VcsSession {
        val s = VcsSession("p1", "test", preview = true)
        s.setRootsForPreview(listOf(VCSRoot("/Users/me/Code/demo", ".", "git", "git")))
        s.page = page
        val repo = s.repo!!
        repo.setPreview(status = st, branches = branches)
        repo.log.setPreview(sampleLog())
        return s
    }

    /** 工程详情里的样子：标题栏 + 标签 + 版本控制内容 */
    @Composable
    private fun InProject(s: VcsSession) {
        PageScaffold(title = "demo", subtitle = "~/Code/demo", onBack = {}) { VcsSessionView(s) }
    }

    @Test fun changesLight() = shot("1_changes", ThemeMode.Light) { InProject(session(VcsPage.CHANGES)) }

    @Test fun changesDark() = shot("1_changes", ThemeMode.Dark) { InProject(session(VcsPage.CHANGES)) }

    @Test fun logLight() = shot("2_log", ThemeMode.Light) { InProject(session(VcsPage.LOG)) }

    @Test fun logDark() = shot("2_log", ThemeMode.Dark, locale = I18n.EN) { InProject(session(VcsPage.LOG)) }

    @Test fun branchesLight() = shot("3_branches", ThemeMode.Light) { InProject(session(VcsPage.BRANCHES)) }

    @Test
    fun conflictsLight() {
        val st = Fixtures.decode("real/vcs.status.conflict.json", VCSStatus.serializer())
            .copy(operation = "rebase", operationDetail = VCSOperationDetail(op = "rebase", step = 2, total = 3, headName = "feature/login", onto = "559f32d8", current = "e63f2bca"))
        shot("4_conflicts", ThemeMode.Light) { InProject(session(VcsPage.CONFLICTS, st)) }
    }

    @Test
    fun conflictsEnglishDark() {
        val st = Fixtures.decode("real/vcs.status.conflict.json", VCSStatus.serializer()).copy(operation = "merge")
        shot("4_conflicts_en", ThemeMode.Dark, locale = I18n.EN) { InProject(session(VcsPage.CONFLICTS, st)) }
    }

    /** 平板：变更列表 + 提交区在左，差异在右 */
    @Test
    @Config(qualifiers = "w1000dp-h700dp-xhdpi")
    fun tabletLight() = shot("7_tablet", ThemeMode.Light) {
        val s = session(VcsPage.CHANGES)
        CompositionLocalProvider(LocalWideLayout provides true) { InProject(s) }
    }

    @Test fun diffLight() = shot("5_diff", ThemeMode.Light) { DiffPage() }

    @Test fun diffDark() = shot("5_diff", ThemeMode.Dark) { DiffPage() }

    @Test fun homeLight() = shot("6_home", ThemeMode.Light) { HomePage() }

    @Test fun homeDark() = shot("6_home", ThemeMode.Dark) { HomePage() }

    @Composable
    private fun DiffPage() {
        val s = session(VcsPage.CHANGES)
        val repo = s.repo!!
        val t = VcsDiffTarget("src/server/handler.go", kind = VcsDiffKind.Working(VcsSide.UNSTAGED), status = "M")
        val d = sampleDiff()
        val layout = VcsDiffLayout.build(d, null, emptySet())
        PageScaffold(
            title = "handler.go",
            subtitle = "src/server",
            onBack = {},
            header = { VcsDiffInfoRow(t, d, layout, 0, false, onJump = {}) },
            bottomBar = workingBarPreview(repo, t),
        ) {
            VcsDiffBody(d, layout, path = t.path, hunkMode = VcsHunkMode.STAGE)
        }
    }

    private fun workingBarPreview(repo: VcsRepo, t: VcsDiffTarget): (@Composable () -> Unit)? = workingBarFor(repo, t)

    @Composable
    private fun HomePage() {
        val list = Fixtures.decode("real/vcs.overview.json", ListSerializer(VCSRepoOverview.serializer()))
        val entries = list.mapIndexed { i, o ->
            VcsHomeEntry(o.root, o.projectId, o.projectName, o.relPath, o.name, if (i < 2) "w1" else "w2", if (o.status == null) (o.error ?: "") else null)
        }
        val byRoot = list.associate { it.root to it.status }
        PageScaffold(title = "版本控制", onBack = {}) {
            Column {
                VcsHomeList(
                    entries, "", {}, onlyChanged = false, onOnlyChanged = {},
                    status = { byRoot[it.root] },
                    workspaceName = { if (it == "w1") "默认工作区" else "客户项目" },
                )
            }
        }
    }

    private fun sampleDiff(): VCSDiff {
        fun l(k: String, o: Int, n: Int, t: String) = VCSDiffLine(k, o, n, t)
        val h1 = VCSDiffHunk(
            "@@ -12,7 +12,9 @@ func (s *Server) handle(w http.ResponseWriter, r *http.Request) {", 12, 7, 12, 9,
            listOf(
                l(" ", 12, 12, "\tctx := r.Context()"),
                l(" ", 13, 13, "\tid := r.URL.Query().Get(\"id\")"),
                l("-", 14, 0, "\tif id == \"\" {"),
                l("+", 0, 14, "\tif id == \"\" || len(id) > maxIDLength {"),
                l("+", 0, 15, "\t\ts.metrics.Inc(\"bad_request\")"),
                l(" ", 15, 16, "\t\thttp.Error(w, \"bad id\", http.StatusBadRequest)"),
                l(" ", 16, 17, "\t\treturn"),
                l(" ", 17, 18, "\t}"),
                l("+", 0, 19, "\tlog.Printf(\"handle request id=%s remote=%s agent=%s\", id, r.RemoteAddr, r.UserAgent())"),
                l(" ", 18, 20, "\titem, err := s.store.Get(ctx, id)"),
            ),
        )
        val h2 = VCSDiffHunk(
            "@@ -40,6 +42,5 @@ func (s *Server) close() error {", 40, 6, 42, 5,
            listOf(
                l(" ", 40, 42, "\ts.mu.Lock()"),
                l(" ", 41, 43, "\tdefer s.mu.Unlock()"),
                l("-", 42, 0, "\tif s.closed {"),
                l("-", 43, 0, "\t\treturn nil"),
                l("-", 44, 0, "\t}"),
                l("+", 0, 44, "\tif s.closed { return nil }"),
                l(" ", 45, 45, "\ts.closed = true"),
            ),
        )
        return VCSDiff("src/server/handler.go", hunks = listOf(h1, h2), mode = "unstaged", context = 3, status = "M", additions = 4, deletions = 4)
    }

    /** 带合并的提交图：A 合并 B 所在的分支 */
    private fun sampleLog(): List<VCSCommit> {
        fun g(lane: Int, lanes: Int, vararg e: Pair<Int, Int>) = VCSGraphRow(lane, lanes, e.map { (f, t) -> VCSGraphEdge(f, t, if (f == 1 || t == 1) 1 else 0) }, lane)
        fun c(h: String, subject: String, author: String, date: String, graph: VCSGraphRow, refs: List<VCSRef> = emptyList(), parents: Int = 1) =
            VCSCommit(h.repeat(5).take(40), h.repeat(4).take(7), List(parents) { "p$it" }, author, null, date, subject, refs, graph)
        return listOf(
            c("a1", "Merge branch 'feature/login'", "Liu Ri", "2026-11-20T09:12:00+08:00", g(0, 2, 0 to 0, 0 to 1),
                listOf(VCSRef("HEAD", "head"), VCSRef("main", "local"), VCSRef("origin/main", "remote")), parents = 2),
            c("b2", "Improve login form validation", "Chen Yu", "2026-11-19T18:40:00+08:00", g(1, 2, 0 to 0, 1 to 1), listOf(VCSRef("feature/login", "local"))),
            c("c3", "Update dependencies", "Liu Ri", "2026-11-19T11:02:00+08:00", g(0, 2, 0 to 0, 1 to 1)),
            c("d4", "Add login page", "Chen Yu", "2026-11-18T16:20:00+08:00", g(1, 2, 0 to 0, 1 to 0)),
            c("e5", "Release 1.2.0", "Liu Ri", "2026-11-12T10:00:00+08:00", g(0, 1, 0 to 0), listOf(VCSRef("v1.2.0", "tag"))),
            c("f6", "Fix crash when the config file is missing", "Wang Fang", "2026-11-03T09:30:00+08:00", g(0, 1, 0 to 0)),
            c("g7", "Initial commit", "Liu Ri", "2026-10-28T20:00:00+08:00", g(0, 1)),
        )
    }
}
