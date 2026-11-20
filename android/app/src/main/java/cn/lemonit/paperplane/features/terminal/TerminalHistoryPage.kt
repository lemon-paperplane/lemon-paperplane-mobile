package cn.lemonit.paperplane.features.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.Route
import cn.lemonit.paperplane.kit.OpCaller
import cn.lemonit.paperplane.kit.model.TerminalHistory
import cn.lemonit.paperplane.kit.terminalHistory
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.shell.PageScaffold
import cn.lemonit.paperplane.ui.theme.PaperTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// 终端的完整输出（terminal.history，去掉颜色与光标控制的纯文本）：输出存在服务器上，App 关掉再回来也能从头看。
// 先显示最近一段，往上滑自动加载更早的；可以长按选择复制（手机上在 xterm 里选文字不方便，这里是主要的复制入口）。

/** 一个终端的完整输出 */
internal data class TerminalHistoryRoute(val terminalId: String) : Route {
    override val key get() = "terminalHistory:$terminalId"

    @Composable
    override fun Content() = TerminalHistoryPage(terminalId)
}

/**
 * 按字节偏移分页读来的输出拼成行（纯逻辑，单元测试覆盖）。行的 id 稳定：往前插的行 id 递减，往后加的递增，
 * 列表据此在加载更早内容时保持滚动位置。分页边界落在一行中间时，两段在边界处接起来。
 */
internal class HistoryBuffer {
    data class Line(val id: Long, val text: String)

    val lines = mutableStateListOf<Line>()
    /** 已加载部分的起止偏移 */
    var start = 0L
        private set
    var end = 0L
        private set
    /** 服务器上最早还能读到的偏移、累计输出字节数 */
    var first = 0L
        private set
    var total = 0L
        private set
    private var nextId = 0L
    private var prevId = -1L

    val loaded: Boolean get() = lines.isNotEmpty() || end > 0
    val hasOlder: Boolean get() = loaded && start > first
    val atEnd: Boolean get() = end >= total

    fun setInitial(h: TerminalHistory) {
        lines.clear()
        nextId = 0
        prevId = -1
        var parts = h.data.split('\n')
        // 从一个多字节字符中间开始读时，开头会有替换字符
        if (h.start > h.first) parts = listOf(parts[0].trimStart('�')) + parts.drop(1)
        for (p in parts) lines += Line(nextId++, p)
        start = h.start
        end = h.end
        first = h.first
        total = h.total
    }

    /** 接在已加载部分前面（h 是 [start] 之前的一段） */
    fun prepend(h: TerminalHistory) {
        if (!loaded) return setInitial(h)
        val parts = h.data.split('\n')
        var head = lines[0].text
        // 这一段的末尾在多字节字符中间被截掉了：原来开头那几个替换字符就是它
        if (h.end < start) head = head.trimStart('�')
        lines[0] = lines[0].copy(text = parts.last() + head)
        val older = parts.dropLast(1)
        val ids = older.indices.map { prevId - (older.size - 1 - it) }
        prevId -= older.size
        lines.addAll(0, older.mapIndexed { i, s -> Line(ids[i], s) })
        start = h.start
        first = h.first
        total = maxOf(total, h.total)
    }

    /** 接在已加载部分后面（h 从 [end] 开始） */
    fun append(h: TerminalHistory) {
        if (!loaded) return setInitial(h)
        val parts = h.data.split('\n')
        val last = lines.lastIndex
        lines[last] = lines[last].copy(text = lines[last].text + parts[0])
        for (p in parts.drop(1)) lines += Line(nextId++, p)
        end = h.end
        total = h.total
        first = h.first
    }

    /** 已加载的全文（最多 [maxChars] 个字符，超出时取最后的部分） */
    fun text(maxChars: Int = Int.MAX_VALUE): Pair<String, Boolean> {
        val all = lines.joinToString("\n") { it.text }
        return if (all.length <= maxChars) all to false else all.substring(all.length - maxChars) to true
    }
}

private const val PAGE = 256 * 1024
/** 复制的上限（系统剪贴板走进程间通信，太大会失败） */
private const val COPY_MAX = 200_000

/**
 * 分页读完整输出（terminal.history）拼进 [buf]。每段的起点先对齐到字符边界（偏移可能落在多字节字符中间：
 * 用不去控制符的小读取看开头有几个替换字符），这样相邻两段在边界处不丢字。
 */
