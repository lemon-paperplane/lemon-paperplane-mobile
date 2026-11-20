package cn.lemonit.paperplane.features.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cn.lemonit.paperplane.BuildConfig
import cn.lemonit.paperplane.kit.PaperplaneJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayInputStream

// 编辑器网页（编辑器 index.html、终端页 terminal.html）在 Android WebView 里的宿主（对照 Mac EditorWindows / TerminalPage 的 WKWebView）。
//
// 网页经本地网关加载（http://127.0.0.1:端口/editor/…#token=…），和桌面一模一样；网页 → 宿主的消息按 Mac 的写法
// window.webkit.messageHandlers.paperplane.postMessage({type, …}) 发出。Android WebView 没有这个对象，所以：
// - addJavascriptInterface 注入 PaperplaneAndroid.postMessage(json 字符串)；
// - 网页 HTML 经 shouldInterceptRequest 从 APK 里直接读出，在 <head> 最前面插一段脚本，造出 webkit.messageHandlers.paperplane
//   （把对象转成 JSON 字符串交给上面的接口）。网页模块脚本执行时宿主已经在了（bridge.ts 在模块加载时判断 hasHost）。
// 宿主 → 网页：evaluateJavascript 调 window.paperplane.* / window.paperplaneTerm.*，网页发 ready 之前排队。

/** 网页桥接脚本与 HTML 注入（纯函数，单元测试覆盖） */
internal object EditorWebBridge {
    /** addJavascriptInterface 的名字 */
    const val INTERFACE = "PaperplaneAndroid"

    /**
     * 注入到网页的宿主脚本：造出 webkit.messageHandlers.paperplane，再执行页面专用的 [extra]（同一个闭包里，可用 post）。
     */
    fun hostScript(extra: String = ""): String = """
(function () {
  var host = window.$INTERFACE;
  if (!host) return;
  var post = function (m) { try { host.postMessage(JSON.stringify(m)); } catch (e) {} };
  window.webkit = window.webkit || {};
  window.webkit.messageHandlers = window.webkit.messageHandlers || {};
  window.webkit.messageHandlers.paperplane = { postMessage: post };
$extra
})();
""".trimIndent()

    /** 把脚本插到 <head> 之后（没有 <head> 时放最前面）；脚本里的 </script> 被转义 */
    fun inject(html: String, script: String): String {
        val tag = "<script>" + script.replace("</script", "<\\/script") + "</script>"
        val m = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html) ?: return tag + html
        return html.substring(0, m.range.last + 1) + tag + html.substring(m.range.last + 1)
    }

    /** 宿主调网页：`window.<obj> && <obj>.<fn>(args…)`，参数按 JSON 编码 */
    fun call(obj: String, fn: String, vararg args: Any?): String {
        val list = args.joinToString(",") { jsValue(it).toString() }
        return "window.$obj && window.$obj.$fn($list)"
    }

    fun jsValue(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { jsValue(it) })
        else -> JsonPrimitive(v.toString())
    }

    /** 网页发来的消息 */
    fun parse(json: String): JsonObject? = runCatching { PaperplaneJson.parseToJsonElement(json) as? JsonObject }.getOrNull()

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
}

/**
 * 一个编辑器网页的控制器：网页就绪前把要执行的脚本排队，就绪后补上。消息在主线程回调。
 */
internal class EditorWebController(private val onMessage: (type: String, msg: JsonObject) -> Unit) {
    var webView: WebView? = null
        internal set

    /** 网页发了 ready */
    var ready by mutableStateOf(false)
        private set

    /** 主框架加载失败（显示错误层，可重试） */
    var loadError by mutableStateOf<String?>(null)
        internal set

    /** 网页进程崩溃（需要换一个新的 WebView） */
    var crashed by mutableStateOf(false)
        internal set

    private val pending = mutableListOf<String>()
    private val main = Handler(Looper.getMainLooper())

    /** 网页就绪后执行（之前排队） */
    fun eval(js: String) {
        val wv = webView
        if (!ready || wv == null) {
            pending += js
            return
        }
        wv.evaluateJavascript(js, null)
    }

    /** 立刻执行并取结果（JSON 文本；网页没就绪时回 null） */
    fun evalForResult(js: String, done: (String?) -> Unit) {
        val wv = webView
        if (!ready || wv == null) {
            done(null)
            return
        }
        wv.evaluateJavascript(js) { done(it) }
    }

    internal fun reset() {
        ready = false
        loadError = null
        pending.clear()
    }

    /** 开始加载（含重新加载）：等新的 ready；排队的脚本留着 */
    internal fun pageStarted() {
        ready = false
        loadError = null
    }

