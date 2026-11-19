package cn.lemonit.paperplane.kit

import cn.lemonit.paperplane.kit.ansi.ANSIColor
import cn.lemonit.paperplane.kit.ansi.ANSIPalette
import cn.lemonit.paperplane.kit.ansi.ANSIParser
import cn.lemonit.paperplane.kit.ansi.ANSISpan
import cn.lemonit.paperplane.kit.ansi.ANSIStyle
import cn.lemonit.paperplane.kit.graph.CommitGraphGeometry
import cn.lemonit.paperplane.kit.graph.GPoint
import cn.lemonit.paperplane.kit.model.GoTime
import cn.lemonit.paperplane.kit.model.VCSGraphEdge
import cn.lemonit.paperplane.kit.model.VCSGraphRow
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.kit.util.IconSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对照 Mac ParserTests.swift 的 ANSITests 与 MiscTests（SSE / SVG / 设计变量 / 文案不归 kit） */
class ANSITest {
    @Test
    fun plainText() {
        assertEquals(listOf(ANSISpan("hello", ANSIStyle.PLAIN)), ANSIParser().parse("hello"))
    }

    @Test
    fun basicColorsAndReset() {
        val p = ANSIParser()
        val spans = p.parse("\u001B[31mred\u001B[0m plain \u001B[1;92mbold\u001B[22m")
        assertEquals(3, spans.size)
        assertEquals(ANSIColor.Palette(1), spans[0].style.fg)
        assertEquals(ANSIStyle.PLAIN, spans[1].style)
        assertEquals(ANSIColor.Palette(10), spans[2].style.fg)
        assertTrue(spans[2].style.bold)
        assertFalse(p.style.bold) // 22 关掉粗体
        assertEquals(ANSIColor.Palette(10), p.style.fg) // 颜色跨行保留
        assertEquals(ANSIColor.Palette(10), p.parse("next").first().style.fg)
        p.reset()
        assertTrue(p.parse("x").first().style.isPlain)
    }

    @Test
    fun colors256AndTrueColor() {
        val s = ANSIParser().parse("\u001B[38;5;208ma\u001B[48;2;10;20;30mb\u001B[38:2::1:2:3mc\u001B[38:5:33md")
        assertEquals(ANSIColor.Palette(208), s[0].style.fg)
        assertEquals(ANSIColor.Rgb(10, 20, 30), s[1].style.bg)
        assertEquals(ANSIColor.Rgb(1, 2, 3), s[2].style.fg)
        assertEquals(ANSIColor.Palette(33), s[3].style.fg)
        // 超出范围的夹到 0–255
        assertEquals(ANSIColor.Rgb(255, 0, 7), ANSIParser().parse("\u001B[38;2;300;-1;7mx").first().style.fg)
    }

    @Test
    fun underlineItalicInverse() {
        val s = ANSIParser().parse("\u001B[3;4;7mx\u001B[23;24;27my")
        assertTrue(s[0].style.italic && s[0].style.underline && s[0].style.inverse)
        assertEquals(ANSIStyle.PLAIN, s[1].style)
        val t = ANSIParser().parse("\u001B[2;9ma\u001B[4mb\u001B[4:0mc")
        assertEquals(3, t.size)
        assertTrue(t[0].style.dim && t[0].style.strikethrough)
        assertTrue(t[1].style.underline)
        assertFalse(t[2].style.underline)
    }

    @Test
    fun stripsCursorControlAndOSC() {
        val s = ANSIParser().parse("\u001B[2K\u001B[1Gdone \u001B]8;;http://x\u0007link\u001B]8;;\u001B\\ end\u001B[?25h")
        assertEquals("done link end", s.joinToString("") { it.text })
    }

    @Test
    fun carriageReturnProgress() {
        val p = ANSIParser()
        assertEquals("100%", p.parse("10%\r50%\r100%").joinToString("") { it.text })
        assertEquals("line", p.parse("line\r").joinToString("") { it.text })
    }

    @Test
    fun backspaceAndSurrogates() {
        assertEquals("ab", ANSIParser.strip("abc\b"))
        assertEquals("中文", ANSIParser.strip("中文\u0001"))
        assertEquals("x", ANSIParser.strip("x\uD840\uDC00\b")) // 退格删掉整个代理对（U+20000）
    }

    @Test
    fun strip() {
        assertEquals("ok\t1", ANSIParser.strip("\u001B[32mok\u001B[0m\t1"))
    }

