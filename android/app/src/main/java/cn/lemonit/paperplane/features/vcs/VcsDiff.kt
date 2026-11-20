package cn.lemonit.paperplane.features.vcs

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.kit.model.VCSDiff
import cn.lemonit.paperplane.kit.model.VCSDiffArgs
import cn.lemonit.paperplane.kit.model.VCSDiffMode
import cn.lemonit.paperplane.kit.model.VCSFileContentArgs
import cn.lemonit.paperplane.kit.model.VCSHunksArgs
import cn.lemonit.paperplane.kit.model.VCSOp
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.kit.vcsDiff
import cn.lemonit.paperplane.kit.vcsDiscardHunks
import cn.lemonit.paperplane.kit.vcsFileContent
import cn.lemonit.paperplane.kit.vcsStageHunks
import cn.lemonit.paperplane.kit.vcsUnstageHunks
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperLoadingPage
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.math.max

// 差异：要看什么（工作区 / 提交）、加载状态、统一视图的行布局、界面（行号、增删着色、块头、长行横向滚动、
// 二进制 / 过大文件提示、图片前后对比、按块暂存 / 取消暂存 / 丢弃）。对照 Mac VCSDiffView.swift（手机上只做统一视图）。

/** 变更列表里的一侧 */
enum class VcsSide { STAGED, UNSTAGED, UNTRACKED, CONFLICT }

/** 要看的差异 */
sealed interface VcsDiffKind {
    /** 工作区；side 决定 mode（已暂存 = staged，未暂存 = unstaged，未跟踪 / 冲突 = head） */
    data class Working(val side: VcsSide) : VcsDiffKind

    /** 提交对第 parent 个父提交（储藏也是提交） */
    data class Commit(val hash: String, val parent: Int = 1) : VcsDiffKind
}

data class VcsDiffTarget(val path: String, val oldPath: String? = null, val kind: VcsDiffKind, val status: String? = null) {
    val mode: String
        get() = when (val k = kind) {
            is VcsDiffKind.Working -> when (k.side) {
                VcsSide.STAGED -> VCSDiffMode.STAGED
                VcsSide.UNSTAGED -> VCSDiffMode.UNSTAGED
                else -> VCSDiffMode.HEAD
            }
            is VcsDiffKind.Commit -> VCSDiffMode.COMMIT
        }

    val side: VcsSide? get() = (kind as? VcsDiffKind.Working)?.side

    fun args(context: Int, ignoreWhitespace: Boolean, noLimit: Boolean): VCSDiffArgs {
        val k = kind
        return VCSDiffArgs(
            path = path,
            oldPath = oldPath,
            context = context,
            mode = mode,
            commit = (k as? VcsDiffKind.Commit)?.hash,
            parent = (k as? VcsDiffKind.Commit)?.parent?.takeIf { it > 1 },
            ignoreWhitespace = ignoreWhitespace.takeIf { it },
            noLimit = noLimit.takeIf { it },
        )
    }

    /** 旧侧版本（fileContent 的 ref） */
    val oldRef: String
        get() = when (val k = kind) {
            is VcsDiffKind.Working -> if (k.side == VcsSide.UNSTAGED) ":" else "HEAD"
            is VcsDiffKind.Commit -> "${k.hash}^${k.parent}"
        }

    /** 新侧版本（"" 为工作区） */
    val newRef: String
        get() = when (val k = kind) {
            is VcsDiffKind.Working -> if (k.side == VcsSide.STAGED) ":" else ""
            is VcsDiffKind.Commit -> k.hash
        }

    val isWorking: Boolean get() = kind is VcsDiffKind.Working
}

/** 块上的操作 */
enum class VcsHunkMode {
    NONE,

    /** 未暂存一侧：暂存块 + 丢弃块 */
    STAGE,

    /** 已暂存一侧：取消暂存块 */
    UNSTAGE,
    ;

    companion object {
        fun of(t: VcsDiffTarget?): VcsHunkMode = when (t?.side) {
            VcsSide.UNSTAGED -> STAGE
            VcsSide.STAGED -> UNSTAGE
            else -> NONE
        }
    }
}

// ---------------------------------------------------------------- 行布局

sealed interface VcsDiffRow {
    data class Hunk(val index: Int, val header: String) : VcsDiffRow

    /** 折叠的上下文：count < 0 表示到文件末尾、行数未知 */
    data class Gap(val index: Int, val count: Int) : VcsDiffRow

