package cn.lemonit.paperplane.features

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.features.importflow.PickerState
import cn.lemonit.paperplane.features.importflow.ServerPathPickerPage
import cn.lemonit.paperplane.features.launch.LaunchContent
import cn.lemonit.paperplane.features.launch.launchGroups
import cn.lemonit.paperplane.features.resources.ResourceStore
import cn.lemonit.paperplane.features.runs.ConsoleState
import cn.lemonit.paperplane.features.runs.RunTabContent
import cn.lemonit.paperplane.features.runs.RunningContent
import cn.lemonit.paperplane.features.workspace.ProjectsContent
import cn.lemonit.paperplane.kit.model.ConfigBrief
import cn.lemonit.paperplane.kit.model.Diagnosis
import cn.lemonit.paperplane.kit.model.FSBrowseEntry
import cn.lemonit.paperplane.kit.model.FSBrowseResult
import cn.lemonit.paperplane.kit.model.FacetBadge
import cn.lemonit.paperplane.kit.model.Fix
import cn.lemonit.paperplane.kit.model.InfoItem
import cn.lemonit.paperplane.kit.model.LastRun
import cn.lemonit.paperplane.kit.model.LogLine
import cn.lemonit.paperplane.kit.model.ProjectSummary
import cn.lemonit.paperplane.kit.model.ResourceSample
import cn.lemonit.paperplane.kit.model.Run
import cn.lemonit.paperplane.kit.model.RunBrief
import cn.lemonit.paperplane.kit.model.TaskInfo
import cn.lemonit.paperplane.kit.model.VCSBrief
import cn.lemonit.paperplane.ui.i18n.I18n
import cn.lemonit.paperplane.ui.shell.LocalWideLayout
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.PaperplaneTheme
import cn.lemonit.paperplane.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import androidx.compose.foundation.background

