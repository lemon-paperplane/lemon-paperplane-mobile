package cn.lemonit.paperplane.kit.graph

import cn.lemonit.paperplane.kit.model.ResourceSample
import cn.lemonit.paperplane.kit.model.VCSGraphRow
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

// 与界面无关的几何计算（对照 Mac PaperplaneKit/Graph/CommitGraphGeometry.swift 与 Models/ResourceModels.swift 的 SparklineGeometry）。
// 坐标单位由调用方决定（Compose 里一般传 px，直接 Offset(p.x, p.y)）。不用 android.graphics（JVM 单元测试里是空实现）。

/** 点（y 向下） */
data class GPoint(val x: Float, val y: Float)

/** 矩形：左上角 + 宽高 */
data class GRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val minX: Float get() = x
    val maxX: Float get() = x + width
    val minY: Float get() = y
    val maxY: Float get() = y + height
}

/**
 * 提交图几何：把 VCSGraphRow（内核已算好的列与连线）换算成可直接绘制的线段和节点位置。
 *
 * 约定（与 vcs.go 一致）：第 i 行的 edges 描述「本行列号 → 下一行列号」的连线。
 * 因此画第 i 行时：上半段来自第 i-1 行的 edges（从行顶到本行中线），下半段来自本行 edges（从中线到行底）。
 * 斜线在半行内完成转弯，三端画法一致。
 */
class CommitGraphGeometry(
    val laneWidth: Float = 14f,
    val rowHeight: Float = 28f,
    val leftPadding: Float = 8f,
) {
    data class Segment(val from: GPoint, val to: GPoint, val color: Int)

    data class Row(
        val node: GPoint,
        val nodeColor: Int,
        val segments: List<Segment>,
        val width: Float,
    )

    /** 第 lane 列的中线 x */
    fun x(lane: Int): Float = leftPadding + lane * laneWidth + laneWidth / 2

    /** 计算一行。坐标以该行左上为原点、y 向下。 */
    fun row(current: VCSGraphRow, previous: VCSGraphRow?): Row {
        val mid = rowHeight / 2
        val segs = ArrayList<Segment>()
        // 上半段：上一行的连线终点落在本行
        previous?.edges?.forEach { e ->
            segs.add(Segment(GPoint((x(e.from) + x(e.to)) / 2, 0f), GPoint(x(e.to), mid), e.color))
        }
        // 下半段：本行出发去下一行
        for (e in current.edges) {
            segs.add(Segment(GPoint(x(e.from), mid), GPoint((x(e.from) + x(e.to)) / 2, rowHeight), e.color))
        }
        val lanes = maxOf(current.lanes, previous?.lanes ?: 0, current.lane + 1)
        return Row(GPoint(x(current.lane), mid), current.color, segs, leftPadding * 2 + lanes * laneWidth)
    }

    /** 一组提交的整体图宽（取最大列数） */
    fun width(rows: List<VCSGraphRow>): Float {
        val lanes = rows.maxOfOrNull { maxOf(it.lanes, it.lane + 1) } ?: 1
        return leftPadding * 2 + lanes * laneWidth
    }

    companion object {
        /** 颜色序号 → 调色板下标（设计变量 graphLane0…graphLane7） */
        fun paletteIndex(color: Int, paletteSize: Int = 8): Int {
            val m = color % paletteSize
            return if (m < 0) m + paletteSize else m
        }
    }
}

/** 趋势线几何：把采样换算到矩形里（x 按时间，y 按值；0 在底部）。三端画法一致。 */
object SparklineGeometry {
    /**
     * @param span 时间轴跨度（毫秒）；为 0 时按点的时间铺满（只有一个时间点时按序号均分）
     * @param end 时间轴右端（毫秒），默认最后一点
     * @param maxValue 纵轴上限（至少为数据最大值，留 10% 余量）
     */
    fun points(
        samples: List<ResourceSample>,
        rect: GRect,
        span: Long = 0,
        end: Long? = null,
        maxValue: Double? = null,
        value: (ResourceSample) -> Double,
    ): List<GPoint> {
        if (samples.isEmpty() || rect.width <= 0 || rect.height <= 0) return emptyList()
        val vmax = maxOf(maxValue ?: 0.0, (samples.maxOf(value)) * 1.1, 0.0001)
        val endT = end ?: samples.last().t
        val startT = if (span > 0) endT - span else samples.first().t
        val width = maxOf(endT - startT, 1L).toDouble()
        return samples.mapIndexed { i, s ->
            val x = if (span > 0 || (samples.size > 1 && endT > startT)) {
                (s.t - startT) / width
            } else {
                if (samples.size > 1) i.toDouble() / (samples.size - 1) else 1.0
            }
            val y = (value(s) / vmax).coerceIn(0.0, 1.0)
            GPoint(rect.minX + x.coerceIn(0.0, 1.0).toFloat() * rect.width, rect.maxY - y.toFloat() * rect.height)
        }
    }

    /** 离 x 最近的采样下标（按住显示数值用） */
    fun nearestIndex(points: List<GPoint>, x: Float): Int? {
        if (points.isEmpty()) return null
        var best = 0
        var bestD = Float.MAX_VALUE
        points.forEachIndexed { i, p ->
            val d = abs(p.x - x)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /** 纵轴上限取「好看」的整数：1、2、2.5、5 × 10^n */
    fun niceCeiling(v: Double): Double {
        if (v <= 0) return 1.0
        val exp = 10.0.pow(floor(log10(v)))
        for (m in doubleArrayOf(1.0, 2.0, 2.5, 5.0, 10.0)) if (m * exp >= v) return m * exp
        return 10 * exp
    }
}