    /** kind：' ' 上下文 | '+' | '-' | '~' 展开出来的上下文 */
    data class Line(val kind: Char, val old: Int, val new: Int, val text: String, val noEol: Boolean = false) : VcsDiffRow
}

class VcsDiffLayout(val rows: List<VcsDiffRow>, val changeStarts: List<Int>, val maxLine: Int, val maxChars: Int) {
    companion object {
        /** 显示用：制表符换成 4 个空格 */
        fun display(s: String): String = s.replace("\t", "    ")

        fun build(d: VCSDiff, newText: List<String>?, expanded: Set<Int>): VcsDiffLayout {
            val rows = ArrayList<VcsDiffRow>()
            val starts = ArrayList<Int>()
            var maxLine = 0
            var maxChars = 0
            fun add(l: VcsDiffRow.Line) {
                rows.add(l)
                maxLine = max(maxLine, max(l.old, l.new))
                maxChars = max(maxChars, display(l.text).length)
            }
            fun context(from: Int, to: Int, offset: Int) {
                val text = newText ?: return
                if (from > to) return
                for (n in from..to) if (n >= 1 && n <= text.size) add(VcsDiffRow.Line('~', n + offset, n, text[n - 1]))
            }
            fun start(start: Int, count: Int) = if (count == 0) start + 1 else start
            var prevNewEnd = 1
            val hunks = d.hunks
            hunks.forEachIndexed { i, h ->
                val newA = start(h.newStart, h.newLines)
                val oldA = start(h.oldStart, h.oldLines)
                val gap = newA - prevNewEnd
                val isNewFile = h.oldStart == 0 && h.oldLines == 0
                if (gap > 0 && !isNewFile) {
                    if (i in expanded && newText != null) context(prevNewEnd, newA - 1, oldA - newA) else rows.add(VcsDiffRow.Gap(i, gap))
                }
                rows.add(VcsDiffRow.Hunk(i, h.header))
                val dels = ArrayList<VcsDiffRow.Line>()
                val adds = ArrayList<VcsDiffRow.Line>()
                var inChange = false
                fun flush() {
                    dels.forEach { add(it) }
                    adds.forEach { add(it) }
                    dels.clear()
                    adds.clear()
                }
                for (l in h.lines) {
                    val k = l.kind.firstOrNull() ?: ' '
                    val line = VcsDiffRow.Line(k, l.old, l.new, l.text, l.noEol)
                    if (k == '-' || k == '+') {
                        if (!inChange) {
                            starts.add(rows.size)
                            inChange = true
                        }
                        if (k == '-') dels.add(line) else adds.add(line)
                    } else {
                        flush()
                        inChange = false
                        add(line)
                    }
                }
                flush()
                prevNewEnd = newA + h.newLines
                // 文件末尾的折叠：只有取到全文才知道还剩多少行
                if (i == hunks.lastIndex && !isNewFile && d.status != "D") {
                    val oldEnd = oldA + h.oldLines
                    if (newText != null) {
                        val remain = newText.size - prevNewEnd + 1
                        if (remain > 0) {
                            if (hunks.size in expanded) context(prevNewEnd, newText.size, oldEnd - prevNewEnd) else rows.add(VcsDiffRow.Gap(hunks.size, remain))
                        }
                    } else {
                        rows.add(VcsDiffRow.Gap(hunks.size, -1))
                    }
                }
            }
            return VcsDiffLayout(rows, starts, maxLine, maxChars.coerceAtMost(4000))
        }
    }
}

// ---------------------------------------------------------------- 加载

/** 一个差异的加载状态（页面里 remember；scope 用页面的协程作用域） */
class VcsDiffState(val repo: VcsRepo, private val scope: CoroutineScope) {
    var target by mutableStateOf<VcsDiffTarget?>(null)
        private set
    var diff by mutableStateOf<VCSDiff?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var noLimit by mutableStateOf(false)
    var ignoreWhitespace by mutableStateOf(VcsPrefs.diffIgnoreWhitespace)
    var newText by mutableStateOf<List<String>?>(null)
        private set
    var expanded by mutableStateOf<Set<Int>>(emptySet())
        private set
    var oldImage by mutableStateOf<ImageBitmap?>(null)
        private set
    var newImage by mutableStateOf<ImageBitmap?>(null)
        private set
    var oldSize by mutableStateOf(0)
        private set
    var newSize by mutableStateOf(0)
        private set
    var busyHunk by mutableStateOf<Int?>(null)
        private set

