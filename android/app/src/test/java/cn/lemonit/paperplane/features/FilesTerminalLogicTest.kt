package cn.lemonit.paperplane.features

import androidx.compose.ui.text.SpanStyle
import cn.lemonit.paperplane.features.browser.BrowserUrls
import cn.lemonit.paperplane.features.editor.EditorWebBridge
import cn.lemonit.paperplane.features.files.FileActions
import cn.lemonit.paperplane.features.files.FilesState
import cn.lemonit.paperplane.features.files.highlight
import cn.lemonit.paperplane.features.files.highlightPattern
import cn.lemonit.paperplane.features.terminal.HistoryBuffer
import cn.lemonit.paperplane.features.terminal.TERMINAL_HOST_JS
import cn.lemonit.paperplane.features.terminal.TermKey
import cn.lemonit.paperplane.features.terminal.TermKeys
import cn.lemonit.paperplane.kit.model.FSGrepMatch
import cn.lemonit.paperplane.kit.model.TerminalHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** 文件、编辑器、终端、浏览器页里的纯逻辑 */
class FilesTerminalLogicTest {
    // ---------------------------------------------------------------- 终端辅助键

    @Test
    fun keySequences() {
        assertEquals("\u001b", TermKeys.sequence(TermKey.ESC))
        assertEquals("\u001b\u001b", TermKeys.sequence(TermKey.ESC, alt = true))
        assertEquals("\t", TermKeys.sequence(TermKey.TAB))
        assertEquals("\u001b[A", TermKeys.sequence(TermKey.UP))
        assertEquals("\u001bOA", TermKeys.sequence(TermKey.UP, appCursor = true))
        assertEquals("\u001b[D", TermKeys.sequence(TermKey.LEFT))
        assertEquals("\u001b[H", TermKeys.sequence(TermKey.HOME))
        assertEquals("\u001bOF", TermKeys.sequence(TermKey.END, appCursor = true))
        // 带修饰键时一律 CSI 1;m x（应用光标模式也一样）
        assertEquals("\u001b[1;5C", TermKeys.sequence(TermKey.RIGHT, ctrl = true, appCursor = true))
        assertEquals("\u001b[1;3B", TermKeys.sequence(TermKey.DOWN, alt = true))
        assertEquals("\u001b[1;7A", TermKeys.sequence(TermKey.UP, ctrl = true, alt = true))
        assertEquals("\u001b[5~", TermKeys.sequence(TermKey.PAGE_UP))
        assertEquals("\u001b[6;5~", TermKeys.sequence(TermKey.PAGE_DOWN, ctrl = true))
    }

    @Test
    fun modifiersOnText() {
        assertEquals("\u0003", TermKeys.text("c", ctrl = true, alt = false))
        assertEquals("\u0003", TermKeys.text("C", ctrl = true, alt = false))
        assertEquals("\u0003lear", TermKeys.text("clear", ctrl = true, alt = false))
        assertEquals("\u001bb", TermKeys.text("b", ctrl = false, alt = true))
        assertEquals("\u001b\u0018", TermKeys.text("x", ctrl = true, alt = true))
        assertEquals("\u001b", TermKeys.text("[", ctrl = true, alt = false))
        assertEquals("\u001f", TermKeys.text("-", ctrl = true, alt = false))
        assertEquals("\u001f", TermKeys.text("/", ctrl = true, alt = false))
        assertEquals("\u0000", TermKeys.text(" ", ctrl = true, alt = false))
        assertEquals("\u007f", TermKeys.text("?", ctrl = true, alt = false))
        assertEquals("|", TermKeys.text("|", ctrl = false, alt = false))
        // 中文：Ctrl 不改，Alt 加 ESC
        assertEquals("中文", TermKeys.text("中文", ctrl = true, alt = false))
        assertEquals("\u001b中", TermKeys.text("中", ctrl = false, alt = true))
        assertEquals("", TermKeys.text("", ctrl = true, alt = true))
    }

