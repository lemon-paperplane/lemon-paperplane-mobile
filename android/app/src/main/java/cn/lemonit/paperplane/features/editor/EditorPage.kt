package cn.lemonit.paperplane.features.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.EditorRoute
import cn.lemonit.paperplane.features.editor.EditorWebBridge.bool
import cn.lemonit.paperplane.features.editor.EditorWebBridge.str
import cn.lemonit.paperplane.kit.model.EditorURL
import cn.lemonit.paperplane.kit.model.ProjectSummary
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperBottomSheet
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperLoadingPage
import cn.lemonit.paperplane.ui.components.PaperMoreButton
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.I18n
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.shell.LocalPageBack
import cn.lemonit.paperplane.ui.shell.LocalWideLayout
import cn.lemonit.paperplane.ui.shell.PageScaffold
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// 编辑器页（对照 Mac Features/Editor/EditorWindows）：全屏 WebView 加载编辑器网页（CodeMirror 6，经本地网关）。
// 一个文件一页（EditorRoute 的键是路径），网页里仍可用快速打开切换别的文件（标签页在网页里）。
// 标题栏：文件名 + 工程名（有未保存时标出），撤销 / 重做 / 保存，「更多」里是查找、替换、跳转到行等网页命令。
// 返回时有未保存的修改先问（底部面板：不保存 / 取消 / 全部保存）。

/** 编辑器（对照 Mac Features/Editor/EditorWindows） */
@Composable
fun EditorPage(route: EditorRoute) {
    val data = AppModel.data
    val project = EditorFiles.project(route)
    val back = LocalPageBack.current ?: { AppModel.nav.pop(); Unit }
    if (project == null) {
        PageScaffold(title = EditorFiles.baseName(route.path)) {
            if (!data.loaded) PaperLoadingPage() else PaperEmptyState("file-question", tr("mobile.editor.notInProject"))
        }
        return
    }
    val rel = EditorFiles.relative(route.path, project)
    EditorContent(route, project, rel, back)
}

/** 编辑器网页地址与工程归属（纯逻辑） */
internal object EditorFiles {
    fun baseName(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }

    /** 文件所在的工程：给了 projectId 用它，否则找路径包含这个文件的工程（取最深的） */
    fun project(route: EditorRoute): ProjectSummary? {
        val data = AppModel.data
        route.projectId?.let { id -> data.projects[id]?.let { return it } }
        val sp = AppModel.serverPath
        return data.projects.values.filter { it.path.isNotEmpty() && sp.relative(route.path, it.path) != null }.maxByOrNull { it.path.length }
    }

    /** 相对工程根的路径（"/" 分隔）；已经是相对路径时原样返回 */
    fun relative(path: String, project: ProjectSummary): String {
        val sp = AppModel.serverPath
        if (!sp.isAbsolute(path)) return path.replace('\\', '/').trimStart('/')
        return sp.relative(path, project.path) ?: path
    }
}

private const val TITLE_SEP = " — "

/** 网页里的未保存文件（相对路径） */
private const val DIRTY_JS =
    "(window.paperplaneEditor && paperplaneEditor.app ? paperplaneEditor.app.tabs : []).filter(function (t) { return t.dirty; }).map(function (t) { return t.path; })"

private class EditorUiState {
    var title by mutableStateOf<String?>(null)
    var dirty by mutableStateOf(false)
    var revealed by mutableStateOf(false)
    /** 网页左侧文件树是否打开（手机上默认收起；用菜单切换时跟着变） */
    var sidebarOpen by mutableStateOf(false)
    /** 重建 WebView（崩溃后） */
    var generation by mutableIntStateOf(0)
}