/**
 * 工程、运行、启动、选择服务器路径页面的截图（假数据，JVM 上渲染，只供目测）。
 * 运行：./gradlew testDebugUnitTest --tests '*WorkspaceScreenshotTest*'
 * 产物：app/build/outputs/roborazzi/workspace_<页>_<light|dark>.png
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class WorkspaceScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    // 同一个测试类里各用例共用资源数据（ResourceStore 是全局的），时间基准取一次，免得各用例的采样混在一起
    private val now = NOW

    private fun shot(name: String, mode: ThemeMode, content: @Composable () -> Unit) {
        I18n.setSetting(I18n.ZH)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PaperplaneTheme(mode = mode) {
                Box(Modifier.fillMaxSize().background(PaperTheme.colors.bgContent).testTag("page")) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("page").captureRoboImage("build/outputs/roborazzi/workspace_${name}_${mode.key}.png")
    }

    private fun samples(runId: String, base: Double, wave: Double): List<ResourceSample> =
        (0 until 120).map { i -> ResourceSample(now - (120 - i) * 2000L, 3 + 6 * kotlin.math.abs(kotlin.math.sin(i / 7.0)), base + wave * kotlin.math.sin(i / 11.0) + i * 0.4) }

    private val web = ProjectSummary(
        id = "p1", name = "shop-web", path = "/Users/dev/work/shop-web", workspaceId = "w1",
        facets = listOf(FacetBadge("vite", "Vite", plugin = "node"), FacetBadge("ts", "TypeScript", plugin = "node"), FacetBadge("npm", "npm", plugin = "node")),
        info = listOf(InfoItem("Node", "20.18"), InfoItem("包管理", "pnpm")),
        status = "running",
        runs = listOf(RunBrief("r1", "p1", "c1", "dev", "running", listOf("http://localhost:5173/"), 4.2, 186.0)),
        configs = listOf(ConfigBrief("c1", "dev", "npm"), ConfigBrief("c2", "build", "npm")),
        defaultConfigId = "c1",
        launch = listOf("c1"),
        vcs = VCSBrief("feature/checkout-redesign", ahead = 2, behind = 1, changes = 5),
    )
    private val api = ProjectSummary(
        id = "p2", name = "order-service", path = "/Users/dev/work/order-service", workspaceId = "w1",
        facets = listOf(FacetBadge("gradle", "Gradle", plugin = "java"), FacetBadge("spring", "Spring Boot", plugin = "java")),
        status = "failed",
        configs = listOf(ConfigBrief("c3", "bootRun", "gradle")),
        defaultConfigId = "c3",
        launch = listOf("c3"),
        launchAlias = mapOf("c3" to "订单服务"),
        problems = 2,
        problemSeverity = "error",
        missingToolchains = listOf("java"),
        lastRun = LastRun("c3", "bootRun", Instant.ofEpochMilli(now - 3 * 3600_000L), exitCode = 1, failed = true),
        vcs = VCSBrief("main", conflicts = 1),
    )
    private val tools = ProjectSummary(
        id = "p3", name = "scripts", path = "/Users/dev/work/scripts", workspaceId = "w1",
        facets = listOf(FacetBadge("python", "Python", plugin = "python")),
        status = "idle",
        configs = listOf(ConfigBrief("c4", "sync.py", "script")),
        lastRun = LastRun("c4", "sync.py", Instant.ofEpochMilli(now - 26 * 3600_000L)),
        busy = "正在安装依赖…",
        dependencies = "missing",
    )

    private val run1 = Run(
        id = "r1", projectId = "p1", configId = "c1", name = "dev", state = "running",
        startedAt = Instant.ofEpochMilli(now - 754_000L), urls = listOf("http://localhost:5173/"), ports = listOf(5173),
        cpu = 4.2, rssMB = 186.0, peakRSSMB = 240.0, procs = 3,
    )
    private val run2 = Run(
        id = "r2", projectId = "p2", configId = "c3", name = "bootRun", state = "starting",
        startedAt = Instant.ofEpochMilli(now - 12_000L), cpu = 88.0, rssMB = 512.0,
    )

    private fun seed() {
        AppModel.data.projects.clear()
        for (p in listOf(web, api, tools)) AppModel.data.projects[p.id] = p
        AppModel.runs.runs.clear()
        AppModel.runs.runs[run1.id] = run1
        AppModel.runs.runs[run2.id] = run2
        ResourceStore.resources("r1").seed(samples("r1", 150.0, 20.0), peak = 240.0, procs = 3)
        ResourceStore.resources("r2").seed(samples("r2", 420.0, 60.0), peak = 600.0, procs = 1, state = "starting")
        ResourceStore.seedTotals(ResourceStore.Totals(projects = 2, runs = 2, rssMB = 698.0, cpu = 92.2))
    }

    /** 平板宽屏：多列网格 */
    @Test
    @Config(qualifiers = "w1000dp-h640dp-mdpi")
    fun projectsTablet() {
        seed()
        shot("6_projects_tablet", ThemeMode.Light) {
            CompositionLocalProvider(LocalWideLayout provides true) { ProjectsContent(listOf(web, api, tools), searching = false) }
        }
    }

    @Test fun projectsLight() = projects(ThemeMode.Light)
    @Test fun projectsDark() = projects(ThemeMode.Dark)

    private fun projects(mode: ThemeMode) {
        seed()
        shot("1_projects", mode) { ProjectsContent(listOf(web, api, tools), searching = false) }
    }

    private fun consoleLines(): List<LogLine> {
        val out = ArrayList<LogLine>()
        var seq = 1L
        fun add(text: String, s: String = "stdout") = out.add(LogLine(seq++, now, s, text))
        add("$ pnpm dev", "system")
        add("")
        add("> shop-web@1.4.0 dev /Users/dev/work/shop-web")
        add("> vite --host")
        add("")
        add("  \u001B[32m\u001B[1mVITE\u001B[22m v5.4.10\u001B[39m  ready in \u001B[1m412\u001B[22m ms")
        add("")
        add("  \u001B[32m➜\u001B[39m  \u001B[1mLocal\u001B[22m:   \u001B[36mhttp://localhost:\u001B[1m5173\u001B[22m/\u001B[39m")
        add("  \u001B[32m➜\u001B[39m  \u001B[1mNetwork\u001B[22m: \u001B[36mhttp://192.168.1.20:\u001B[1m5173\u001B[22m/\u001B[39m")
        add("\u001B[2m10:21:07\u001B[22m \u001B[36m[vite]\u001B[39m \u001B[32mhmr update \u001B[39m\u001B[2m/src/pages/Checkout.vue\u001B[22m")
        add("\u001B[33mwarning\u001B[39m: Unused variable 'total' in src/pages/Checkout.vue:48")
        add("Error: Request failed with status code 502 (GET /api/cart)", "stderr")
        add("    at fetchCart (src/api/cart.ts:17:11)", "stderr")
        for (i in 1..14) add("\u001B[2m10:21:${10 + i}\u001B[22m \u001B[36m[vite]\u001B[39m page reload \u001B[2msrc/components/Item$i.vue\u001B[22m")
        return out
    }

    @Test fun runTabLight() = runTab(ThemeMode.Light)
    @Test fun runTabDark() = runTab(ThemeMode.Dark)

    private fun runTab(mode: ThemeMode) {
        seed()
        val console = ConsoleState.preview(consoleLines())
        console.query = "vite"
        val r = run1.copy(
            diagnoses = listOf(
                Diagnosis(
                    id = "d1", severity = "warning", title = "端口 5173 已被占用，改用了 5174",
                    message = "另一个进程（node，PID 4182）在监听这个端口。",
                    fixes = listOf(Fix("f1", "结束占用端口的进程", "killPort", recommended = true), Fix("f2", "固定使用 5174", "setRunOption")),
                ),
            ),
        )
        shot("2_run", mode) { RunTabContent(web, "c1", r, onSelect = {}, console = console) }
    }

    @Test fun runningLight() {
        seed()
        val tasks = listOf(
            TaskInfo("t1", "install", "安装依赖：scripts", "p3", state = "running", message = "pip install -r requirements.txt", percent = -1.0),
            TaskInfo("t2", "sync", "同步 Gradle：order-service", "p2", state = "failed", error = "Could not resolve org.springframework.boot:3.3.4"),
            TaskInfo("t3", "import", "导入 shop-web", "p1", state = "succeeded", message = "识别出 3 个运行配置"),
        )
        shot("3_running", ThemeMode.Light) { RunningContent(listOf(run1, run2), tasks, ResourceStore.totals, searching = false) }
    }

    @Test fun launchDark() {
        seed()
        val groups = launchGroups(listOf(web, api, tools))
        shot("4_launch", ThemeMode.Dark) {
            LaunchContent(
                workspaceId = "w1",
                all = groups,
                groups = groups,
                activeRun = { pid, cid -> listOf(run1, run2).firstOrNull { it.projectId == pid && it.configId == cid } },
                latestRun = { pid, cid -> listOf(run1, run2).firstOrNull { it.projectId == pid && it.configId == cid } },
            )
        }
    }

    @Test fun pickerLight() {
        seed()
        val st = PickerState(files = false)
        st.listing = FSBrowseResult(
            path = "/Users/dev/work",
            parent = "/Users/dev",
            home = "/Users/dev",
            sep = "/",
            roots = listOf("/"),
            entries = listOf(
                FSBrowseEntry(".cache", "/Users/dev/work/.cache", dir = true, hidden = true),
                FSBrowseEntry("order-service", "/Users/dev/work/order-service", dir = true),
                FSBrowseEntry("scripts", "/Users/dev/work/scripts", dir = true),
                FSBrowseEntry("shop-web", "/Users/dev/work/shop-web", dir = true),
                FSBrowseEntry("notes.md", "/Users/dev/work/notes.md", dir = false, size = 2048),
            ),
        )
        shot("5_picker", ThemeMode.Light) { ServerPathPickerPage("导入文件夹", st, null, onBack = {}, onChoose = {}) }
    }

    companion object {
        private val NOW = System.currentTimeMillis()
    }
}
