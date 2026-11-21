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

    private fun liveRows(vararg texts: String) = ConsoleParse.live(texts.map { LogLine(0, 0, "stdout", it) })

    @Test
    fun liveTailReplacesWithoutTouchingWindow() {
        val s = ConsoleState("r1", maxLines = 100)
        s.reset(lines(1L..100L), 100)
        val v0 = s.version
        s.replaceLive(liveRows("pushing 10%", "layer b 0%"))
        assertTrue(s.version > v0)
        // 实时行用界面编号，排在所有已定型行之后；不进窗口、不推进 seq
        assertEquals(listOf(ConsoleState.LIVE_SEQ, ConsoleState.LIVE_SEQ + 1), s.liveRows.map { it.seq })
        assertEquals(100, s.lines.size)
        assertEquals(100L, s.hi)
        assertEquals(100L, s.latestSeq)
        assertEquals(102, s.rowCount)
        assertEquals("layer b 0%", s.lastRow?.text)
        // 同样的内容不重复刷新
        val v1 = s.version
        s.replaceLive(liveRows("pushing 10%", "layer b 0%"))
        assertEquals(v1, s.version)
        // 行数上限只算已定型行
        assertFalse(s.appendLive(lines(101L..105L)))
        assertEquals(105, s.lines.size)
        assertEquals(105L, s.hi)
        assertEquals(2, s.liveRows.size)
        s.appendLive(lines(106L..115L))
        assertEquals(100, s.lines.size) // 超出 10% 才裁，丢开头
        assertEquals(2, s.liveRows.size)
        // 整体替换（变短）、清空
        s.replaceLive(liveRows("done"))
        assertEquals(listOf("done"), s.liveRows.map { it.text })
        s.replaceLive(emptyList())
        assertFalse(s.liveRows.isNotEmpty())
        assertEquals(s.lines.size, s.rowCount)
    }

    @Test
    fun liveTailHiddenWhileDetached() {
        val s = ConsoleState("r1")
        s.reset(lines(1L..10L), 10)
        s.replaceLive(liveRows("50%"))
        assertTrue(s.hasContent)
        assertEquals(11, s.rowCount)
        // 缺口：窗口脱离最新，实时尾部和窗口末尾之间隔着没加载的行，先不显示（但记着）
        assertTrue(s.appendLive(lines(20L..21L)))
        assertFalse(s.attached)
        assertTrue(s.liveRows.isEmpty())
        assertEquals(1, s.live.size)
        assertEquals(10, s.rowCount)
        assertEquals(-1, s.indexOf(ConsoleState.LIVE_SEQ))
        // 脱离时来的实时尾部也记着，补齐接上后显示
        s.replaceLive(liveRows("60%"))
        s.appendNewer(lines(11L..21L), 21)
        assertTrue(s.attached)
        assertEquals(listOf("60%"), s.liveRows.map { it.text })
        assertEquals(21, s.indexOf(ConsoleState.LIVE_SEQ))
    }

    @Test
    fun liveTailEndAndClear() {
        val s = ConsoleState("r1")
        s.reset(lines(1L..5L), 5)
        s.replaceLive(liveRows("a", "b"))
        s.markEnded()
        assertTrue(s.ended)
        assertTrue(s.live.isEmpty())
        val t = ConsoleState("r2")
        t.reset(lines(1L..5L), 5)
        t.replaceLive(liveRows("a".repeat(300)))
        assertEquals(300, t.contentWidth)
        t.clear()
        assertTrue(t.live.isEmpty())
        assertFalse(t.hasContent)
        assertEquals(0, t.contentWidth)
        // 只有实时尾部、还没有任何定型行时也算有内容
        t.replaceLive(liveRows("downloading"))
        assertTrue(t.hasContent)
        assertEquals(1, t.rowCount)
        assertEquals("downloading", t.selectedText())
    }

    @Test
    fun liveTailWidthHoldsUntilCleared() {
        val s = ConsoleState("r1")
        s.reset(ConsoleParse.lines(listOf(log(1, "short")), ANSIParser()), 1)
        assertEquals(5, s.contentWidth)
        s.replaceLive(liveRows("x".repeat(40)))
        assertEquals(40, s.contentWidth)
        // 进度条数字变短时横向宽度不跟着缩
        s.replaceLive(liveRows("x".repeat(38)))
        assertEquals(40, s.contentWidth)
        s.replaceLive(emptyList())
        assertEquals(5, s.contentWidth)
    }

    @Test
    fun liveTailStylesAreSelfContained() {
        // 已定型行的样式跨行延续（红色没闭合）
        val tail = ANSIParser()
        ConsoleParse.lines(listOf(log(1, "\u001B[31mred")), tail)
        // 实时行：每行自包含，没闭合的绿色不会带到下一行，也不改已定型行的解析器
        val live = liveRows("\u001B[32mgreen 50%", "plain", "\u001B[1mbold\u001B[0m tail")
        assertEquals(ANSIColor.Palette(2), live[0].spans!![0].style.fg)
        assertNull(live[1].spans)
        assertEquals("bold tail", live[2].text)
        assertEquals(ANSIColor.Palette(1), tail.style.fg)
        assertEquals(ANSIColor.Palette(1), ConsoleParse.line(log(2, "still red"), tail).spans!![0].style.fg)
        // 只变颜色、文字不变也算变化（界面要重画）
        val s = ConsoleState("r1")
        s.reset(emptyList(), 0)
        s.replaceLive(liveRows("\u001B[33mPushing"))
        val v = s.version
        s.replaceLive(liveRows("\u001B[32mPushing"))
        assertTrue(s.version > v)
    }

    @Test
    fun liveTailSearchSelectionAndCopy() {
        val p = ANSIParser()
        val s = ConsoleState("r1")
        s.reset(ConsoleParse.lines(listOf(log(1, "push start"), log(2, "ok")), p), 2)
        s.replaceLive(liveRows("layer a: Pushing 10%", "layer b: Waiting"))
        val rows = s.rowsSnapshot()
        assertEquals(4, rows.size)
        // 搜索覆盖实时尾部，结果在已定型行之后
        val m = ConsoleSearch.find(rows, "push")
        assertEquals(listOf(1L, ConsoleState.LIVE_SEQ), m.map { it.seq })
        s.setMatches(m, keepIndex = false)
        assertEquals(ConsoleState.LIVE_SEQ, s.currentMatch?.seq)
        assertEquals(2, s.indexOf(ConsoleState.LIVE_SEQ)) // 显示下标：两行定型行之后
        assertEquals(3, s.indexOf(ConsoleState.LIVE_SEQ + 1))
        assertEquals(-1, s.indexOf(ConsoleState.LIVE_SEQ + 2))
        // 没选时复制全部（含实时尾部）
        assertEquals("push start\nok\nlayer a: Pushing 10%\nlayer b: Waiting", s.selectedText())
        // 从定型行选到实时行
        s.startSelection(2)
        s.extendSelection(ConsoleState.LIVE_SEQ)
        assertEquals(2, s.selectedCount)
        assertEquals("ok\nlayer a: Pushing 10%", s.selectedText())
        s.selectAll()
        assertEquals(4, s.selectedCount)
        assertEquals(ConsoleState.LIVE_SEQ + 1, s.selEnd)
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
