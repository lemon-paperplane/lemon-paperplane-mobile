package cn.lemonit.paperplane.features.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cn.lemonit.paperplane.BuildConfig
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.BrowserRoute
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.kit.util.ServerURL
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperInfoButton
import cn.lemonit.paperplane.ui.components.PaperProgressBar
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.shell.PageScaffold
import cn.lemonit.paperplane.ui.theme.PaperTheme

// App 内置浏览器：打开运行地址。服务器 localhost 上的地址经端口转发（net.PortForwarder）到手机的回环地址，
// 只能在 App 里打开（别的 App 连不上这条隧道，切到后台隧道会断，回来时重新转发）；直连的地址也可以交给系统浏览器。
// 顶部地址栏（只读，可复制）+ 加载进度；底部后退、前进、刷新、用系统浏览器打开；加载失败显示原因和重试。

/** 浏览器页面的状态 */
private class BrowserState {
    var webView: WebView? = null
    var title by mutableStateOf<String?>(null)
    /** 地址栏里显示的地址（转发时显示服务器上的原地址，不显示手机本机端口） */
    var shownUrl by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var loading by mutableStateOf(false)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/** 经转发打开时：本机地址 → 服务器上的原地址（地址栏显示、复制都用原地址） */
internal object BrowserUrls {
    fun toRemote(url: String, localPort: Int, remotePort: Int): String {
        val p = ServerURL.parse(url) ?: return url
        if (!ServerURL.isLoopbackHost(p.host) || p.effectivePort != localPort) return url
        return p.copy(host = "localhost", port = remotePort).string
    }

    /** 能交给系统浏览器的：直连的 http(s) 地址（转发地址在别的 App 里打不开） */
    fun canOpenExternally(url: String, forwarded: Boolean): Boolean {
        if (forwarded) return false
        val scheme = url.substringBefore(':').lowercase()
        if (scheme != "http" && scheme != "https") return false
        return ServerURL.parse(url)?.host?.let { !ServerURL.isLoopbackHost(it) } ?: false
    }
}

/** App 内置浏览器 */
@Composable
fun BrowserPage(route: BrowserRoute) {
    val c = PaperTheme.colors
    val s = PaperTheme.spacing
    val state = remember(route.url) { BrowserState() }
    val port = route.forwardPort
    // 转发：本机端口（null = 还没开好；开不了时 forwardError）
    var localPort by remember(route.url) { mutableStateOf<Int?>(null) }
    var forwardError by remember(route.url) { mutableStateOf<String?>(null) }
    var loadUrl by remember(route.url) { mutableStateOf(if (port == null) route.url else null) }

    // 开转发；切到后台时 AppModel 会关掉全部转发，回到前台再开（本机端口可能变，变了就重新加载）
    if (port != null) {
        LaunchedEffect(route.url, AppModel.foreground) {
            if (!AppModel.foreground) return@LaunchedEffect
            try {
                val f = AppModel.forwarder().forward(port)
                forwardError = null
                if (f.localPort != localPort) {
                    val first = localPort == null
                    localPort = f.localPort
                    val target = if (first) ServerURL.forwarded(route.url, f.localPort) else state.webView?.url?.let { cur ->
                        ServerURL.parse(cur)?.takeIf { ServerURL.isLoopbackHost(it.host) }?.copy(port = f.localPort)?.string
                    } ?: ServerURL.forwarded(route.url, f.localPort)
                    loadUrl = target
                    state.webView?.loadUrl(target)
                }
            } catch (e: Exception) {
                forwardError = e.message ?: tr("remote.forward.failed")
            }
        }
        DisposableEffect(route.url) {
            onDispose { runCatching { AppModel.forwarder().unforward(port) } }
        }
    }

    BackHandler(enabled = state.canBack) { state.webView?.goBack() }

    val display = state.shownUrl.ifEmpty { route.url }
    val external = BrowserUrls.canOpenExternally(display, forwarded = port != null)
    PageScaffold(
        // 软键盘弹出时整页让出（底部操作栏跟着上移）
        modifier = Modifier.windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom)),
        title = state.title?.takeIf { it.isNotBlank() } ?: route.title ?: Format.shortURL(route.url),
        actions = {
            if (port != null) PaperInfoButton(tr("mobile.browser.forwardInfo"))
        },
        header = {
            AddressBar(display, forwarded = port != null)
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (state.loading) PaperProgressBar(state.progress.takeIf { it > 0f }, height = 2.dp)
            }
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = s.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PaperIconButton("chevron-left", tr("mobile.browser.back"), onClick = { state.webView?.goBack() }, enabled = state.canBack)
                PaperIconButton("chevron-right", tr("mobile.browser.forward"), onClick = { state.webView?.goForward() }, enabled = state.canForward)
                PaperIconButton("refresh-cw", tr("files.refresh"), onClick = {
                    state.error = null
                    state.webView?.reload()
                }, enabled = loadUrl != null)
                PaperIconButton("external-link", tr("mobile.browser.openExternal"), onClick = { Actions.openExternal(display) }, enabled = external)
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(c.bgContent)) {
            val url = loadUrl
            if (url != null) {
                BrowserWebView(url, state, remotePort = port, localPort = localPort)
            }
            val err = forwardError ?: state.error
            when {
                err != null -> PaperEmptyState(
                    "globe",
                    tr(if (forwardError != null) "remote.forward.failed" else "mobile.browser.loadFailed") + "\n" + err,
                    Modifier.fillMaxSize().background(c.bgContent),
                    actionText = tr("connection.retry"),
                    actionIcon = "refresh-cw",
                ) {
                    state.error = null
                    if (forwardError != null) {
                        forwardError = null
                        localPort = null
                        runCatching { AppModel.forwarder().forward(port!!) }
                            .onSuccess { f ->
                                localPort = f.localPort
                                loadUrl = ServerURL.forwarded(route.url, f.localPort)
                                state.webView?.loadUrl(loadUrl!!)
                            }
                            .onFailure { forwardError = it.message ?: tr("remote.forward.failed") }
                    } else {
                        state.webView?.reload()
                    }
                }
                url == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PaperProgressBar(null, Modifier.padding(horizontal = s.huge))
                }
            }
        }
    }
}

