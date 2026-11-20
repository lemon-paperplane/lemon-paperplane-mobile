package cn.lemonit.paperplane.features.runs

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.lemonit.paperplane.kit.ansi.ANSIParser
import cn.lemonit.paperplane.kit.ansi.ANSISpan
import cn.lemonit.paperplane.kit.model.LogLine

// 控制台的数据部分（和界面、网络无关，JVM 单元测试可测）。
//
// 控制台是服务器上完整日志的一个「窗口」：[lo, hi] 一段连续的行（seq 连续）。
//   - 实时：窗口末尾就是最新一行时（attached），日志流来的行直接追加；
//   - 往上翻：窗口顶部还没到服务器能读到的最早一行（firstAvailable）时，按页往前补（run.logs from/limit）；
//   - 内存上限 MAX_LINES：超出时丢掉离用户远的一头。丢掉的是末尾时窗口脱离最新（attached = false），
//     实时行先不追加，等用户滚回底部再按页往后补，补到最新再接上实时；
//   - 流断开重连后（切回前台）服务器只补发内存缓冲里的行，中间可能缺一段：发现缺口时同样先脱离、按页补齐。
// 解析（ANSI 拆段）在入窗口前做一次，结果缓存在 ConsoleLine 里，界面只按需把段落转成带颜色的文字。

/** 控制台里的一行（ANSI 已解析） */
@Immutable
class ConsoleLine(
    val seq: Long,
    /** stdout | stderr | system */
    val stream: String,
    /** 去掉转义后的文本（搜索、复制用） */
    val text: String,
    /** 着色段落；整行都是默认样式时为 null（大多数行，省内存） */
    val spans: List<ANSISpan>?,
    /** 显示宽度（字符数，宽字符算 2）：不换行时算横向滚动的宽度 */
    val width: Int,
) {
    override fun equals(other: Any?): Boolean = other is ConsoleLine && other.seq == seq && other.text == text && other.stream == stream
    override fun hashCode(): Int = seq.hashCode()
}

/** 一处搜索结果：行号 + 行内区间 [start, end) */
@Immutable
data class ConsoleMatch(val seq: Long, val start: Int, val end: Int)

object ConsoleParse {
    /** 解析一批行（parser 的样式跨行保留，和终端一致） */
    fun lines(list: List<LogLine>, parser: ANSIParser): List<ConsoleLine> = list.map { line(it, parser) }

    fun line(l: LogLine, parser: ANSIParser): ConsoleLine {
        val spans = if (l.text.indexOf('\u001B') < 0 && l.text.indexOf('\r') < 0 && l.text.indexOf('\b') < 0 && parser.style.isPlain) {
            null
        } else {
            parser.parse(l.text)
        }
        val text = spans?.joinToString("") { it.text } ?: l.text.filterControl()
        val plainSpans = spans?.takeUnless { s -> s.all { it.style.isPlain } }
        return ConsoleLine(l.seq, l.s, text, plainSpans, displayWidth(text))
    }

    /** 没有转义的行也去掉其他控制字符（保留 tab） */
    private fun String.filterControl(): String {
        if (none { (it < ' ' && it != '\t') || it == '\u007F' }) return this
        return filter { !((it < ' ' && it != '\t') || it == '\u007F') }
    }

    /** 等宽显示宽度：中日韩等宽字符算 2，tab 算 4 */
    fun displayWidth(s: String): Int {
        var w = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            w += when {
                cp == '\t'.code -> 4
                isWide(cp) -> 2
                else -> 1
            }
            i += Character.charCount(cp)
        }
        return w
    }

    private fun isWide(cp: Int): Boolean =
        cp in 0x1100..0x115F || cp in 0x2E80..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF ||
            cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 || cp in 0x1F300..0x1FAFF || cp in 0x20000..0x3FFFD
}

