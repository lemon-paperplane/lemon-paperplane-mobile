package cn.lemonit.paperplane.features.terminal

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.BrowserRoute
import cn.lemonit.paperplane.features.editor.EditorWebBridge
import cn.lemonit.paperplane.features.editor.EditorWebBridge.bool
import cn.lemonit.paperplane.features.editor.EditorWebBridge.str
import cn.lemonit.paperplane.features.editor.EditorWebController
import cn.lemonit.paperplane.features.editor.EditorWebView
import cn.lemonit.paperplane.kit.terminalInput
import cn.lemonit.paperplane.kit.util.ServerURL
import cn.lemonit.paperplane.net.LocalGateway
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.theme.PaperTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

// 终端区域：编辑器工程里的 terminal.html（xterm.js），经本地网关加载（对照 Mac TerminalPage 的 TerminalWebView）。
// 网页自己连输出流（GET /api/v1/terminals/<id>/stream，按偏移续传）、自己发键盘输入（terminal.input）、按区域大小 resize。
// 宿主这边补手机上缺的：
// - 辅助键栏（Esc、Tab、方向键……）的序列由这里算好直接发 terminal.input；
// - Ctrl / Alt 粘滞修饰键作用于软键盘输入：注入脚本包一层 fetch，网页发 terminal.input 时把第一个字符换掉；
// - 方向键要知道「应用光标键」模式：注入脚本包一层 EventSource，从输出里认 CSI ? 1 h / l，变了告诉宿主；
// - 切到后台时断开输出流、回前台续上（同样靠包过的 EventSource：挂起期间新建的连接等回前台再真正连）。