    private var job: Job? = null
    private var cacheKey: Triple<VCSDiff, List<String>?, Set<Int>>? = null
    private var cacheValue: VcsDiffLayout? = null

    /** 布局缓存：diff、全文、展开集合都没变（同一对象）时不重新生成几千行 */
    val layout: VcsDiffLayout?
        get() {
            val d = diff ?: return null
            val t = newText
            val e = expanded
            val k = cacheKey
            val v = cacheValue
            if (k != null && v != null && k.first === d && k.second === t && k.third === e) return v
            return VcsDiffLayout.build(d, t, e).also {
                cacheKey = Triple(d, t, e)
                cacheValue = it
            }
        }

    /** 块操作是否可用（忽略空白时块的划分与插件按块操作用的 diff 不同，不给块操作） */
    val hunkMode: VcsHunkMode get() = if (ignoreWhitespace) VcsHunkMode.NONE else VcsHunkMode.of(target)

    /** 预览 / 截图测试用 */
    internal fun setPreview(t: VcsDiffTarget, d: VCSDiff) {
        target = t
        diff = d
    }

    fun set(t: VcsDiffTarget?) {
        if (t == target) return
        target = t
        diff = null
        error = null
        noLimit = false
        reload(resetExpansion = true)
    }

    fun reload(resetExpansion: Boolean = false) {
        if (repo.session.preview) return
        job?.cancel()
        if (resetExpansion) {
            newText = null
            expanded = emptySet()
            oldImage = null
            newImage = null
        }
        val t = target ?: run {
            diff = null
            error = null
            return
        }
        loading = diff == null || resetExpansion
        job = scope.launch {
            try {
                val d = repo.api().vcsDiff(repo.projectId, repo.root, t.args(CONTEXT, ignoreWhitespace, noLimit))
                if (target != t) return@launch
                diff = d
                error = null
                loading = false
                if (d.binary && VcsPath.isImage(t.path)) loadImages(t)
                if (expanded.isNotEmpty() || newText != null) {
                    newText = null
                    loadNewText()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (target != t) return@launch
                error = e.message ?: tr("common.failed")
                diff = null
                loading = false
            }
        }
    }

    private suspend fun loadImages(t: VcsDiffTarget) {
        val api = runCatching { repo.api() }.getOrNull() ?: return
        val maxBytes = 32 shl 20
        val o = scope.async {
            if (t.isWorking && t.status == "?") null
            else runCatching { api.vcsFileContent(repo.projectId, repo.root, VCSFileContentArgs(t.oldPath ?: t.path, t.oldRef, maxBytes)) }.getOrNull()
        }
        val n = scope.async {
            runCatching { api.vcsFileContent(repo.projectId, repo.root, VCSFileContentArgs(t.path, t.newRef.ifEmpty { null }, maxBytes)) }.getOrNull()
        }
        val oc = o.await()
        val nc = n.await()
        if (target != t) return
        fun decode(b: ByteArray?): ImageBitmap? = b?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
        oldImage = oc?.takeIf { it.exists }?.let { decode(it.bytes()) }
        newImage = nc?.takeIf { it.exists }?.let { decode(it.bytes()) }
        oldSize = oc?.size ?: 0
        newSize = nc?.size ?: 0
    }

    /** 取新侧全文（展开上下文用） */
    private suspend fun loadNewText() {
        val t = target ?: return
        if (newText != null) return
        val c = runCatching {
            repo.api().vcsFileContent(repo.projectId, repo.root, VCSFileContentArgs(t.path, t.newRef.ifEmpty { null }, 8 shl 20))
        }.getOrNull() ?: return
        if (target != t || !c.exists) return
        val text = c.text ?: return
        val lines = text.split("\n").toMutableList()
        if (text.endsWith("\n")) lines.removeAt(lines.lastIndex)
        newText = lines.map { it.removeSuffix("\r") }
    }

    fun expand(gap: Int) {
        scope.launch {
            loadNewText()
            expanded = expanded + gap
        }
    }

    fun expandAll() {
        scope.launch {
            loadNewText()
            diff?.let { d -> expanded = (0..d.hunks.size).toSet() }
        }
    }

    /** 按块暂存 / 取消暂存 / 丢弃 */
    fun hunkOp(op: String, index: Int) {
        val d = diff ?: return
        val t = target ?: return
        if (index !in d.hunks.indices) return
        val title = when (op) {
            VCSOp.STAGE_HUNKS -> tr("vcs.diff.stageHunk")
            VCSOp.UNSTAGE_HUNKS -> tr("vcs.diff.unstageHunk")
            else -> tr("vcs.diff.discardHunk")
        }
        val args = VCSHunksArgs(t.path, listOf(index), d.context ?: CONTEXT, d.mode, listOf(d.hunks[index].header), t.oldPath)
        busyHunk = index
        repo.perform(title, quiet = true, then = { reload() }) { api ->
            try {
                when (op) {
                    VCSOp.STAGE_HUNKS -> api.vcsStageHunks(repo.projectId, repo.root, args)
                    VCSOp.UNSTAGE_HUNKS -> api.vcsUnstageHunks(repo.projectId, repo.root, args)
                    else -> api.vcsDiscardHunks(repo.projectId, repo.root, args)
                }
            } finally {
                busyHunk = null
            }
        }
    }

    fun confirmDiscardHunk(index: Int) {
        Dialogs.confirm(tr("vcs.diff.discardHunk.title"), tr("vcs.diff.discardHunk.message"), confirmText = tr("vcs.discard"), danger = true) {
            hunkOp(VCSOp.DISCARD_HUNKS, index)
        }
    }

    companion object {
        const val CONTEXT = 3
    }
}

// ---------------------------------------------------------------- 界面

/**
 * 差异内容（无状态：截图测试直接喂数据）。统一视图：旧 / 新两列行号、增删着色、块头（可按块操作）、折叠的上下文（点开展开）。
 * wrap = false 时长行横向滚动（块头和折叠行固定在可见区域）。
 */
@Composable
fun VcsDiffBody(
    diff: VCSDiff?,
    layout: VcsDiffLayout?,
    modifier: Modifier = Modifier,
    path: String? = null,
    loading: Boolean = false,
    error: String? = null,
    wrap: Boolean = false,
    hunkMode: VcsHunkMode = VcsHunkMode.NONE,
    busyHunk: Int? = null,
    oldImage: ImageBitmap? = null,
    newImage: ImageBitmap? = null,
    oldSize: Int = 0,
    newSize: Int = 0,
    listState: LazyListState = rememberLazyListState(),
    onRetry: () -> Unit = {},
    onShowAnyway: () -> Unit = {},
    onExpandGap: (Int) -> Unit = {},
    onStageHunk: (Int) -> Unit = {},
    onUnstageHunk: (Int) -> Unit = {},
    onDiscardHunk: (Int) -> Unit = {},
) {
    val c = PaperTheme.colors
    Box(modifier.fillMaxSize().background(c.bgContent)) {
        when {
            error != null -> Column(Modifier.padding(PaperTheme.spacing.lg)) {
                PaperAlert(Tone.Danger, tr("vcs.diff.failed"), message = error) {
                    PaperButton(tr("connection.retry"), onClick = onRetry, size = ButtonSize.Small)
                }
            }
            diff == null -> if (loading) PaperLoadingPage() else PaperEmptyState("file-diff", tr("vcs.diff.none"))
            diff.binary -> if (path != null && VcsPath.isImage(path)) {
                ImageCompare(oldImage, newImage, oldSize, newSize)
            } else {
                PaperEmptyState("binary", tr("vcs.diff.binary"), Modifier.align(Alignment.Center))
            }
            diff.tooLarge -> PaperEmptyState(
                "file-text", tr("vcs.diff.tooLarge"), Modifier.align(Alignment.Center),
                actionText = tr("vcs.diff.showAnyway"), onAction = onShowAnyway,
            )
            diff.hunks.isEmpty() -> Identical(diff, Modifier.align(Alignment.Center))
            layout != null -> DiffLines(layout, wrap, hunkMode, busyHunk, listState, onExpandGap, onStageHunk, onUnstageHunk, onDiscardHunk)
        }
    }
}

@Composable
private fun Identical(d: VCSDiff, modifier: Modifier) {
    val c = PaperTheme.colors
    Column(modifier.padding(PaperTheme.spacing.huge), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md)) {
        PaperIcon("file-check", size = PaperTheme.sizes.iconEmpty, tint = c.iconSecondary)
        Text(tr("vcs.diff.identical"), style = PaperTheme.typography.body, color = c.textSecondary)
        val o = d.oldMode
        val n = d.newMode
        if (o != null && n != null && o != n) Text(tr("vcs.diff.modeChanged", o, n), style = PaperTheme.typography.monoSmall, color = c.textTertiary)
        val sim = d.similarity
        if (sim != null && sim > 0 && d.oldPath != null) Text(tr("vcs.diff.similarity", sim), style = PaperTheme.typography.small, color = c.textTertiary)
    }
}

