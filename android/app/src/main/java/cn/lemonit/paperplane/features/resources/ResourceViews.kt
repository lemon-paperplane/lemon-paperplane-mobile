package cn.lemonit.paperplane.features.resources

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.kit.graph.GRect
import cn.lemonit.paperplane.kit.graph.SparklineGeometry
import cn.lemonit.paperplane.kit.model.ResourceSample
import cn.lemonit.paperplane.kit.model.Run
import cn.lemonit.paperplane.kit.model.RunState
import cn.lemonit.paperplane.kit.model.RunStats
import cn.lemonit.paperplane.kit.runStats
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.ui.components.PaperBadge
import cn.lemonit.paperplane.ui.components.PaperBottomSheet
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperInfoButton
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperColors
import cn.lemonit.paperplane.ui.theme.PaperTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 资源监控的小组件（对照 Mac Features/Resources/ResourceViews.swift + RunResourcePanel.swift）：
// 趋势线、卡片里的资源数字（点开看详情）、运行页的可收起资源面板。全部用 Canvas 自己画。
// 配色：内存用强调色，CPU 用 warning 语气色；数字格式沿用 Format.memory / Format.cpu。

enum class ResourceMetric {
    MEMORY, CPU;

    fun value(s: ResourceSample): Double = if (this == MEMORY) s.rssMB else s.cpu

    fun color(c: PaperColors): Color = if (this == MEMORY) c.accent else c.warning

    fun format(v: Double): String = if (this == MEMORY) Format.memory(v) else Format.cpu(v)

    /** 纵轴最小上限：内存 64 MB、CPU 10%，避免几乎不变的数据被放大成锯齿 */
    val minScale: Double get() = if (this == MEMORY) 64.0 else 10.0
}