object ConsoleSearch {
    /** 不区分大小写查找，最多 limit 处 */
    fun find(lines: List<ConsoleLine>, query: String, limit: Int = 5000): List<ConsoleMatch> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<ConsoleMatch>()
        for (l in lines) {
            var from = 0
            while (true) {
                val i = l.text.indexOf(query, from, ignoreCase = true)
                if (i < 0) break
                out += ConsoleMatch(l.seq, i, i + query.length)
                if (out.size >= limit) return out
                from = i + maxOf(query.length, 1)
            }
        }
        return out
    }
}

/**
 * 一个控制台的状态（窗口 + 界面状态）。只在主线程改；网络部分见 ConsoleLoader。
 */
@Stable
class ConsoleState(val consoleId: String?, val maxLines: Int = MAX_LINES) {
    val lines = mutableStateListOf<ConsoleLine>()

    /** 每次内容变化 +1（搜索等据此重算） */
    var version by mutableIntStateOf(0)
        private set
    /** 首次加载中 */
    var loading by mutableStateOf(consoleId != null)
    var error by mutableStateOf<String?>(null)
    /** 控制台已结束（运行停止、任务完成），不会再有新行 */
    var ended by mutableStateOf(false)
    var loadingOlder by mutableStateOf(false)
    var loadingNewer by mutableStateOf(false)
    /** 跟随最新（用户往上翻时关掉，滚回底部再打开） */
    var follow by mutableStateOf(true)
    /** 窗口末尾就是最新一行（实时行直接追加） */
    var attached by mutableStateOf(true)
        private set
    /** 窗口最后一行的 seq（空窗口时是起点前一行） */
    var hi by mutableLongStateOf(0L)
        private set
    /** 服务器上最新一行（流和分页结果里看到的最大值） */
    var latestSeq by mutableLongStateOf(0L)
        private set
    /** 服务器能读到的最早一行（还不知道时按 1） */
    var firstAvailable by mutableLongStateOf(1L)
        private set
    /** 清空后不再往前读：只看这一行之后的 */
    var floor by mutableLongStateOf(0L)
        private set
    /** 已加载行里最长的显示宽度（不换行时横向滚动用） */
    var maxWidth by mutableIntStateOf(0)
        private set
    /** 已经拿到过第一批数据 */
    var initialized = false
        private set

    // 搜索
    var query by mutableStateOf("")
    var matches by mutableStateOf<List<ConsoleMatch>>(emptyList())
        private set
    var matchesBySeq by mutableStateOf<Map<Long, List<ConsoleMatch>>>(emptyMap())
        private set
    var matchIndex by mutableIntStateOf(0)

    // 选择（长按一行进入，点另一行选中一段）
    var selAnchor by mutableStateOf<Long?>(null)
    var selEnd by mutableStateOf<Long?>(null)
    val selecting: Boolean get() = selAnchor != null

    /** 实时追加用的解析器（样式跨行延续） */
    internal val tailParser = ANSIParser()

    /** 翻页失败后的冷却（毫秒时间戳）：网络出错时别一直重试、一直提示 */
    internal var olderRetryAt = 0L
    internal var newerRetryAt = 0L

    val lo: Long get() = lines.firstOrNull()?.seq ?: (hi + 1)

    /** 往前还有没加载的行 */
    val hasOlder: Boolean get() = lo > maxOf(firstAvailable, floor + 1)

    /** 往后还有没加载的行（窗口脱离了最新） */
    val hasNewer: Boolean get() = !attached || latestSeq > hi

    /** 第一批数据：最近若干行 + 服务器最新的 seq */
    fun reset(list: List<ConsoleLine>, last: Long) {
        lines.clear()
        lines.addAll(list.filter { it.seq > floor })
        hi = maxOf(lines.lastOrNull()?.seq ?: last, floor)
        latestSeq = maxOf(latestSeq, last, hi)
        attached = hi >= latestSeq
        recomputeWidth()
        initialized = true
        loading = false
        error = null
        bump()
    }