    // ---------------------------------------------------------------- 完整输出分页拼接

    private fun h(start: Long, end: Long, data: String, first: Long = 0, total: Long = end) =
        TerminalHistory(start = start, end = end, first = first, total = total, data = data)

    @Test
    fun historyPrependJoinsSplitLine() {
        val b = HistoryBuffer()
        assertFalse(b.loaded)
        // 最近一段从一行中间开始
        b.setInitial(h(100, 200, "llo world\nsecond\n$ ", total = 200))
        assertTrue(b.hasOlder)
        assertEquals(listOf("llo world", "second", "$ "), b.lines.map { it.text })
        val idsBefore = b.lines.map { it.id }
        b.prepend(h(0, 100, "first line\nhe", total = 200))
        assertEquals(listOf("first line", "hello world", "second", "$ "), b.lines.map { it.text })
        assertFalse(b.hasOlder)
        // 原有的行 id 不变，往前插的行 id 更小（列表据此保持滚动位置）
        assertEquals(idsBefore, b.lines.drop(1).map { it.id })
        assertTrue(b.lines[0].id < b.lines[1].id)
        assertEquals(b.lines.size, b.lines.map { it.id }.toSet().size)
    }

    @Test
    fun historyPrependDropsBrokenCharAtBoundary() {
        val b = HistoryBuffer()
        // 新的一段从多字节字符中间开始（开头是替换字符），老的一段的末尾被内核截在字符前
        b.setInitial(h(103, 200, "��尾巴\n", first = 0, total = 200))
        assertEquals("尾巴", b.lines[0].text)
        b.prepend(h(0, 101, "头"))
        assertEquals("头尾巴", b.lines[0].text)
        assertEquals(0L, b.start)
    }

    @Test
    fun historyAppendContinuesLastLine() {
        val b = HistoryBuffer()
        b.setInitial(h(0, 10, "a\nb", total = 20))
        assertFalse(b.atEnd)
        b.append(h(10, 20, "c\nd", total = 20))
        assertEquals(listOf("a", "bc", "d"), b.lines.map { it.text })
        assertTrue(b.atEnd)
        val (all, cut) = b.text()
        assertEquals("a\nbc\nd", all)
        assertFalse(cut)
        val (tail, cut2) = b.text(4)
        assertEquals("bc\nd", tail)
        assertTrue(cut2)
    }

    // ---------------------------------------------------------------- 网页桥接

    @Test
    fun injectAfterHead() {
        val html = "<!doctype html>\n<html><head>\n<meta charset=\"utf-8\"></head><body></body></html>"
        val out = EditorWebBridge.inject(html, "var x = '</script>';")
        assertTrue(out, out.startsWith("<!doctype html>\n<html><head><script>var x = '<\\/script>';</script>\n<meta"))
        assertEquals("<script>a</script>plain", EditorWebBridge.inject("plain", "a"))
        val upper = EditorWebBridge.inject("<HEAD lang=en>x", "a")
        assertEquals("<HEAD lang=en><script>a</script>x", upper)
    }

    @Test
    fun hostCalls() {
        assertEquals("window.paperplane && window.paperplane.openFile(\"src/a b.kt\",12)", EditorWebBridge.call("paperplane", "openFile", "src/a b.kt", 12))
        assertEquals("window.paperplaneTerm && window.paperplaneTerm.sync([\"a\",\"b\"])", EditorWebBridge.call("paperplaneTerm", "sync", listOf("a", "b")))
        assertEquals("window.paperplane && window.paperplane.saveAll()", EditorWebBridge.call("paperplane", "saveAll"))
        // 网页消息
        val m = EditorWebBridge.parse("{\"type\":\"dirty\",\"dirty\":true}")!!
        with(EditorWebBridge) {
            assertEquals("dirty", m.str("type"))
            assertEquals(true, m.bool("dirty"))
        }
        assertNull(EditorWebBridge.parse("not json"))
        // 宿主脚本造出 webkit.messageHandlers.paperplane，并把终端页的脚本放进同一个闭包
        val js = EditorWebBridge.hostScript(TERMINAL_HOST_JS)
        assertTrue(js.contains("window.webkit.messageHandlers.paperplane = { postMessage: post }"))
        assertTrue(js.contains("window.__ppAndroid"))
        // 写出来给 node 做语法与行为检查（scratchpad 里的脚本）
        File("build/outputs/paperplane-js").apply { mkdirs() }.resolve("terminal-host.js").writeText(js)
        File("build/outputs/paperplane-js").resolve("editor-host.js").writeText(EditorWebBridge.hostScript())
    }