/** 地址栏：只读显示 + 复制（转发时左边是转发图标） */
@Composable
private fun AddressBar(url: String, forwarded: Boolean) {
    val c = PaperTheme.colors
    val s = PaperTheme.spacing
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = s.lg, end = s.xs, bottom = s.sm)
            .height(40.dp)
            .background(c.neutralSubtle, RoundedCornerShape(PaperTheme.radius.lg))
            .padding(start = s.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(s.sm),
    ) {
        PaperIcon(if (forwarded) "arrow-left-right" else if (url.startsWith("https:")) "lock" else "globe", size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
        Text(
            Format.shortURL(url),
            style = PaperTheme.typography.monoSmall,
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
        PaperIconButton("copy", tr("common.copy"), onClick = { Actions.copy(url) }, size = ButtonSize.Small, tint = c.iconSecondary)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserWebView(url: String, state: BrowserState, remotePort: Int?, localPort: Int?) {
    val c = PaperTheme.colors
    val bg = c.bgContent.toArgb()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun remote(u: String): String = if (remotePort != null && localPort != null) BrowserUrls.toRemote(u, localPort, remotePort) else u
    fun sync(view: WebView) {
        state.canBack = view.canGoBack()
        state.canForward = view.canGoForward()
        view.url?.let { state.shownUrl = remote(it) }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
            WebView(ctx).apply {
                // 撑满父容器：WRAP_CONTENT 时网页视口高度会被当成 0（100vh = 0）
                layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(bg)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportMultipleWindows(false)
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        state.progress = newProgress / 100f
                    }

                    override fun onReceivedTitle(view: WebView, title: String?) {
                        state.title = title
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        // http(s) 都在这里打开；其他协议（mailto:、tel:、intent: 等）交给系统
                        val scheme = request.url.scheme?.lowercase()
                        if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data" || scheme == "blob") return false
                        Actions.openExternal(request.url.toString())
                        return true
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        state.loading = true
                        state.error = null
                        sync(view)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        state.loading = false
                        sync(view)
                    }

                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                        sync(view)
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (!request.isForMainFrame) return
                        state.loading = false
                        state.error = describe(error)
                    }
                }
                state.webView = this
                loadUrl(url)
            }
        },
        update = { it.setBackgroundColor(bg) },
        onRelease = { wv ->
            if (state.webView === wv) state.webView = null
            wv.stopLoading()
            wv.webChromeClient = null
            wv.destroy()
        },
    )
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> state.webView?.onPause()
                Lifecycle.Event.ON_RESUME -> state.webView?.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
}

/** 加载失败的原因（常见的几种给出说明，其余原样显示） */
private fun describe(e: WebResourceError): String {
    val raw = e.description?.toString().orEmpty()
    return when {
        raw.contains("CLEARTEXT_NOT_PERMITTED") -> tr("mobile.browser.cleartext")
        e.errorCode == WebViewClient.ERROR_CONNECT || raw.contains("CONNECTION_REFUSED") ||
            raw.contains("CONNECTION_RESET") || raw.contains("EMPTY_RESPONSE") -> tr("mobile.browser.refused") + "\n" + raw
        else -> raw
    }
}