/** 注入终端页的脚本（和网页 terminal.ts 的约定：消息走 webkit.messageHandlers.paperplane，输入走 fetch terminal.input） */
internal val TERMINAL_HOST_JS = """
  var A = window.__ppAndroid = { ctrl: false, alt: false, ctrlLock: false, altLock: false, suspended: false, app: {} };
  // 应用光标键模式（CSI ? 1 h / l）
  var modeRe = /\x1b\[\?([0-9;]*)([hl])/g;
  function scan(id, s) {
    if (s.indexOf("\x1b[?") < 0) return;
    modeRe.lastIndex = 0;
    var m, v = null;
    while ((m = modeRe.exec(s))) { if (m[1].split(";").indexOf("1") >= 0) v = m[2] === "h"; }
    if (v !== null && A.app[id] !== v) { A.app[id] = v; post({ type: "cursorMode", id: id, app: v }); }
  }
  // 输出流：记下活动的连接（切后台时断开），挂起期间新建的连接等回前台再连
  var RealES = window.EventSource, live = [], waiting = [];
  function termId(url) { var m = /\/terminals\/([^/?]+)\/stream/.exec(String(url)); return m ? decodeURIComponent(m[1]) : ""; }
  function track(es, url) {
    var id = termId(url);
    live = live.filter(function (x) { return x.readyState !== 2; });
    live.push(es);
    if (id) es.addEventListener("message", function (ev) { try { scan(id, atob(JSON.parse(ev.data).d)); } catch (e) {} });
  }
  function Deferred(url, init) {
    var self = this, ls = [], real = null, closed = false;
    this.url = String(url); this.readyState = 0; this.withCredentials = false;
    this.onopen = null; this.onmessage = null; this.onerror = null;
    this.addEventListener = function (t, f, o) { if (real) real.addEventListener(t, f, o); else ls.push([t, f, o]); };
    this.removeEventListener = function (t, f, o) {
      if (real) real.removeEventListener(t, f, o); else ls = ls.filter(function (l) { return l[0] !== t || l[1] !== f; });
    };
    this.close = function () { closed = true; self.readyState = 2; if (real) real.close(); };
    this.start = function () {
      if (closed) return;
      real = new RealES(url, init);
      track(real, url);
      real.onopen = function (e) { self.readyState = 1; if (self.onopen) self.onopen(e); };
      real.onmessage = function (e) { if (self.onmessage) self.onmessage(e); };
      real.onerror = function (e) { self.readyState = real.readyState; if (self.onerror) self.onerror(e); };
      ls.forEach(function (l) { real.addEventListener(l[0], l[1], l[2]); });
    };
  }
  function ES(url, init) {
    if (A.suspended) { var d = new Deferred(url, init); waiting.push(d); return d; }
    var es = new RealES(url, init);
    track(es, url);
    return es;
  }
  ES.prototype = RealES.prototype; ES.CONNECTING = 0; ES.OPEN = 1; ES.CLOSED = 2;
  window.EventSource = ES;
  A.suspend = function () {
    A.suspended = true;
    var l = live; live = [];
    l.forEach(function (es) {
      if (es.readyState === 2) return;
      es.close();
      try { if (es.onerror) es.onerror(new Event("error")); } catch (e) {}
    });
  };
  A.resume = function () {
    A.suspended = false;
    var w = waiting; waiting = [];
    w.forEach(function (d) { d.start(); });
  };
  // Ctrl / Alt 作用于软键盘输入（第一个字符）
  function ctrlChar(c) {
    var k = c.charCodeAt(0);
    if (k >= 97 && k <= 122) return String.fromCharCode(k - 96);
    if (k >= 64 && k <= 95) return String.fromCharCode(k - 64);
    if (c === " " || c === "2") return "\x00";
    if (c === "3") return "\x1b";
    if (c === "4") return "\x1c";
    if (c === "5") return "\x1d";
    if (c === "6") return "\x1e";
    if (c === "7" || c === "-" || c === "/") return "\x1f";
    if (c === "8" || c === "?") return "\x7f";
    return c;
  }
  var realFetch = window.fetch;
  window.fetch = function (input, init) {
    try {
      var url = typeof input === "string" ? input : (input && input.url) || "";
      if ((A.ctrl || A.alt) && url.indexOf("/api/v1/call/terminal.input") >= 0 && init && typeof init.body === "string") {
        var b = JSON.parse(init.body);
        if (typeof b.data === "string" && b.data.length > 0) {
          var head = b.data.charAt(0), rest = b.data.slice(1);
          if (A.ctrl) head = ctrlChar(head);
          if (A.alt) head = "\x1b" + head;
          b.data = head + rest;
          init = Object.assign({}, init, { body: JSON.stringify(b) });
          if (!A.ctrlLock) A.ctrl = false;
          if (!A.altLock) A.alt = false;
          post({ type: "modsUsed" });
        }
      }
    } catch (e) {}
    return realFetch.call(window, input, init);
  };
  // 粘贴：交给 xterm 自己的粘贴处理（括号粘贴模式等）
  A.paste = function (text) {
    var ta = document.querySelector(".pp-term.on .xterm-helper-textarea");
    if (!ta) return false;
    try {
      var dt = new DataTransfer();
      dt.setData("text/plain", text);
      ta.dispatchEvent(new ClipboardEvent("paste", { clipboardData: dt, bubbles: true, cancelable: true }));
      return true;
    } catch (e) { return false; }
  };
""".trimEnd()

/** 终端页地址：/editor/terminal.html#token=…&theme=…&fontSize=…（网页从地址片段取令牌） */
internal fun terminalPageUrl(gateway: LocalGateway, dark: Boolean, fontSize: Int): String =
    gateway.baseUrl + "/editor/terminal.html#token=" + URLEncoder.encode(gateway.token, "UTF-8") +
        "&theme=" + (if (dark) "dark" else "light") + "&fontSize=" + fontSize

/**
 * 一个终端区域的状态：网页控制器、当前显示的终端、修饰键、各终端的光标键模式；按顺序发辅助键的输入。
 */
