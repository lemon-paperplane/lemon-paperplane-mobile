package cn.lemonit.paperplane.features.plugins

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.Route
import cn.lemonit.paperplane.kit.PaperplaneJson
import cn.lemonit.paperplane.kit.ViewContext
import cn.lemonit.paperplane.kit.model.jsonString
import cn.lemonit.paperplane.ui.shell.PageScaffold
import cn.lemonit.paperplane.ui.theme.PaperTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 插件网页的全屏页（嵌在概览区块里的 webview 视图点「打开」后进这里） */
data class PluginWebRoute(val title: String, val url: String, val context: ViewContext) : Route {
    override val key get() = "pluginWeb:${context.plugin}/${context.view}:${context.projectId}:${context.moduleId}"

    @Composable
    override fun Content() {
        PageScaffold(title = title) { PluginWebView(url, context, Modifier.fillMaxSize()) }
    }
}

/**
 * 插件 webview 视图（逃生舱，对照 Mac WebviewHost）：WebView 加载本地网关地址（[AppModel.gateway] 改写过，带本地令牌），
 * 页面可以同源 fetch /api/v1/call/<op>、用 EventSource 订阅 /api/v1/events（网关流式转发不缓冲）。
 * 另外注入 window.paperplane.call(op, args)（返回 Promise），经 App 的接口客户端转发，自动补上工程 / 模块上下文。
 * 网关以外的链接用系统浏览器打开；网页里能后退时返回键先后退。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PluginWebView(url: String, context: ViewContext, modifier: Modifier = Modifier) {
    val bg = PaperTheme.colors.bgContent.toArgb()
    val base = remember { runCatching { AppModel.gateway().baseUrl }.getOrNull() }
    var canBack by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    BackHandler(enabled = canBack) { web?.goBack() }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                // 撑满父容器：WRAP_CONTENT 时网页视口高度会被当成 0（100vh = 0）
                layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(bg)
                addJavascriptInterface(Bridge(this, context, base), BRIDGE_NAME)
                val docStart = base != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                if (docStart) WebViewCompat.addDocumentStartJavaScript(this, BRIDGE_JS, setOf(base!!))
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        if (!docStart && base != null && url?.startsWith(base) == true) view.evaluateJavascript(BRIDGE_JS, null)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val u = request.url.toString()
                        if (base != null && u.startsWith(base)) return false
                        if (request.isForMainFrame) Actions.openExternal(u)
                        return true
                    }

                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                        canBack = view.canGoBack()
                    }
                }
                loadUrl(url)
                web = this
            }
        },
        onRelease = { v ->
            v.removeJavascriptInterface(BRIDGE_NAME)
            v.stopLoading()
            v.destroy()
            web = null
        },
    )
}

private const val BRIDGE_NAME = "PaperplaneNative"

/** 页面里的 window.paperplane.call：按序号记下 Promise，原生回调 __paperplaneReply 时兑现 */
private val BRIDGE_JS = """
(function () {
  if (window.paperplane && window.paperplane.__native) return;
  var seq = 0, pending = {};
  window.__paperplaneReply = function (id, ok, payload) {
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    if (ok) p.resolve(payload === undefined ? null : payload); else p.reject(new Error(payload));
  };
  window.paperplane = {
    __native: true,
    call: function (op, args) {
      return new Promise(function (resolve, reject) {
        var id = String(++seq);
        pending[id] = { resolve: resolve, reject: reject };
        $BRIDGE_NAME.call(id, String(op), JSON.stringify(args || {}));
      });
    }
  };
})();
""".trimIndent()

/** 原生一侧：只接受网关页面的调用（顶层地址必须是本地网关），结果原样（JSON）交回页面 */
private class Bridge(private val web: WebView, private val ctx: ViewContext, private val base: String?) {
    @JavascriptInterface
    fun call(id: String, op: String, args: String) {
        AppModel.scope.launch {
            val reply = try {
                check(base != null && web.url?.startsWith(base) == true) { "forbidden" }
                val input = (runCatching { PaperplaneJson.parseToJsonElement(args) }.getOrNull() as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                if (input["projectId"] == null) ctx.projectId?.let { input["projectId"] = JsonPrimitive(it) }
                if (input["moduleId"] == null) ctx.moduleId?.let { input["moduleId"] = JsonPrimitive(it) }
                val out = AppModel.api().callRaw(op, JsonObject(input))
                "window.__paperplaneReply(${js(id)}, true, ${out.jsonString})"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "window.__paperplaneReply(${js(id)}, false, ${js(e.message ?: "error")})"
            }
            runCatching { web.evaluateJavascript(reply, null) }
        }
    }

    /** 字符串 → JS 字面量 */
    private fun js(s: String) = JsonPrimitive(s).toString()
}