    /**
     * 实时追加（日志流）。窗口不在末尾时只记下最新 seq。
     * @return 发现了缺口（中间有行没收到），调用方应按页补齐（[appendNewer]）
     */
    fun appendLive(batch: List<ConsoleLine>): Boolean {
        if (batch.isEmpty()) return false
        latestSeq = maxOf(latestSeq, batch.last().seq)
        if (!attached) return false
        val fresh = batch.filter { it.seq > hi }
        if (fresh.isEmpty()) return false
        if (fresh.first().seq > hi + 1) {
            // 缺了一段：先脱离，按页补齐后再接上
            attached = false
            return true
        }
        // 只要连续的部分
        var expect = hi + 1
        val ok = ArrayList<ConsoleLine>(fresh.size)
        for (l in fresh) {
            if (l.seq != expect) {
                attached = false
                break
            }
            ok += l
            expect++
        }
        add(ok)
        return !attached
    }

    private fun add(ok: List<ConsoleLine>) {
        if (ok.isEmpty()) return
        lines.addAll(ok)
        hi = ok.last().seq
        widen(ok)
        trim(dropHead = follow)
        bump()
    }

    /** 往后补的一页（from = hi + 1）；last 是服务器最新的 seq。补到最新时接上实时。 */
    fun appendNewer(page: List<ConsoleLine>, last: Long) {
        latestSeq = maxOf(latestSeq, last)
        var expect = hi + 1
        val ok = ArrayList<ConsoleLine>(page.size)
        for (l in page) {
            if (l.seq < expect) continue
            if (l.seq != expect) break
            ok += l
            expect++
        }
        if (ok.isNotEmpty()) {
            lines.addAll(ok)
            hi = ok.last().seq
            widen(ok)
            // 往后补都是用户在看底部：丢开头
            trim(dropHead = true)
        } else if (last <= hi) {
            // 服务器那边已经没有更新的行了
            latestSeq = hi
        }
        if (hi >= latestSeq) attached = true
        bump()
    }

    /** 窗口不在末尾时，实时流只记下最新的 seq */
    fun noteLatest(seq: Long) {
        if (seq > latestSeq) latestSeq = seq
    }

    /** 往前补的一页；first 是服务器能读到的最早一行（0 表示没有内容） */
    fun prependOlder(page: List<ConsoleLine>, first: Long) {
        val cur = lo
        if (first > 0) firstAvailable = first
        val older = page.filter { it.seq < cur && it.seq > floor }
        if (older.isEmpty()) {
            // 拿不到更早的了
            firstAvailable = cur
            bump()
            return
        }
        // 只要和窗口顶部连续的那段
        val contiguous = ArrayList<ConsoleLine>(older.size)
        var expect = cur - 1
        for (l in older.asReversed()) {
            if (l.seq != expect) break
            contiguous += l
            expect--
        }
        contiguous.reverse()
        if (contiguous.isEmpty()) {
            firstAvailable = cur
            bump()
            return
        }
        if (lines.isEmpty()) hi = contiguous.last().seq
        lines.addAll(0, contiguous)
        widen(contiguous)
        trim(dropHead = false)
        bump()
    }

    /** 太多时丢掉一头（多丢 10% 减少次数） */
    private fun trim(dropHead: Boolean) {
        val over = lines.size - maxLines
        if (over <= maxLines / 10) return
        val n = over
        if (dropHead) {
            lines.removeRange(0, n)
        } else {
            lines.removeRange(lines.size - n, lines.size)
            hi = lines.lastOrNull()?.seq ?: hi
            attached = false
        }
        recomputeWidth()
    }

    /** 清空：本地清掉，之后不再往前读 */
    fun clear() {
        floor = maxOf(hi, latestSeq)
        hi = floor
        attached = true
        lines.clear()
        maxWidth = 0
        matches = emptyList()
        matchesBySeq = emptyMap()
        matchIndex = 0
        selAnchor = null
        selEnd = null
        tailParser.reset()
        bump()
    }

    /** 直接跳到最新（缺口太大时）：调用方给出最新的一页 */
    fun jumpToTail(list: List<ConsoleLine>, last: Long) {
        lines.clear()
        latestSeq = maxOf(latestSeq, last)
        lines.addAll(list.filter { it.seq > floor })
        hi = maxOf(lines.lastOrNull()?.seq ?: last, floor)
        attached = hi >= latestSeq
        recomputeWidth()
        bump()
    }