    @Test
    fun palette256() {
        assertNull(ANSIPalette.rgb(7))
        assertEquals(ANSIColor.Rgb(0, 0, 0), ANSIPalette.rgb(16))
        assertEquals(ANSIColor.Rgb(255, 255, 255), ANSIPalette.rgb(231))
        assertEquals(ANSIColor.Rgb(255, 0, 0), ANSIPalette.rgb(196))
        assertEquals(ANSIColor.Rgb(8, 8, 8), ANSIPalette.rgb(232))
        assertEquals(ANSIColor.Rgb(238, 238, 238), ANSIPalette.rgb(255))
    }
}

class MiscTest {
    @Test
    fun commitGraphGeometry() {
        val g = CommitGraphGeometry(laneWidth = 10f, rowHeight = 20f, leftPadding = 0f)
        val prev = VCSGraphRow(lane = 0, lanes = 2, edges = listOf(VCSGraphEdge(0, 0, 0), VCSGraphEdge(1, 0, 1)), color = 0)
        val cur = VCSGraphRow(lane = 0, lanes = 1, edges = listOf(VCSGraphEdge(0, 0, 0)), color = 0)
        val row = g.row(cur, prev)
        assertEquals(GPoint(5f, 10f), row.node)
        assertEquals(3, row.segments.size)
        // 上一行 1→0 的连线：在本行上半段从两列中点到本列中线
        assertEquals(GPoint(10f, 0f), row.segments[1].from)
        assertEquals(GPoint(5f, 10f), row.segments[1].to)
        assertEquals(GPoint(5f, 20f), row.segments[2].to)
        assertEquals(20f, row.width, 0f)
        assertEquals(20f, g.width(listOf(prev, cur)), 0f)
        assertEquals(1, CommitGraphGeometry.paletteIndex(9))
        assertEquals(7, CommitGraphGeometry.paletteIndex(-1))
    }

    @Test
    fun iconRef() {
        assertEquals(IconSource.Builtin("play"), IconSource.parse("builtin:play"))
        assertEquals(IconSource.Plugin("spring-boot", "icons/a.svg"), IconSource.parse("plugin:spring-boot/icons/a.svg"))
        assertEquals(IconSource.Plugin("gradle", "icons/x.svg"), IconSource.parse("icons/x.svg", pluginId = "gradle"))
        assertEquals(IconSource.Builtin("play"), IconSource.parse("play"))
        assertEquals(IconSource.Builtin("play"), IconSource.parse(" play.svg "))
        assertNull(IconSource.parse(""))
        assertNull(IconSource.parse(null))
        assertNull(IconSource.parse("plugin:noslash"))
        assertEquals("plugin:a/b.svg", IconSource.Plugin("a", "b.svg").key)
    }

    @Test
    fun format() {
        assertEquals("~/Code/x", Format.abbreviatePath("/Users/me/Code/x", home = "/Users/me"))
        assertEquals("/Users/meme/x", Format.abbreviatePath("/Users/meme/x", home = "/Users/me"))
        assertEquals("2.0 GB", Format.memory(2048.0))
        assertEquals("512 MB", Format.memory(512.4))
        assertEquals("24.1 MB", Format.memory(24.1))
        assertEquals("2.5%", Format.cpu(2.5))
        assertEquals("120%", Format.cpu(120.4))
        assertEquals("localhost:8080", Format.shortURL("http://localhost:8080/"))
        assertEquals("2m 5s", Format.duration(125.0))
        assertEquals("3s", Format.duration(3.9))
        assertEquals("1h 4m", Format.duration(3840.0))
        assertEquals("999 B", Format.bytes(999))
        assertEquals("12 KB", Format.bytes(12_345))
        assertEquals("1.2 MB", Format.bytes(1_234_567))
        assertEquals("1.0 MB", Format.bytes(999_999))
    }

    @Test
    fun goTime() {
        assertNotNull(GoTime.parse("2026-11-18T09:39:12Z"))
        assertNotNull(GoTime.parse("2026-11-18T09:39:12.5+08:00"))
        assertNotNull(GoTime.parse("2026-11-18T09:39:12.123456789-07:00"))
        assertTrue(GoTime.isZero(GoTime.parse("0001-01-01T00:00:00Z")))
        assertTrue(GoTime.isZero(null))
    }
}
