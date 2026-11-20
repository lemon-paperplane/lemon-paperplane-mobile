package cn.lemonit.paperplane.features.plugins

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.kit.ViewContext
import cn.lemonit.paperplane.kit.model.ViewInfo
import cn.lemonit.paperplane.kit.model.ViewNode
import cn.lemonit.paperplane.kit.renderView
import cn.lemonit.paperplane.kit.viewEvent
import cn.lemonit.paperplane.kit.webviewUrl
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.components.ToastKind
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 一个声明式插件视图的状态（对照 Mac PluginViewModel）：view.render 的节点树、插件 state、表格勾选、表单值、
 * 标签页 / 树节点 / 区块的展开状态。事件走 view.event，处理 refresh / state / message / openUrl / reimport。
 */
@Stable
class PluginViewModel(val context: ViewContext) {
    var root by mutableStateOf<ViewNode?>(null)
        internal set
    var error by mutableStateOf<String?>(null)
        private set
    var busyAction by mutableStateOf<String?>(null)
        private set

    /** 表格勾选：表格 id → 行 id */
    val selections = mutableStateMapOf<String, Set<String>>()
    /** 表单值：表单 id → 值 */
    val formValues = mutableStateMapOf<String, Map<String, JsonElement>>()
    /** 标签页节点选中的序号 */
    val tabIndex = mutableStateMapOf<String, Int>()
    /** 树节点展开、区块折叠 */
    val expanded = mutableStateMapOf<String, Boolean>()

    private var state: Map<String, JsonElement>? = context.initialState
    /** 插件要求的定时刷新间隔（毫秒，0 = 不刷新） */
    var refreshMs = 0
        private set

    /** 插件 id（节点里的相对图标路径按它解析） */
    val plugin: String get() = context.plugin

    /** 拉一次节点树；已有内容时失败不打扰（保留旧内容） */
    suspend fun render() {
        try {
            val r = AppModel.api().renderView(context, state)
            root = r.root
            r.state?.let { state = it }
            refreshMs = if (r.refreshMs > 0) maxOf(1000, r.refreshMs) else 0
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (root == null) error = e.message ?: tr("plugin.viewFailed")
        }
    }

    /** 触发事件；有 confirm 时先确认（标题用按钮文字，说明用 confirm） */
    fun fire(action: String?, values: Map<String, JsonElement> = emptyMap(), confirm: String? = null, title: String? = null) {
        if (action.isNullOrEmpty()) return
        if (!confirm.isNullOrEmpty()) {
            Dialogs.confirm(
                title = title ?: confirm,
                message = if (title == null) null else confirm,
                confirmText = title ?: tr("common.ok"),
            ) { send(action, values) }
            return
        }
        AppModel.scope.launch {
            try {
                send(action, values)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toasts.error(e)
            }
        }
    }

    private suspend fun send(action: String, values: Map<String, JsonElement>) {
        val v = LinkedHashMap(values)
        // 带上表格勾选
        val sel = selections.filterValues { it.isNotEmpty() }
        if (sel.isNotEmpty()) {
            v["selection"] = JsonObject(sel.mapValues { (_, ids) -> JsonArray(ids.sorted().map { JsonPrimitive(it) }) })
            if (v["selected"] == null) v["selected"] = JsonArray(sel.values.flatten().sorted().map { JsonPrimitive(it) })
        }
        busyAction = action
        try {
            val r = AppModel.api().viewEvent(context, action, v, state)
            r.state?.let { state = it }
            r.message?.takeIf { it.isNotEmpty() }?.let { Toasts.show(it, ToastKind.Info) }
            r.openUrl?.takeIf { it.isNotEmpty() }?.let { openLink(it) }
            if (r.reimport) context.projectId?.let { Actions.reimport(it) }
            if (r.refresh) render()
        } finally {
            busyAction = null
        }
    }
}

/** 插件给的链接：http(s) 用系统浏览器打开 */
internal fun openLink(url: String) {
    Actions.openExternal(url)
}

/**
 * 插件视图宿主（工程标签页、概览区块、独立页面、设置页通用；对照 Mac PluginViewHost）。
 * 声明式视图渲染节点树（可见时按 refreshMs 定时刷新，切到后台暂停）；webview 视图点「打开」后才加载网页
 * （[scrolls] 为 false 的嵌入位置打开成全屏页）。
 *
 * @param scrolls true 时自己滚动、铺满（标签页、独立页）；false 时按内容高度嵌在外层的滚动页里
 */
@Composable
fun PluginViewHost(view: ViewInfo, context: ViewContext, modifier: Modifier = Modifier, scrolls: Boolean = true) {
    if (view.isWebview) {
        PluginWebviewEntry(view, context, modifier, scrolls)
        return
    }
    val model = remember(context) { PluginViewModel(context) }
    val foreground = AppModel.foreground
    LaunchedEffect(model, foreground) {
        if (!foreground) return@LaunchedEffect
        model.render()
        while (model.refreshMs > 0) {
            delay(model.refreshMs.toLong())
            model.render()
        }
    }
    PluginViewBody(model, modifier, scrolls)
}

/** 节点树 / 出错 / 加载中（截图测试直接给 model） */
@Composable
fun PluginViewBody(model: PluginViewModel, modifier: Modifier = Modifier, scrolls: Boolean = true) {
    val sp = PaperTheme.spacing
    val root = model.root
    val scope = rememberCoroutineScope()
    when {
        root != null -> if (scrolls) {
            Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(sp.xl)) {
                NodeView(root, model, "r")
            }
        } else {
            Column(modifier.fillMaxWidth()) { NodeView(root, model, "r") }
        }
        model.error != null -> Box(modifier.fillMaxWidth().padding(if (scrolls) sp.xl else sp.xs)) {
            PaperAlert(Tone.Danger, tr("plugin.viewFailed"), message = model.error) {
                PaperButton(tr("connection.retry"), onClick = { scope.launch { model.render() } }, size = ButtonSize.Small)
            }
        }
        else -> Box(
            (if (scrolls) modifier.fillMaxSize() else modifier.fillMaxWidth()).padding(sp.xl),
            contentAlignment = if (scrolls) Alignment.Center else Alignment.CenterStart,
        ) { PaperSpinner(tint = PaperTheme.colors.iconSecondary) }
    }
}

/** webview 视图：只有用户点了「打开」才取地址、创建 WebView */
@Composable
private fun PluginWebviewEntry(view: ViewInfo, context: ViewContext, modifier: Modifier, scrolls: Boolean) {
    var url by remember(context) { mutableStateOf<String?>(null) }
    var loading by remember(context) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val u = url
    if (u != null && scrolls) {
        PluginWebView(u, context, modifier.fillMaxSize())
        return
    }
    PaperEmptyState(
        icon = "app-window",
        title = view.title,
        actionText = tr("plugin.openWebview"),
        actionIcon = "external-link",
        modifier = modifier,
        onAction = {
            if (loading) return@PaperEmptyState
            loading = true
            scope.launch {
                try {
                    val raw = AppModel.api().webviewUrl(context)
                    if (raw == null) {
                        Toasts.error(tr("plugin.viewFailed"))
                    } else {
                        val local = AppModel.gateway().url(raw)
                        if (scrolls) url = local else AppModel.nav.push(PluginWebRoute(view.title, local, context))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toasts.error(e, tr("plugin.viewFailed"))
                } finally {
                    loading = false
                }
            }
        },
    )
}