@Composable
private fun ImageCompare(old: ImageBitmap?, new: ImageBitmap?, oldSize: Int, newSize: Int) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PaperTheme.spacing.lg), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.lg)) {
        ImagePane(tr("vcs.diff.before"), old, oldSize)
        ImagePane(tr("vcs.diff.after"), new, newSize)
    }
}

@Composable
private fun ImagePane(title: String, img: ImageBitmap?, size: Int) {
    val c = PaperTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = PaperTheme.typography.label, color = c.textSecondary)
            if (img != null) Text("${img.width}×${img.height}  ·  ${Format.bytes(size.toLong())}", style = PaperTheme.typography.monoSmall, color = c.textTertiary)
        }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 120.dp).background(c.bgSubtle).padding(PaperTheme.spacing.md),
            contentAlignment = Alignment.Center,
        ) {
            if (img != null) {
                Image(img, title, Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
            } else {
                Text(tr("vcs.diff.noFile"), style = PaperTheme.typography.body, color = c.textTertiary)
            }
        }
    }
}

@Composable
private fun DiffLines(
    layout: VcsDiffLayout,
    wrap: Boolean,
    hunkMode: VcsHunkMode,
    busyHunk: Int?,
    listState: LazyListState,
    onExpandGap: (Int) -> Unit,
    onStage: (Int) -> Unit,
    onUnstage: (Int) -> Unit,
    onDiscard: (Int) -> Unit,
) {
    val t = PaperTheme.typography
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charW: Dp = remember(t.mono, density) { with(density) { (measurer.measure("0123456789", t.mono).size.width / 10f).toDp() } }
    val numW: Dp = remember(t.monoSmall, density) { with(density) { (measurer.measure("0123456789", t.monoSmall).size.width / 10f).toDp() } }
    val digits = max(layout.maxLine.toString().length, 2)
    val gutter = numW * digits + 8.dp
    val marker = charW + 6.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxWidth
        val hs = rememberScrollState()
        val contentWidth = if (wrap) viewport else maxOf(viewport, gutter * 2 + marker + charW * layout.maxChars + 24.dp)
        Box(Modifier.fillMaxSize().then(if (wrap) Modifier else Modifier.horizontalScroll(hs))) {
            LazyColumn(Modifier.width(contentWidth).fillMaxSize(), state = listState) {
                itemsIndexed(layout.rows, key = { i, _ -> i }) { _, row ->
                    val pin = if (wrap) Modifier else Modifier.width(viewport).offset { IntOffset(hs.value, 0) }
                    when (row) {
                        is VcsDiffRow.Hunk -> HunkHeader(row, hunkMode, busyHunk == row.index, pin, onStage, onUnstage, onDiscard)
                        is VcsDiffRow.Gap -> GapRow(row, pin) { onExpandGap(row.index) }
                        is VcsDiffRow.Line -> LineRow(row, wrap, gutter, marker)
                    }
                }
                item { Box(Modifier.height(PaperTheme.spacing.huge)) }
            }
        }
    }
}