/** 趋势线（细线 + 浅色面积）。span 为时间轴跨度（毫秒），0 表示按已有点铺满。 */
@Composable
fun Sparkline(
    samples: List<ResourceSample>,
    metric: ResourceMetric,
    modifier: Modifier = Modifier,
    span: Long = 0,
    lineWidth: Dp = 1.25.dp,
) {
    val colors = PaperTheme.colors
    val color = metric.color(colors)
    val fillAlpha = if (colors.isDark) 0.22f else 0.14f
    Canvas(modifier) {
        val lw = lineWidth.toPx()
        val maxV = maxOf(metric.minScale, samples.maxOfOrNull { metric.value(it) } ?: 0.0)
        val rect = GRect(0f, lw, size.width, size.height - 2 * lw)
        val pts = SparklineGeometry.points(samples, rect, span = span, maxValue = maxV) { metric.value(it) }
        if (pts.isEmpty()) return@Canvas
        val line = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (p in pts.drop(1)) lineTo(p.x, p.y)
            if (pts.size == 1) lineTo(pts[0].x - 1, pts[0].y)
        }
        val area = Path().apply {
            addPath(line)
            lineTo(pts.last().x, size.height)
            lineTo(pts[0].x, size.height)
            close()
        }
        drawPath(area, color.copy(alpha = fillAlpha))
        drawPath(line, color, style = Stroke(lw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 某个运行的内存（+ 可选 CPU）数字，只观察这个运行自己的数据。还没收到 stats 时用 fallback（运行摘要里的值）。 */
@Composable
fun RunResourceText(runId: String, modifier: Modifier = Modifier, showCpu: Boolean = true, fallbackCpu: Double = 0.0, fallbackRss: Double = 0.0) {
    val res = remember(runId) { ResourceStore.resources(runId) }
    val s = res.snap
    val c = PaperTheme.colors
    val rss = if (s.t > 0) s.rssMB else fallbackRss
    val cpu = if (s.t > 0) s.cpu else fallbackCpu
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(Format.memory(rss), style = PaperTheme.typography.monoSmall, color = c.textSecondary, maxLines = 1)
        if (showCpu) Text(Format.cpu(cpu), style = PaperTheme.typography.monoSmall, color = c.textTertiary, maxLines = 1)
    }
}

/**
 * 卡片 / 列表行里的资源：内存趋势线 + 内存 + CPU；点一下从底部弹出详情（对照 Mac 悬浮详情）。
 *
 * `RunResourceInline(run.id, run.startedAt)`
 */
@Composable
fun RunResourceInline(
    runId: String,
    startedAt: Instant?,
    modifier: Modifier = Modifier,
    sparkWidth: Dp = 44.dp,
    showCpu: Boolean = true,
) {
    val res = remember(runId) { ResourceStore.resources(runId) }
    val s = res.snap
    val c = PaperTheme.colors
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(runId) { res.loadHistoryIfNeeded() }
    Row(
        modifier
            .heightIn(min = PaperTheme.sizes.touchTarget)
            .clip(RoundedCornerShape(PaperTheme.radius.md))
            .clickable(role = Role.Button) { open = true }
            .padding(horizontal = PaperTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Sparkline(s.samples, ResourceMetric.MEMORY, Modifier.width(sparkWidth).height(14.dp))
        Text(Format.memory(s.rssMB), style = PaperTheme.typography.monoSmall, color = c.textSecondary, maxLines = 1)
        if (showCpu) Text(Format.cpu(s.cpu), style = PaperTheme.typography.monoSmall, color = c.textTertiary, maxLines = 1)
    }
    if (open) ResourceDetailSheet(res, startedAt, onDismiss = { open = false })
}

/** 资源详情面板：内存 / CPU 当前值与 5 分钟趋势、峰值、进程数、运行时长 */
@Composable
private fun ResourceDetailSheet(res: RunResources, startedAt: Instant?, onDismiss: () -> Unit) {
    PaperBottomSheet(onDismiss = onDismiss, title = tr("resources.title")) {
        ResourceDetailContent(res.snap, startedAt, live = res.snap.state.isEmpty() || RunState.isActive(res.snap.state))
    }
}

@Composable
internal fun ResourceDetailContent(s: ResourceSnapshot, startedAt: Instant?, live: Boolean) {
    var hoverT by remember { mutableStateOf<Long?>(null) }
    val end = endTime(s.samples, live)
    Column(verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xl)) {
        ResourceChart(tr("resources.memory"), ResourceMetric.MEMORY, s.samples, end, hoverT, { hoverT = it }, Modifier.height(120.dp))
        ResourceChart(tr("resources.cpu"), ResourceMetric.CPU, s.samples, end, hoverT, { hoverT = it }, Modifier.height(120.dp))
        ResourceFacts(
            peak = s.peakRSSMB,
            procs = s.procs,
            uptime = startedAt?.let { Format.duration((System.currentTimeMillis() - it.toEpochMilli()) / 1000.0) } ?: "-",
            ports = emptyList(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            PaperInfoButton(tr("resources.info"))
        }
    }
}

private fun endTime(samples: List<ResourceSample>, live: Boolean): Long {
    val last = samples.lastOrNull()?.t ?: 0L
    return if (live) maxOf(last, System.currentTimeMillis() - 2000) else last
}

/** 峰值 / 进程数 / 运行时长 / 端口 小标签 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ResourceFacts(peak: Double, procs: Int, uptime: String, ports: List<Int>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm),
    ) {
        Fact(tr("resources.peak"), Format.memory(peak))
        Fact(tr("resources.procs"), procs.toString())
        Fact(tr("resources.uptime"), uptime)
        if (ports.isNotEmpty()) Fact(tr("resources.ports"), ports.joinToString(", "))
    }
}

@Composable
private fun Fact(k: String, v: String) {
    val c = PaperTheme.colors
    Row(
        Modifier
            .height(26.dp)
            .background(c.bgSubtle, RoundedCornerShape(PaperTheme.radius.sm))
            .padding(horizontal = PaperTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(k, style = PaperTheme.typography.small, color = c.textSecondary, maxLines = 1)
        Text(v, style = PaperTheme.typography.monoSmall, color = c.textPrimary, maxLines = 1)
    }
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * 趋势图：固定 5 分钟时间轴，按住拖动显示该时刻的值（与另一张图联动：hoverT 由调用方共享）。
 */
@Composable
fun ResourceChart(
    title: String,
    metric: ResourceMetric,
    samples: List<ResourceSample>,
    endT: Long,
    hoverT: Long?,
    onHover: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PaperTheme.colors
    val t = PaperTheme.typography
    val color = metric.color(c)
    val maxV = SparklineGeometry.niceCeiling(maxOf(metric.minScale, samples.maxOfOrNull { metric.value(it) } ?: 0.0))
    val hovered = hoverT?.let { h -> samples.minByOrNull { kotlin.math.abs(it.t - h) } }
    val shown = hovered ?: samples.lastOrNull()
    val fillAlpha = if (c.isDark) 0.22f else 0.14f
    Column(modifier, verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm)) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Text(title, style = t.small, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (hovered != null) {
                Text(timeFormatter.format(Instant.ofEpochMilli(hovered.t).atZone(ZoneId.systemDefault())), style = t.monoSmall, color = c.textTertiary)
            }
            Text(shown?.let { metric.format(metric.value(it)) } ?: "-", style = t.mono, color = c.textPrimary)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(
                Modifier
                    .matchParentSize()
                    .pointerInput(endT) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            fun at(x: Float) {
                                val frac = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                onHover(endT - SPAN + (frac * SPAN).toLong())
                            }
                            at(down.position.x)
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull() ?: break
                                if (!ch.pressed) break
                                at(ch.position.x)
                                ch.consume()
                            }
                            onHover(null)
                        }
                    },
            ) {
                val w = size.width
                val h = size.height
                val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                for (f in listOf(0f, 0.5f)) {
                    drawLine(c.borderSubtle, Offset(0f, h * f), Offset(w, h * f), strokeWidth = 1.dp.toPx(), pathEffect = dash)
                }
                drawLine(c.border, Offset(0f, h - 0.5f), Offset(w, h - 0.5f), strokeWidth = 1.dp.toPx())
                val pts = SparklineGeometry.points(samples, GRect(0f, 1f, w, h - 2f), span = SPAN, end = endT, maxValue = maxV / 1.1) { metric.value(it) }
                if (pts.isNotEmpty()) {
                    val line = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        for (p in pts.drop(1)) lineTo(p.x, p.y)
                    }
                    val area = Path().apply {
                        addPath(line)
                        lineTo(pts.last().x, h)
                        lineTo(pts[0].x, h)
                        close()
                    }
                    drawPath(area, color.copy(alpha = fillAlpha))
                    drawPath(line, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                if (hovered != null) {
                    val i = samples.indexOf(hovered)
                    if (i in pts.indices) {
                        val p = pts[i]
                        drawLine(c.textTertiary, Offset(p.x, 0f), Offset(p.x, h), strokeWidth = 1.dp.toPx())
                        drawCircle(c.bgSurface, radius = 3.dp.toPx(), center = Offset(p.x, p.y))
                        drawCircle(color, radius = 3.dp.toPx(), center = Offset(p.x, p.y), style = Stroke(1.5.dp.toPx()))
                    }
                }
            }
            Text(metric.format(maxV), style = t.caption, color = c.textTertiary, modifier = Modifier.padding(start = 2.dp))
        }
        Row {
            Text(tr("resources.axis.past"), style = t.caption, color = c.textTertiary)
            Spacer(Modifier.weight(1f))
            Text(tr("resources.axis.now"), style = t.caption, color = c.textTertiary)
        }
    }
}