@Composable
private fun EditorContent(route: EditorRoute, project: ProjectSummary, rel: String, back: () -> Unit) {
    val c = PaperTheme.colors
    val dark = c.isDark
    val wide = LocalWideLayout.current
    val state = remember(route.path) { EditorUiState().apply { sidebarOpen = wide } }

    val gatewayResult = remember(AppModel.client) { runCatching { AppModel.gateway() } }
    val gateway = gatewayResult.getOrNull()
    if (gateway == null) {
        PageScaffold(title = EditorFiles.baseName(route.path), subtitle = project.name) {
            PaperEmptyState("circle-alert", gatewayResult.exceptionOrNull()?.message ?: tr("editorWindow.loadFailed"))
        }
        return
    }
    val url = remember(gateway, state.generation) {
        EditorURL.make(gateway.baseUrl, gateway.token, project.id, rel, route.line, I18n.locale.value, if (dark) "dark" else "light")
    }
    val controller = rememberEditorWebController(route.path, state.generation) { type, msg ->
        when (type) {
            "ready" -> state.revealed = true
            "title" -> state.title = msg.str("title")
            "dirty" -> state.dirty = msg.bool("dirty") == true
            "closeWindow" -> back()
            "reveal" -> Toasts.warning(tr("remote.localOnly"))
            "openExternal" -> msg.str("url")?.let { openLink(it) }
        }
    }

    // 网页没发 ready（旧版本或出错）时，几秒后也显示出来
    LaunchedEffect(controller) {
        delay(4000)
        state.revealed = true
    }
    // 明暗、语言变化时通知网页（第一次由地址参数带过去）
    val locale = I18n.localeState.value
    remember(controller) { mutableStateOf(dark to locale) }.let { last ->
        LaunchedEffect(dark, locale) {
            val (d, l) = last.value
            if (d != dark) controller.eval(EditorWebBridge.call("paperplane", "setTheme", if (dark) "dark" else "light"))
            if (l != locale) controller.eval(EditorWebBridge.call("paperplane", "setLocale", locale))
            last.value = dark to locale
        }
    }
    // 同一文件再次打开（例如从搜索结果跳到另一行）：切到那一行
    remember(controller) { mutableStateOf(route.line) }.let { last ->
        LaunchedEffect(route.line) {
            if (last.value != route.line && route.line != null) controller.eval(EditorWebBridge.call("paperplane", "openFile", rel, route.line))
            last.value = route.line
        }
    }

    fun command(id: String) = controller.eval(EditorWebBridge.call("paperplaneEditor", "runCommand", id))

    fun tryClose() {
        if (!state.dirty || !controller.ready) {
            back()
            return
        }
        controller.evalForResult(DIRTY_JS) { json ->
            val paths = runCatching { EditorWebBridge.parse("{\"a\":$json}")?.get("a") as? JsonArray }.getOrNull()
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            askUnsaved(paths, state, controller, back)
        }
    }

    BackHandler(enabled = true) { tryClose() }

    // 网页标题是「工程名 — 当前文件名」
    val fileName = state.title?.let { t -> t.removePrefix(project.name + TITLE_SEP).takeIf { it != t && it.isNotEmpty() } }
        ?: EditorFiles.baseName(route.path)
    PageScaffold(
        title = fileName,
        subtitle = if (state.dirty) tr("mobile.editor.unsaved") + " · " + project.name else project.name,
        onBack = { tryClose() },
        actions = {
            PaperIconButton("undo-2", tr("mobile.editor.undo"), onClick = { command("undo") }, enabled = controller.ready)
            PaperIconButton("redo-2", tr("mobile.editor.redo"), onClick = { command("redo") }, enabled = controller.ready)
            PaperIconButton(
                "save",
                tr("common.save"),
                onClick = { controller.eval(EditorWebBridge.call("paperplane", "saveAll")) },
                enabled = controller.ready && state.dirty,
                tint = if (state.dirty) c.accent else null,
            )
            PaperMoreButton(entries = {
                editorMenu(::command, state.sidebarOpen) { state.sidebarOpen = !state.sidebarOpen }
            }, tint = c.iconPrimary)
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom)),
        ) {
            key(state.generation) {
                EditorWebView(
                    url = url,
                    hostScript = EditorWebBridge.hostScript(if (wide) "" else SIDEBAR_CLOSED_JS),
                    controller = controller,
                    background = c.bgContent,
                    // 不要给 WebView 加 alpha / graphicsLayer：在安卓上会让整页画不出来（WebView 的绘制和图层合成冲突）。
                    // 加载中、出错时用不透明的覆盖层盖住
                    modifier = Modifier.fillMaxSize(),
                    onNavigate = { openLink(it) },
                )
            }
            val err = controller.loadError
            when {
                controller.crashed -> PaperEmptyState(
                    "file-pen", tr("editorWindow.crashed"), Modifier.fillMaxSize().background(c.bgContent),
                    actionText = tr("editorWindow.reload"), actionIcon = "refresh-cw",
                ) {
                    state.revealed = false
                    state.dirty = false
                    state.generation++
                }
                err != null -> PaperEmptyState(
                    "file-pen", tr("editorWindow.loadFailed") + "\n" + err, Modifier.fillMaxSize().background(c.bgContent),
                    actionText = tr("editorWindow.reload"), actionIcon = "refresh-cw",
                ) {
                    controller.loadError = null
                    controller.webView?.reload()
                }
                !state.revealed -> PaperLoadingPage(Modifier.background(c.bgContent))
            }
        }
    }
}