@Composable
private fun HunkHeader(
    row: VcsDiffRow.Hunk,
    mode: VcsHunkMode,
    busy: Boolean,
    modifier: Modifier,
    onStage: (Int) -> Unit,
    onUnstage: (Int) -> Unit,
    onDiscard: (Int) -> Unit,
) {
    val c = PaperTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 40.dp)
            .background(c.diffHunkBg)
            .padding(start = PaperTheme.spacing.md, end = PaperTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.header, style = PaperTheme.typography.monoSmall, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (busy) PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = c.accent)
        when (mode) {
            VcsHunkMode.STAGE -> {
                PaperButton(tr("vcs.diff.discardHunk"), onClick = { onDiscard(row.index) }, kind = ButtonKind.Text, size = ButtonSize.Small, icon = "undo-2", enabled = !busy)
                PaperButton(tr("vcs.diff.stageHunk"), onClick = { onStage(row.index) }, kind = ButtonKind.Text, size = ButtonSize.Small, icon = "plus", enabled = !busy)
            }
            VcsHunkMode.UNSTAGE ->
                PaperButton(tr("vcs.diff.unstageHunk"), onClick = { onUnstage(row.index) }, kind = ButtonKind.Text, size = ButtonSize.Small, icon = "minus", enabled = !busy)
            VcsHunkMode.NONE -> {}
        }
    }
}