internal class TerminalSession {
    var shownId by mutableStateOf<String?>(null)
        private set
    var ctrl by mutableStateOf(ModState.OFF)
    var alt by mutableStateOf(ModState.OFF)
    var fontSize by mutableIntStateOf(TerminalActions.fontSize)
        private set
    /** 重建 WebView（崩溃后、改字号后） */
    var generation by mutableIntStateOf(0)
    private val appCursor = mutableStateMapOf<String, Boolean>()
    private val outbox = Channel<Pair<String, String>>(Channel.UNLIMITED)

    val controller = EditorWebController { type, msg -> onMessage(type, msg) }

    fun show(id: String) {
        shownId = id
        controller.eval(EditorWebBridge.call("paperplaneTerm", "show", id))
    }

    /** 宿主的终端列表变了：关掉网页里不在列表的 */
    fun sync(ids: List<String>) = controller.eval(EditorWebBridge.call("paperplaneTerm", "sync", ids))

    fun setTheme(dark: Boolean) = controller.eval(EditorWebBridge.call("paperplaneTerm", "setTheme", if (dark) "dark" else "light"))

    fun changeFontSize(v: Int) {
        val n = v.coerceIn(TerminalActions.FONT_SIZES)
        if (n == fontSize) return
        TerminalActions.fontSize = n
        fontSize = n
    }

    /** 辅助键栏的特殊键 */
    fun key(k: TermKey) {
        val id = shownId ?: return
        send(id, TermKeys.sequence(k, ctrl.on, alt.on, appCursor[id] == true))
        consumeMods()
    }

    /** 辅助键栏的字符键（| ~ / -） */
    fun text(s: String) {
        val id = shownId ?: return
        send(id, TermKeys.text(s, ctrl.on, alt.on))
        consumeMods()
    }

    /** 粘贴：交给网页里的 xterm（括号粘贴模式）；网页不支持时直接发 */
    fun paste(text: String) {
        val id = shownId ?: return
        if (text.isEmpty()) return
        controller.evalForResult("window.__ppAndroid ? __ppAndroid.paste(${EditorWebBridge.jsValue(text)}) : false") { r ->
            if (r != "true") send(id, text)
        }
    }

    fun toggleMod(which: String) {
        // 点一下：对下一个键生效；ONCE 时再点一下：锁定；锁定时再点：关掉
        when (which) {
            "ctrl" -> ctrl = next(ctrl)
            "alt" -> alt = next(alt)
        }
        syncMods()
    }

    private fun next(s: ModState) = when (s) {
        ModState.OFF -> ModState.ONCE
        ModState.ONCE -> ModState.LOCKED
        ModState.LOCKED -> ModState.OFF
    }

    private fun consumeMods() {
        var changed = false
        if (ctrl == ModState.ONCE) { ctrl = ModState.OFF; changed = true }
        if (alt == ModState.ONCE) { alt = ModState.OFF; changed = true }
        if (changed) syncMods()
    }

    private fun syncMods() {
        controller.eval(
            "window.__ppAndroid && (__ppAndroid.ctrl = ${ctrl.on}, __ppAndroid.alt = ${alt.on}, " +
                "__ppAndroid.ctrlLock = ${ctrl == ModState.LOCKED}, __ppAndroid.altLock = ${alt == ModState.LOCKED})",
        )
    }

    fun suspend() = controller.eval("window.__ppAndroid && __ppAndroid.suspend()")

    fun resume() = controller.eval("window.__ppAndroid && __ppAndroid.resume()")

    /** 弹出 / 收起软键盘 */
    fun showKeyboard(show: Boolean) {
        val wv = controller.webView ?: return
        val imm = wv.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (show) {
            controller.eval(EditorWebBridge.call("paperplaneTerm", "focus"))
            wv.requestFocus()
            wv.post { imm.showSoftInput(wv, InputMethodManager.SHOW_IMPLICIT) }
        } else {
            imm.hideSoftInputFromWindow(wv.windowToken, 0)
        }
    }

    private fun send(id: String, data: String) {
        if (data.isNotEmpty()) outbox.trySend(id to data)
    }

    /** 按顺序发出辅助键的输入（在页面的协程里跑） */
    suspend fun pump() {
        for ((id, data) in outbox) {
            runCatching { AppModel.api().terminalInput(id, data) }
        }
    }