internal class HistoryLoader(
    private val terminalId: String,
    private val page: Int = PAGE,
    private val api: () -> OpCaller = { AppModel.api() },
) {
    val buf = HistoryBuffer()

    /** 偏移对齐到下一个字符的开头 */
    private suspend fun aligned(offset: Long): Long {
        if (offset <= 0) return 0
        val p = api().terminalHistory(terminalId, offset, 4, strip = false)
        if (p.start != offset) return p.start // 早于最早能读到的位置：内核已经改到最早处
        return offset + p.data.takeWhile { it == '\uFFFD' }.length.coerceAtMost(3)
    }

    /** 最近的一段 */
    suspend fun latest() {
        val total = api().terminalHistory(terminalId, Long.MAX_VALUE / 4, 1, strip = false).total
        val from = aligned((total - page).coerceAtLeast(0))
        buf.setInitial(api().terminalHistory(terminalId, from, page, strip = true))
    }

    /** 往前一段 */
    suspend fun older() {
        if (!buf.hasOlder) return
        var from = aligned((buf.start - page).coerceAtLeast(buf.first))
        if (from >= buf.start) from = (buf.start - page).coerceAtLeast(buf.first)
        buf.prepend(api().terminalHistory(terminalId, from, (buf.start - from).toInt(), strip = true))
    }

    /** 接上之后的新输出（最多 [maxPages] 段） */
    suspend fun newer(maxPages: Int = 8) {
        var n = 0
        do {
            buf.append(api().terminalHistory(terminalId, buf.end, page, strip = true))
        } while (!buf.atEnd && ++n < maxPages)
    }
}

@Composable
private fun TerminalHistoryPage(terminalId: String) {
    val t = AppModel.terminals.list.firstOrNull { it.id == terminalId }
    val loader = remember(terminalId) { HistoryLoader(terminalId) }
    val buf = loader.buf
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** 第一次加载并滚到底之后才开始往前加载 */
    var scrolled by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    suspend fun guarded(block: suspend () -> Unit) {
        if (loading) return
        loading = true
        error = null
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: tr("common.failed")
        } finally {
            loading = false
        }
    }

    suspend fun scrollToEnd() {
        if (buf.lines.isNotEmpty()) list.scrollToItem(buf.lines.lastIndex + (if (buf.hasOlder) 1 else 0))
    }

    LaunchedEffect(terminalId) {
        guarded { loader.latest() }
        scrollToEnd()
        scrolled = true
    }
    // 滑到顶部附近时加载更早的一段（每加载一段行数变化，再判断一次）
    LaunchedEffect(buf) {
        snapshotFlow { (scrolled && list.firstVisibleItemIndex < 30 && buf.hasOlder) to buf.lines.size }.collect { (near, _) ->
            if (near && !loading) guarded { loader.older() }
        }
    }

    val c = PaperTheme.colors
    val s = PaperTheme.spacing
    PageScaffold(
        title = tr("mobile.terminal.history"),
        subtitle = t?.name,
        actions = {
            PaperIconButton("arrow-down-to-line", tr("mobile.terminal.toBottom"), onClick = {
                scope.launch {
                    guarded { loader.newer() }
                    scrollToEnd()
                }
            }, enabled = buf.loaded)
            PaperIconButton("copy", tr("mobile.terminal.copyAll"), onClick = {
                val (text, cut) = buf.text(COPY_MAX)
                Actions.copy(text, if (cut) tr("mobile.terminal.copiedTail") else null)
            }, enabled = buf.lines.isNotEmpty())
        },
    ) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))) {
            when {
                !buf.loaded && error != null -> PaperEmptyState(
                    "circle-alert", error!!, Modifier.fillMaxSize(), actionText = tr("connection.retry"), actionIcon = "refresh-cw",
                ) {
                    scope.launch {
                        guarded { loader.latest() }
                        scrollToEnd()
                    }
                }
                !buf.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PaperSpinner(tint = c.iconSecondary) }
                buf.lines.all { it.text.isEmpty() } -> PaperEmptyState("scroll-text", tr("mobile.terminal.historyEmpty"), Modifier.fillMaxSize())
                else -> SelectionContainer {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = list,
                        contentPadding = PaddingValues(horizontal = s.lg, vertical = s.md),
                    ) {
                        if (buf.hasOlder) {
                            item(key = "older") {
                                Box(Modifier.fillMaxWidth().padding(s.md), contentAlignment = Alignment.Center) {
                                    PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
                                }
                            }
                        }
                        items(buf.lines, key = { it.id }) { line ->
                            Text(line.text.ifEmpty { " " }, style = PaperTheme.typography.monoSmall, color = c.textPrimary)
                        }
                        if (error != null) {
                            item(key = "error") {
                                Text(error!!, style = PaperTheme.typography.small, color = c.danger, modifier = Modifier.padding(vertical = s.md))
                            }
                        }
                    }
                }
            }
        }
    }
}