/** 手机上默认收起网页左侧的文件树（文件在「文件」标签里浏览；网页里可以再打开） */
private const val SIDEBAR_CLOSED_JS =
    "  try { if (localStorage.getItem('pp-editor.sidebar.open') === null) localStorage.setItem('pp-editor.sidebar.open', 'false'); } catch (e) {}"

private fun editorMenu(command: (String) -> Unit, sidebarOpen: Boolean, sidebarToggled: () -> Unit): List<MenuEntry> = listOf(
    MenuEntry.Item(tr("editor.find.placeholder"), icon = "search") { command("find") },
    MenuEntry.Item(tr("editor.find.replace"), icon = "replace") { command("replace") },
    MenuEntry.Item(tr("editor.status.goToLine"), icon = "corner-down-left") { command("goToLine") },
    MenuEntry.Divider,
    MenuEntry.Item(tr("editor.empty.quickOpen"), icon = "file-search") { command("quickOpen") },
    MenuEntry.Item(tr("editor.findInFiles.title"), icon = "text-search") { command("findInFiles") },
    MenuEntry.Item(tr("editor.tree.show"), icon = "panel-left", checked = sidebarOpen) {
        command("toggleSidebar")
        sidebarToggled()
    },
    MenuEntry.Divider,
    MenuEntry.Item(tr("editor.settings.wordWrap"), icon = "wrap-text") { command("toggleWordWrap") },
    MenuEntry.Item(tr("mobile.editor.zoomIn"), icon = "plus") { command("zoomIn") },
    MenuEntry.Item(tr("mobile.editor.zoomOut"), icon = "minus") { command("zoomOut") },
)

/** 网页里的外部链接：http / https / mailto 交给系统浏览器 */
private fun openLink(url: String) {
    val scheme = url.substringBefore(':').lowercase()
    if (scheme in setOf("http", "https", "mailto")) Actions.openExternal(url)
}

/** 返回时有未保存的修改：不保存 / 取消 / 全部保存（保存完再返回） */
private fun askUnsaved(paths: List<String>, state: EditorUiState, controller: EditorWebController, back: () -> Unit) {
    Dialogs.present("editor-unsaved") { dismiss ->
        var saving by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val s = PaperTheme.spacing
        PaperBottomSheet(onDismiss = dismiss, title = tr("editor.unsaved.title"), showClose = false) { close ->
            val message = when (paths.size) {
                0 -> null
                1 -> tr("editor.unsaved.message", paths[0])
                else -> tr("editor.unsaved.messageMany", paths.size)
            }
            if (message != null) Text(message, style = PaperTheme.typography.body, color = PaperTheme.colors.textSecondary)
            if (failed) PaperAlert(Tone.Danger, tr("mobile.editor.saveFailed"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(s.md)) {
                PaperButton(tr("editor.unsaved.discard"), onClick = {
                    close()
                    back()
                }, kind = ButtonKind.Secondary, modifier = Modifier.weight(1f))
                PaperButton(tr("common.cancel"), onClick = close, kind = ButtonKind.Secondary, modifier = Modifier.weight(1f))
            }
            PaperButton(
                tr("editor.unsaved.saveAll"),
                kind = ButtonKind.Primary,
                loading = saving,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    saving = true
                    failed = false
                    controller.eval(EditorWebBridge.call("paperplane", "saveAll"))
                    scope.launch {
                        // 网页保存成功后会发 dirty:false；出错（外部修改冲突、只读等）时网页自己提示，这里等一会儿算失败
                        val ok = withTimeoutOrNull(15_000) { snapshotFlow { state.dirty }.first { !it } } != null
                        saving = false
                        if (ok) {
                            close()
                            back()
                        } else {
                            failed = true
                        }
                    }
                },
            )
        }
    }
}