    private fun onMessage(type: String, msg: JsonObject) {
        when (type) {
            "ready" -> {
                shownId?.let { controller.eval(EditorWebBridge.call("paperplaneTerm", "show", it)) }
                syncMods()
                if (!AppModel.foreground) suspend()
            }
            "closed" -> msg.str("id")?.let { TerminalActions.removed(it) }
            "cursorMode" -> msg.str("id")?.let { appCursor[it] = msg.bool("app") == true }
            "modsUsed" -> {
                if (ctrl == ModState.ONCE) ctrl = ModState.OFF
                if (alt == ModState.ONCE) alt = ModState.OFF
            }
            "openExternal" -> msg.str("url")?.let { openLink(it) }
        }
    }
}

/**
 * 终端里点到的链接：开发服务器打印的 localhost 地址经端口转发在 App 内置浏览器打开，其他 http(s) 地址也在内置浏览器打开。
 */
internal fun openLink(url: String) {
    val scheme = url.substringBefore(':').lowercase()
    if (scheme != "http" && scheme != "https") return
    val port = ServerURL.loopbackPort(url)
    if (port != null) AppModel.nav.push(BrowserRoute(url, forwardPort = port)) else AppModel.nav.push(BrowserRoute(url))
}

/** 终端的 WebView：软键盘不给联想（英文单词不进组字状态，Ctrl+字母、退格立即生效），中文输入法照常组字上屏 */
private class TerminalWebView(context: Context) : WebView(context) {
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs)
        outAttrs.inputType = outAttrs.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return ic
    }
}

/**
 * 终端网页区域。[terminalId] 为当前要显示的终端（null 时只加载网页，例如列表为空）；[allIds] 是这个区域里的全部终端（同步网页）。
 */
@Composable
internal fun TerminalSurface(session: TerminalSession, terminalId: String?, allIds: List<String>, modifier: Modifier = Modifier) {
    val c = PaperTheme.colors
    val dark = c.isDark
    val gatewayResult = remember(AppModel.client) { runCatching { AppModel.gateway() } }
    val gateway = gatewayResult.getOrNull()
    if (gateway == null) {
        PaperEmptyState("circle-alert", gatewayResult.exceptionOrNull()?.message ?: tr("editorWindow.loadFailed"), modifier.fillMaxSize())
        return
    }
    // 地址只在字号变化、重建时变（明暗由 setTheme 通知）
    val url = remember(gateway, session.fontSize, session.generation) { terminalPageUrl(gateway, dark, session.fontSize) }
    LaunchedEffect(session) { session.pump() }
    LaunchedEffect(terminalId) { terminalId?.let { session.show(it) } }
    LaunchedEffect(allIds) { session.sync(allIds) }
    LaunchedEffect(dark) { session.setTheme(dark) }
    LaunchedEffect(AppModel.foreground) { if (AppModel.foreground) session.resume() else session.suspend() }
    val ctl = session.controller
    Box(modifier.background(c.consoleBg)) {
        key(url) {
            EditorWebView(
                url = url,
                hostScript = EditorWebBridge.hostScript(TERMINAL_HOST_JS),
                controller = ctl,
                background = c.consoleBg,
                modifier = Modifier.fillMaxSize().alpha(if (ctl.loadError == null && !ctl.crashed) 1f else 0f),
                onNavigate = { openLink(it) },
                newWebView = { TerminalWebView(it) },
            )
        }
        val err = ctl.loadError
        if (ctl.crashed || err != null) {
            PaperEmptyState(
                "square-terminal",
                if (ctl.crashed) tr("editorWindow.crashed") else tr("editorWindow.loadFailed") + "\n" + err,
                Modifier.fillMaxSize().background(c.bgContent),
                actionText = tr("editorWindow.reload"),
                actionIcon = "refresh-cw",
            ) {
                ctl.crashed = false
                ctl.loadError = null
                session.generation++
            }
        }
    }
}