@Composable
private fun GapRow(row: VcsDiffRow.Gap, modifier: Modifier, onClick: () -> Unit) {
    val c = PaperTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(c.bgSubtle)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = PaperTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaperIcon("unfold-vertical", size = 16.dp, tint = c.iconSecondary)
        Text(
            if (row.count < 0) tr("vcs.diff.expandRest") else tr("vcs.diff.unchangedLines", row.count),
            style = PaperTheme.typography.small,
            color = c.textTertiary,
        )
    }
}

@Composable
private fun LineRow(l: VcsDiffRow.Line, wrap: Boolean, gutter: Dp, marker: Dp) {
    val c = PaperTheme.colors
    val t = PaperTheme.typography
    val bg = when (l.kind) {
        '+' -> c.diffAddedBg
        '-' -> c.diffRemovedBg
        else -> Color.Transparent
    }
    Row(Modifier.fillMaxWidth().background(bg).defaultMinSize(minHeight = 20.dp), verticalAlignment = Alignment.Top) {
        Text(if (l.kind == '+' || l.old <= 0) "" else l.old.toString(), style = t.monoSmall, color = c.textTertiary, textAlign = TextAlign.End, modifier = Modifier.width(gutter).padding(end = 6.dp, top = 1.dp), maxLines = 1)
        Text(if (l.kind == '-' || l.new <= 0) "" else l.new.toString(), style = t.monoSmall, color = c.textTertiary, textAlign = TextAlign.End, modifier = Modifier.width(gutter).padding(end = 6.dp, top = 1.dp), maxLines = 1)
        Text(
            if (l.kind == '+' || l.kind == '-') l.kind.toString() else " ",
            style = t.mono,
            color = if (l.kind == '+') c.diffAddedText else c.diffRemovedText,
            modifier = Modifier.width(marker).padding(start = 2.dp),
        )
        Text(
            VcsDiffLayout.display(l.text),
            style = t.mono,
            color = if (l.kind == '~') c.textSecondary else c.textPrimary,
            softWrap = wrap,
            maxLines = if (wrap) Int.MAX_VALUE else 1,
            modifier = if (wrap) Modifier.weight(1f, fill = false) else Modifier,
        )
        if (l.noEol) PaperIcon("circle-minus", size = 12.dp, tint = c.textTertiary, modifier = Modifier.padding(start = 4.dp, top = 3.dp), contentDescription = tr("vcs.diff.noEol"))
    }
}

/** 差异页的标题下工具行：状态、增删行数、上一处 / 下一处变更 */
@Composable
fun VcsDiffInfoRow(
    target: VcsDiffTarget,
    diff: VCSDiff?,
    layout: VcsDiffLayout?,
    current: Int,
    loading: Boolean,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    Row(
        modifier.fillMaxWidth().height(44.dp).padding(start = sp.lg, end = sp.xs),
        horizontalArrangement = Arrangement.spacedBy(sp.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val st = diff?.status ?: target.status
        if (st != null) {
            VcsStatusLetter(st)
            Text(VcsStyle.title(st), style = PaperTheme.typography.small, color = c.textSecondary)
        }
        if (diff != null) VcsLineStats(diff.additions, diff.deletions)
        Box(Modifier.weight(1f))
        if (loading) PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
        val starts = layout?.changeStarts.orEmpty()
        if (starts.isNotEmpty()) {
            Text("${max(current + 1, 0)}/${starts.size}", style = PaperTheme.typography.monoSmall, color = c.textTertiary)
            PaperIconButton("arrow-up", tr("vcs.diff.prev"), onClick = { onJump(-1) }, enabled = current > 0)
            PaperIconButton("arrow-down", tr("vcs.diff.next"), onClick = { onJump(1) }, enabled = current < starts.size - 1)
        }
    }
    PaperDivider(subtle = true)
}
