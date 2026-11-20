package cn.lemonit.paperplane.features.runs

import cn.lemonit.paperplane.kit.ansi.ANSIColor
import cn.lemonit.paperplane.kit.ansi.ANSIParser
import cn.lemonit.paperplane.kit.model.LogLine
import cn.lemonit.paperplane.ui.i18n.I18n
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 控制台窗口逻辑：实时追加、缺口、往前 / 往后翻页、上限裁剪、清空、搜索、选择 */
class ConsoleModelTest {
    private fun log(seq: Long, text: String = "line $seq", s: String = "stdout") = LogLine(seq, 0, s, text)

    private fun lines(range: LongRange, parser: ANSIParser = ANSIParser()) = ConsoleParse.lines(range.map { log(it) }, parser)

    @Test
    fun parsePlainAndColored() {
        val p = ANSIParser()
        val plain = ConsoleParse.line(log(1, "hello\tworld"), p)
        assertNull(plain.spans)
        assertEquals("hello\tworld", plain.text)
        val colored = ConsoleParse.line(log(2, "\u001B[32mok\u001B[0m done"), p)
        assertNotNull(colored.spans)
        assertEquals("ok done", colored.text)
        assertEquals(ANSIColor.Palette(2), colored.spans!![0].style.fg)
        // 没闭合的样式延续到下一行
        ConsoleParse.line(log(3, "\u001B[31mred"), p)
        val next = ConsoleParse.line(log(4, "still red"), p)
        assertEquals(ANSIColor.Palette(1), next.spans!![0].style.fg)
        // 宽字符算 2
        assertEquals(4 + 2, ConsoleParse.displayWidth("中文ab"))
    }

    @Test
    fun liveAppendAndGap() {
        val s = ConsoleState("r1")
        s.reset(lines(1L..10L), 10)
        assertTrue(s.attached)
        assertEquals(10L, s.hi)
        assertFalse(s.appendLive(lines(11L..12L)))
        assertEquals(12, s.lines.size)
        // 重复的行忽略
        assertFalse(s.appendLive(lines(12L..12L)))
        assertEquals(12, s.lines.size)
        // 缺了 13..19：脱离最新，等按页补齐
        assertTrue(s.appendLive(lines(20L..21L)))
        assertFalse(s.attached)
        assertEquals(21L, s.latestSeq)
        assertEquals(12L, s.hi)
        // 脱离时实时行只记最新
        s.noteLatest(25)
        s.appendNewer(lines(13L..20L), 25)
        assertFalse(s.attached)
        s.appendNewer(lines(21L..25L), 25)
        assertTrue(s.attached)
        assertEquals((1L..25L).toList(), s.lines.map { it.seq })
    }

    @Test
    fun prependOlderUntilFirst() {
        val s = ConsoleState("r1")
        s.reset(lines(2001L..2500L), 2500)
        assertTrue(s.hasOlder)
        s.prependOlder(lines(1001L..2000L), 1)
        assertEquals(1001L, s.lo)
        s.prependOlder(lines(1L..1000L), 1)
        assertEquals(1L, s.lo)
        assertFalse(s.hasOlder)
        assertEquals(2500, s.lines.size)
        assertEquals((1L..2500L).toList(), s.lines.map { it.seq })
    }

    @Test
    fun memoryOnlyConsoleStopsAtBufferStart() {
        // 没有日志文件：服务器从内存缓冲最早一行（500）开始给
        val s = ConsoleState("t1")
        s.reset(lines(800L..900L), 900)
        s.prependOlder(lines(500L..799L), 500)
        assertEquals(500L, s.lo)
        assertFalse(s.hasOlder)
        // 再要更早的：服务器还是从 500 给，没有更早的了
        s.prependOlder(lines(500L..600L), 500)
        assertEquals(500L, s.lo)
        assertFalse(s.hasOlder)
    }

    @Test
    fun trimHeadWhileFollowingAndTailWhileReadingOld() {
        val s = ConsoleState("r1", maxLines = 100)
        s.reset(lines(1L..100L), 100)
        s.appendLive(lines(101L..120L))
        // 超出 10% 才裁：丢开头
        assertEquals(100, s.lines.size)
        assertEquals(21L, s.lo)
        assertTrue(s.hasOlder)
        assertTrue(s.attached)
        // 往上翻读老日志时超出：丢末尾，脱离最新
        val t = ConsoleState("r2", maxLines = 100)
        t.reset(lines(201L..300L), 300)
        t.follow = false
        t.prependOlder(lines(101L..200L), 1)
        assertEquals(100, t.lines.size)
        assertEquals(101L, t.lo)
        assertFalse(t.attached)
        assertTrue(t.hasNewer)
        assertEquals(200L, t.hi)
        // 往后补回来，补到最新再接上
        t.appendNewer(lines(201L..300L), 300)
        assertTrue(t.attached)
        assertEquals(300L, t.hi)
    }

    @Test
    fun jumpToStartThenCatchUp() {
        val s = ConsoleState("r1")
        s.reset(lines(9501L..10000L), 10000)
        s.jumpToStart(lines(1L..1000L), 1, 10000)
        assertFalse(s.attached)
        assertFalse(s.follow)
        assertFalse(s.hasOlder)
        assertTrue(s.hasNewer)
        assertEquals(1000L, s.hi)
        s.appendNewer(lines(1001L..2000L), 10000)
        assertEquals(2000, s.lines.size)
        assertFalse(s.attached)
    }

    @Test
    fun clearHidesOlder() {
        val s = ConsoleState("r1")
        s.reset(lines(1L..50L), 50)
        s.clear()
        assertEquals(0, s.lines.size)
        assertFalse(s.hasOlder)
        assertTrue(s.attached)
        s.appendLive(lines(51L..52L))
        assertEquals(listOf(51L, 52L), s.lines.map { it.seq })
        s.prependOlder(lines(1L..50L), 1)
        assertEquals(51L, s.lo)
    }

    @Test
    fun searchAndSelection() {
        val p = ANSIParser()
        val s = ConsoleState("r1")
        s.reset(ConsoleParse.lines(listOf(log(1, "Error: a"), log(2, "ok"), log(3, "error error")), p), 3)
        val m = ConsoleSearch.find(s.lines.toList(), "error")
        assertEquals(3, m.size)
        s.setMatches(m, keepIndex = false)
        assertEquals(2, s.matchIndex)
        assertEquals(1L, s.step(1))
        assertEquals(3L, s.step(-1))
        s.startSelection(3)
        s.extendSelection(1)
        assertEquals(3, s.selectedCount)
        assertTrue(s.isSelected(2))
        assertEquals("Error: a\nok\nerror error", s.selectedText())
        s.cancelSelection()
        assertFalse(s.selecting)
        assertEquals(1, s.indexOf(2))
        assertEquals(-1, s.indexOf(9))
    }

    @Test
    fun relativeTime() {
        I18n.install("en", mapOf("vcs.date.agoMinutes" to "{0} min. ago", "vcs.date.agoDays" to "{0} days ago"))
        val now = java.time.Instant.parse("2026-11-20T12:00:00Z")
        assertEquals("5 min. ago", RelativeTime.short(now.minusSeconds(300), now))
        assertEquals("3 days ago", RelativeTime.short(now.minusSeconds(3 * 86400 + 10), now))
        assertTrue(RelativeTime.short(now.minusSeconds(30L * 86400), now).startsWith("2026-10-"))
    }
}