private const val SPAN = 300_000L

/**
 * 运行页控制台上方的「资源」面板（可收起，手机上默认收起）：收起时一行趋势线 + 当前值；
 * 展开时内存与 CPU 趋势图、峰值 / 进程数 / 运行时长 / 端口。展开且可见时每 2 秒取一次 run.stats。
 */
@Composable
fun RunResourcePanel(run: Run, modifier: Modifier = Modifier, initiallyOpen: Boolean = false) {
    val res = remember(run.id) { ResourceStore.resources(run.id) }
    var open by rememberSaveable(run.id) { mutableStateOf(initiallyOpen) }
    var stats by remember(run.id) { mutableStateOf<RunStats?>(null) }
    var hoverT by remember { mutableStateOf<Long?>(null) }
    val fg = AppModel.foreground
    LaunchedEffect(run.id, open, run.isActive, fg) {
        if (!open || !fg) {
            res.loadHistoryIfNeeded()
            return@LaunchedEffect
        }
        while (true) {
            val c = AppModel.client ?: break
            try {
                val st = c.runStats(run.id)
                stats = st
                ResourceStore.apply(st)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            if (!run.isActive) break
            delay(2000)
        }
    }
    RunResourcePanelContent(
        run = run,
        snap = res.snap,
        stats = stats,
        open = open,
        onToggle = { open = !open },
        hoverT = hoverT,
        onHover = { hoverT = it },
        modifier = modifier,
    )
}

/** 资源面板外观（数据作为参数，截图测试直接画） */
@Composable
internal fun RunResourcePanelContent(
    run: Run,
    snap: ResourceSnapshot,
    stats: RunStats?,
    open: Boolean,
    onToggle: () -> Unit,
    hoverT: Long?,
    onHover: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    Column(modifier.fillMaxWidth().background(c.bgContent)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(PaperTheme.sizes.touchTarget)
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(start = sp.lg, end = sp.lg),
            horizontalArrangement = Arrangement.spacedBy(sp.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaperIcon(if (open) "chevron-down" else "chevron-right", size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
            Text(tr("resources.title"), style = PaperTheme.typography.label, color = c.textPrimary)
            if (!run.isActive) PaperBadge(tr("resources.ended"))
            Spacer(Modifier.weight(1f))
            if (!open) Sparkline(snap.samples, ResourceMetric.MEMORY, Modifier.width(72.dp).height(14.dp), span = SPAN)
            Text(Format.memory(snap.rssMB), style = PaperTheme.typography.monoSmall, color = ResourceMetric.MEMORY.color(c))
            Text(Format.cpu(snap.cpu), style = PaperTheme.typography.monoSmall, color = ResourceMetric.CPU.color(c))
        }
        if (open) {
            Column(Modifier.padding(start = sp.lg, end = sp.lg, bottom = sp.md), verticalArrangement = Arrangement.spacedBy(sp.md)) {
                val end = endTime(snap.samples, run.isActive)
                Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(sp.xl)) {
                    ResourceChart(tr("resources.memory"), ResourceMetric.MEMORY, snap.samples, end, hoverT, onHover, Modifier.weight(1f))
                    ResourceChart(tr("resources.cpu"), ResourceMetric.CPU, snap.samples, end, hoverT, onHover, Modifier.weight(1f))
                }
                val ports = stats?.ports?.takeIf { it.isNotEmpty() } ?: run.ports
                val uptime = stats?.uptimeSec?.takeIf { it > 0 }?.let { Format.duration(it.toDouble()) }
                    ?: run.startedAt?.let { st -> Format.duration(((run.endedAt ?: Instant.now()).toEpochMilli() - st.toEpochMilli()) / 1000.0) }
                    ?: "-"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ResourceFacts(
                        peak = maxOf(snap.peakRSSMB, run.peakRSSMB),
                        procs = maxOf(snap.procs, stats?.processes?.size ?: 0),
                        uptime = uptime,
                        ports = ports,
                        modifier = Modifier.weight(1f),
                    )
                    PaperInfoButton(tr("resources.info"))
                }
            }
        }
        PaperDivider(subtle = true)
    }
}
