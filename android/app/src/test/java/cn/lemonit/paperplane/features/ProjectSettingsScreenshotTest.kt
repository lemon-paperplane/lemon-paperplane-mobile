package cn.lemonit.paperplane.features

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.ProjectRoute
import cn.lemonit.paperplane.core.ServerEntry
import cn.lemonit.paperplane.core.ServerPhase
import cn.lemonit.paperplane.features.plugins.PluginViewBody
import cn.lemonit.paperplane.features.plugins.PluginViewModel
import cn.lemonit.paperplane.features.project.ProjectDetailModel
import cn.lemonit.paperplane.features.project.ProjectDetailScreen
import cn.lemonit.paperplane.features.project.RunConfigEditMode
import cn.lemonit.paperplane.features.project.RunConfigEditPage
import cn.lemonit.paperplane.features.project.RunConfigEditState
import cn.lemonit.paperplane.features.settings.AppAppearanceGroup
import cn.lemonit.paperplane.features.settings.ConnectionCodeBody
import cn.lemonit.paperplane.features.settings.HostModel
import cn.lemonit.paperplane.features.settings.HostSection
import cn.lemonit.paperplane.features.settings.ServersGroup
import cn.lemonit.paperplane.features.toolchains.ToolchainsContent
import cn.lemonit.paperplane.kit.Fixtures
import cn.lemonit.paperplane.kit.ViewContext
import cn.lemonit.paperplane.kit.model.Contributions
import cn.lemonit.paperplane.kit.model.Diagnosis
import cn.lemonit.paperplane.kit.model.Fix
import cn.lemonit.paperplane.kit.model.Project
import cn.lemonit.paperplane.kit.model.ProjectSummary
import cn.lemonit.paperplane.kit.model.RemoteAuditEntry
import cn.lemonit.paperplane.kit.model.RemoteKey
import cn.lemonit.paperplane.kit.model.RemoteKeyCreated
import cn.lemonit.paperplane.kit.model.RemoteStatus
import cn.lemonit.paperplane.kit.model.RemoteUser
import cn.lemonit.paperplane.kit.model.Toolchain
import cn.lemonit.paperplane.kit.model.ToolchainKind
import cn.lemonit.paperplane.kit.model.VCSBrief
import cn.lemonit.paperplane.kit.model.ViewRenderResult
import cn.lemonit.paperplane.ui.components.PaperSheetPreview
import cn.lemonit.paperplane.ui.i18n.I18n
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.shell.LocalEdgeToEdge
import cn.lemonit.paperplane.ui.shell.LocalPageBack
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.PaperplaneTheme
import cn.lemonit.paperplane.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 功能 2 页面截图（工程详情概览、运行配置编辑、设置里的服务器与远程连接、连接码面板、插件声明式界面）。
 * 用 Mac 版夹具当假数据，页面里的接口调用因为没连服务器会失败（页面按「已有数据」显示）。
 * 运行：./gradlew testDebugUnitTest --tests '*ProjectSettingsScreenshotTest*'
 * 产物：app/build/outputs/roborazzi/feature_<页>_<light|dark>.png（只供目测）
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h1300dp-xhdpi")
class ProjectSettingsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, mode: ThemeMode, content: @Composable () -> Unit) {
        I18n.setSetting(I18n.ZH)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PaperplaneTheme(mode = mode) {
                Box(Modifier.fillMaxSize().background(PaperTheme.colors.bgContent).testTag("page")) {
                    CompositionLocalProvider(LocalPageBack provides {}, LocalEdgeToEdge provides false) { content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("page").captureRoboImage("build/outputs/roborazzi/feature_${name}_${mode.key}.png")
    }

    private fun seedProject(): Project {
        val summary = Fixtures.decode("real/project.list.json", ListSerializer(ProjectSummary.serializer())).first()
        AppModel.data.projects[summary.id] = summary.copy(
            problems = 2,
            problemSeverity = "warning",
            vcs = VCSBrief(branch = "main", changes = 3),
            missingToolchains = listOf("node"),
        )
        AppModel.data.contributions = Fixtures.decode("real/ui.contributions.json", Contributions.serializer())
        AppModel.data.toolchainKinds = Fixtures.decode("real/toolchain.kinds.json", ListSerializer(ToolchainKind.serializer()))
        AppModel.data.toolchains = Fixtures.decode("real/toolchain.list.json", ListSerializer(Toolchain.serializer()))
        AppModel.data.loaded = true
        return Fixtures.decode("real/project.get.json", Project.serializer())
    }

    @Test
    @Config(qualifiers = "w400dp-h1500dp-xhdpi")
    fun projectOverview() {
        val project = seedProject()
        val model = ProjectDetailModel(project.id, project)
        model.problems = listOf(
            Diagnosis(
                id = "d1",
                severity = "warning",
                title = "依赖比锁文件旧",
                message = "uv.lock 在上次安装后改过，运行前需要重新安装依赖。",
                fixes = listOf(Fix(id = "install", title = "安装依赖", recommended = true), Fix(id = "ignore", title = "忽略")),
            ),
            Diagnosis(id = "d2", severity = "info", title = "没有 .python-version", message = "按 pyproject.toml 的 requires-python 选择解释器。"),
        )
        shot("project_overview", ThemeMode.Light) { ProjectDetailScreen(ProjectRoute(project.id), model) }
    }

    @Test
    fun runConfigEdit() {
        val project = seedProject()
        val cfg = project.allRunConfigs.first()
        val state = RunConfigEditState(project, RunConfigEditMode.EDIT, cfg)
        shot("runconfig_edit", ThemeMode.Dark) { RunConfigEditPage(state) }
    }

    @Test
    @Config(qualifiers = "w400dp-h2200dp-xhdpi")
    fun settingsHost() {
        val host = HostModel().apply {
            status = Fixtures.decode("kernel/remote.status.json", RemoteStatus.serializer())
            keys = Fixtures.decode("kernel/remote.keys.json", ListSerializer(RemoteKey.serializer()))
            users = Fixtures.decode("kernel/remote.users.json", ListSerializer(RemoteUser.serializer()))
            audit = Fixtures.decode("kernel/remote.audit.json", ListSerializer(RemoteAuditEntry.serializer()))
        }
        val servers = listOf(
            ServerEntry(name = "Mac mini", urls = listOf("https://192.168.31.99:47290"), fingerprint = "sha256:0f1e2d3c4b5a6978", os = "darwin"),
            ServerEntry(name = "家里的 Linux", urls = listOf("https://nas.local:47290"), fingerprint = "sha256:9a8b7c6d5e4f3a2b", os = "linux"),
        )
        shot("settings_host", ThemeMode.Light) {
            val sp = PaperTheme.spacing
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(sp.lg), verticalArrangement = Arrangement.spacedBy(sp.xxl)) {
                AppAppearanceGroup()
                ServersGroup(servers, servers[0].id, ServerPhase.Ready)
                HostSection(host, "Mac mini")
            }
        }
    }

    @Test
    fun connectionCode() {
        val created = Fixtures.decode("kernel/remote.keyCreate.json", RemoteKeyCreated.serializer())
        val code = "ppc1_eyJ1IjpbImh0dHBzOi8vMTkyLjE2OC4zMS45OTo0NzI5MCJdLCJmIjoic2hhMjU2OjBmMWUyZDNjIiwidCI6InBwa18xMjM0In0"
        shot("connection_code", ThemeMode.Dark) {
            Column(Modifier.padding(top = PaperTheme.spacing.huge)) {
                PaperSheetPreview(title = tr("host.keys.created"), showClose = false) {
                    ConnectionCodeBody(created.copy(code = code, qrSvg = qrSvg(code)), onClose = {})
                }
            }
        }
    }

    @Test
    fun pluginView() {
        seedProject()
        val r = Fixtures.decode("real/view.render.json", ViewRenderResult.serializer())
        val model = PluginViewModel(ViewContext("python", "packages", "3afc9005b05d5550")).apply { root = r.root }
        shot("plugin_view", ThemeMode.Light) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PaperTheme.spacing.xl)) {
                PluginViewBody(model, scrolls = false)
            }
        }
    }

    @Test
    fun toolchains() {
        seedProject()
        shot("toolchains", ThemeMode.Dark) { ToolchainsContent(AppModel.data.toolchainKinds) }
    }

    /** 和内核 remote.QRSVG 一样的格式：黑块、白底、四格静区 */
    private fun qrSvg(text: String): String {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 4))
        val n = m.width
        val path = StringBuilder()
        for (y in 0 until n) {
            var x = 0
            while (x < n) {
                if (!m.get(x, y)) {
                    x++
                    continue
                }
                var run = 1
                while (x + run < n && m.get(x + run, y)) run++
                path.append("M$x ${y}h${run}v1h-${run}z")
                x += run
            }
        }
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $n $n" shape-rendering="crispEdges"><rect width="$n" height="$n" fill="#ffffff"/><path fill="#000000" d="$path"/></svg>"""
    }
}