    // ---------------------------------------------------------------- 文件

    @Test
    fun pathsAndCrumbs() {
        assertEquals("", FileActions.parent("a"))
        assertEquals("a/b", FileActions.parent("a/b/c.txt"))
        assertEquals("a", FileActions.join("", "a"))
        assertEquals("a/b", FileActions.join("a", "b"))
        assertEquals(emptyList<Pair<String, String>>(), FilesState.crumbs(""))
        assertEquals(listOf("src" to "src", "main" to "src/main", "kotlin" to "src/main/kotlin"), FilesState.crumbs("src/main/kotlin"))
    }

    @Test
    fun grepGrouping() {
        val ms = listOf(FSGrepMatch("a.kt", 1, 1, "x"), FSGrepMatch("b.kt", 3, 1, "y"), FSGrepMatch("a.kt", 9, 2, "z"))
        val g = FilesState.group(ms)
        assertEquals(listOf("a.kt", "b.kt"), g.map { it.first })
        assertEquals(listOf(1, 9), g[0].second.map { it.line })
    }

    @Test
    fun mtimeFormatting() {
        val zone = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 11, 20)
        assertEquals("08:12", FileActions.mtime("2026-11-20T08:12:05.123456789+08:00", zone, today))
        assertEquals("2026-11-19 23:00", FileActions.mtime("2026-11-19T15:00:00Z", zone, today))
        assertNull(FileActions.mtime("", zone, today))
        assertNull(FileActions.mtime("garbage", zone, today))
    }

    @Test
    fun grepHighlight() {
        val style = SpanStyle()
        val plain = highlight("Foo foo FOO", highlightPattern("foo", regex = false, caseSensitive = false), style)
        assertEquals(3, plain.spanStyles.size)
        val cs = highlight("Foo foo FOO", highlightPattern("foo", regex = false, caseSensitive = true), style)
        assertEquals(1, cs.spanStyles.size)
        assertEquals(4, cs.spanStyles[0].start)
        // 正则里的特殊字符按字面找
        assertEquals(1, highlight("a.b axb", highlightPattern("a.b", regex = false, caseSensitive = false), style).spanStyles.size)
        assertEquals(2, highlight("a.b axb", highlightPattern("a.b", regex = true, caseSensitive = false), style).spanStyles.size)
        // 写错的正则：不高亮
        assertNull(highlightPattern("(", regex = true, caseSensitive = false))
    }

    // ---------------------------------------------------------------- 浏览器

    @Test
    fun browserUrls() {
        assertEquals("http://localhost:5173/app?x=1", BrowserUrls.toRemote("http://localhost:41234/app?x=1", 41234, 5173))
        assertEquals("http://localhost:5173/", BrowserUrls.toRemote("http://127.0.0.1:41234/", 41234, 5173))
        // 页面跳到别的地址：原样
        assertEquals("https://example.com/", BrowserUrls.toRemote("https://example.com/", 41234, 5173))
        assertFalse(BrowserUrls.canOpenExternally("http://localhost:5173/", forwarded = true))
        assertFalse(BrowserUrls.canOpenExternally("http://localhost:5173/", forwarded = false))
        assertTrue(BrowserUrls.canOpenExternally("http://192.168.1.5:5173/", forwarded = false))
        assertFalse(BrowserUrls.canOpenExternally("ftp://192.168.1.5/", forwarded = false))
    }
}