    /** 跳到日志开头：调用方给出从服务器能读到的第一行开始的一页 */
    fun jumpToStart(list: List<ConsoleLine>, first: Long, last: Long) {
        lines.clear()
        lines.addAll(list.filter { it.seq > floor })
        if (first > 0) firstAvailable = first
        latestSeq = maxOf(latestSeq, last)
        hi = lines.lastOrNull()?.seq ?: floor
        attached = hi >= latestSeq
        follow = false
        recomputeWidth()
        bump()
    }

    fun failed(message: String) {
        error = message
        loading = false
    }

    /** 某个 seq 在窗口里的下标；不在时返回 -1 */
    fun indexOf(seq: Long): Int {
        var a = 0
        var b = lines.size - 1
        while (a <= b) {
            val m = (a + b) ushr 1
            val v = lines[m].seq
            when {
                v < seq -> a = m + 1
                v > seq -> b = m - 1
                else -> return m
            }
        }
        return -1
    }

    // ---------------------------------------------------------------- 搜索

    fun setMatches(list: List<ConsoleMatch>, keepIndex: Boolean) {
        matches = list
        matchesBySeq = list.groupBy { it.seq }
        if (!keepIndex || matchIndex >= list.size) matchIndex = maxOf(0, list.size - 1)
    }

    /** 下一个 / 上一个，返回要滚到的行号 */
    fun step(delta: Int): Long? {
        if (matches.isEmpty()) return null
        matchIndex = ((matchIndex + delta) % matches.size + matches.size) % matches.size
        return matches[matchIndex].seq
    }

    val currentMatch: ConsoleMatch? get() = matches.getOrNull(matchIndex)

    // ---------------------------------------------------------------- 选择

    fun startSelection(seq: Long) {
        selAnchor = seq
        selEnd = seq
    }

    fun extendSelection(seq: Long) {
        if (selAnchor == null) selAnchor = seq
        selEnd = seq
    }

    fun selectAll() {
        if (lines.isEmpty()) return
        selAnchor = lines.first().seq
        selEnd = lines.last().seq
    }

    fun cancelSelection() {
        selAnchor = null
        selEnd = null
    }

    fun isSelected(seq: Long): Boolean {
        val a = selAnchor ?: return false
        val b = selEnd ?: a
        return seq in minOf(a, b)..maxOf(a, b)
    }

    val selectedCount: Int
        get() {
            val a = selAnchor ?: return 0
            val b = selEnd ?: a
            val lo = minOf(a, b)
            val hi = maxOf(a, b)
            return lines.count { it.seq in lo..hi }
        }

    /** 选中的文本（没选时为全部已加载的行） */
    fun selectedText(): String {
        val a = selAnchor
        val b = selEnd ?: a
        val list = if (a == null || b == null) lines.toList() else lines.filter { it.seq in minOf(a, b)..maxOf(a, b) }
        return list.joinToString("\n") { it.text }
    }

    // ---------------------------------------------------------------- 内部

    private fun widen(list: List<ConsoleLine>) {
        var w = maxWidth
        for (l in list) if (l.width > w) w = l.width
        maxWidth = w
    }

    private fun recomputeWidth() {
        maxWidth = lines.maxOfOrNull { it.width } ?: 0
    }

    private fun bump() {
        version++
    }

    companion object {
        /** 内存里最多保留的行数（更早 / 更晚的需要时再按页读） */
        const val MAX_LINES = 50_000

        /** 往前 / 往后每页的行数 */
        const val PAGE = 1000

        /** 第一次显示最近多少行 */
        const val INITIAL = 500

        /** 预览、截图测试用：直接放一批行 */
        fun preview(lines: List<LogLine>, ended: Boolean = false): ConsoleState {
            val s = ConsoleState(null)
            val parser = ANSIParser()
            s.reset(ConsoleParse.lines(lines, parser), lines.lastOrNull()?.seq ?: 0)
            s.firstAvailable = lines.firstOrNull()?.seq ?: 1
            s.ended = ended
            return s
        }
    }
}