    internal fun dispatch(json: String) {
        val msg = EditorWebBridge.parse(json) ?: return
        val type = (msg["type"] as? JsonPrimitive)?.contentOrNull ?: return
        main.post {
            if (type == "ready" && !ready) {
                ready = true
                loadError = null
                val queued = pending.toList()
                pending.clear()
                for (js in queued) webView?.evaluateJavascript(js, null)
            }
            onMessage(type, msg)
        }
    }

    /** JavascriptInterface 的对象（方法在 WebView 的后台线程上调用） */
    internal inner class Bridge {
        @JavascriptInterface
        fun postMessage(json: String) = dispatch(json)
    }
}

/**
 * 加载本地网关上的编辑器网页（[url] 形如 http://127.0.0.1:端口/editor/xxx.html#…）。
 *
 * @param hostScript 注入的宿主脚本（[EditorWebBridge.hostScript]）
 * @param onNavigate 网页要跳到网关以外的地址（链接、window.open）：交给调用方（系统浏览器或 App 内置浏览器）
 * @param newWebView 创建 WebView（终端页用子类改输入法参数）
 * @param configure 额外的 WebView 设置
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
internal fun EditorWebView(
    url: String,
    hostScript: String,
    controller: EditorWebController,
    background: Color,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit = {},
    newWebView: (Context) -> WebView = { WebView(it) },
    configure: (WebView) -> Unit = {},
) {
    val navigate by rememberUpdatedState(onNavigate)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val bg = background.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            createWebView(newWebView(ctx), url, hostScript, controller, bg, { navigate(it) }, configure)
        },
        update = { it.setBackgroundColor(bg) },
        onRelease = { wv ->
            // 换新 WebView（重建）时新的可能先建好：只清掉指向自己的引用
            if (controller.webView === wv) {
                controller.webView = null
                controller.reset()
            }
            wv.removeJavascriptInterface(EditorWebBridge.INTERFACE)
            wv.stopLoading()
            wv.webChromeClient = null
            wv.destroy()
        },
    )
    // 页面不可见时暂停 WebView 的动画与渲染（JS 照常跑：终端页自己按前后台断开 / 续上输出流）
    DisposableEffect(lifecycle, controller) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> controller.webView?.onPause()
                Lifecycle.Event.ON_RESUME -> controller.webView?.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
private fun createWebView(
    webView: WebView,
    url: String,
    hostScript: String,
    controller: EditorWebController,
    bg: Int,
    navigate: (String) -> Unit,
    configure: (WebView) -> Unit,
): WebView {
    if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    val origin = Uri.parse(url).let { "${it.scheme}://${it.authority}" }
    val assets = webView.context.applicationContext.assets
    return webView.apply {
        setBackgroundColor(bg)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportMultipleWindows(false)
        settings.mediaPlaybackRequiresUserGesture = true
        // 文字大小不随系统「字体大小」放大（网页按 px 布局，放大后控件挤在一起）
        settings.textZoom = 100
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = WebView.OVER_SCROLL_NEVER
        addJavascriptInterface(controller.Bridge(), EditorWebBridge.INTERFACE)
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                val path = u.path.orEmpty()
                if (request.method != "GET" || "${u.scheme}://${u.authority}" != origin) return null
                if (!path.startsWith("/editor/") || !path.endsWith(".html")) return null
                val rel = path.removePrefix("/editor/")
                if (rel.contains("..")) return null
                val html = runCatching { assets.open("editor/$rel").use { it.readBytes().decodeToString() } }.getOrNull() ?: return null
                val body = EditorWebBridge.inject(html, hostScript).toByteArray()
                return WebResourceResponse("text/html", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-cache"), ByteArrayInputStream(body))
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if ("${u.scheme}://${u.authority}" == origin) return false
                if (u.scheme == "about") return false
                if (request.isForMainFrame) navigate(u.toString())
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                controller.pageStarted()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                controller.loadError = error.description?.toString().orEmpty()
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (!request.isForMainFrame || response.statusCode < 400) return
                controller.loadError = "HTTP ${response.statusCode}"
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // 网页进程没了：这个 WebView 不能再用，交给页面换一个新的
                if (controller.webView === view) controller.webView = null
                controller.crashed = true
                return true
            }
        }
        configure(this)
        controller.pageStarted()
        controller.crashed = false
        controller.webView = this
        loadUrl(url)
    }
}

/** 记住一个控制器（随 key 重建） */
@Composable
internal fun rememberEditorWebController(vararg keys: Any?, onMessage: (type: String, msg: JsonObject) -> Unit): EditorWebController {
    val handler by rememberUpdatedState(onMessage)
    return remember(*keys) { EditorWebController { t, m -> handler(t, m) } }
}
