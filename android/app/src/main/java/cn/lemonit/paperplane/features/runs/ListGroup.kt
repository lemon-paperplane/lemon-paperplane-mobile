package cn.lemonit.paperplane.features.runs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.theme.PaperTheme

// 长列表里的「卡片分组」：每行是 LazyColumn 的一个 item（只组合可见的），合起来看是一张圆角描边的卡片，行间有分隔线。

/**
 * 分组卡片里的一格：底色 + 描边（只画属于这一格的边：第一格画上边和上圆角，最后一格画下边和下圆角）。
 */
internal fun Modifier.groupCell(first: Boolean, last: Boolean, radius: Dp, bg: Color, border: Color, borderWidth: Dp): Modifier {
    val shape = RoundedCornerShape(
        topStart = if (first) radius else Dp(0f),
        topEnd = if (first) radius else Dp(0f),
        bottomStart = if (last) radius else Dp(0f),
        bottomEnd = if (last) radius else Dp(0f),
    )
    return this
        .clip(shape)
        .background(bg)
        .drawWithContent {
            drawContent()
            val w = borderWidth.toPx()
            val h2 = w / 2
            val r = radius.toPx()
            val wd = size.width
            val ht = size.height
            val p = Path()
            // 左边：从下往上
            p.moveTo(h2, if (last) ht - r else ht)
            if (first) {
                p.lineTo(h2, r)
                p.arcTo(Rect(h2, h2, 2 * r - h2, 2 * r - h2), 180f, 90f, false)
                p.lineTo(wd - r, h2)
                p.arcTo(Rect(wd - 2 * r + h2, h2, wd - h2, 2 * r - h2), 270f, 90f, false)
            } else {
                p.lineTo(h2, 0f)
                p.moveTo(wd - h2, 0f)
            }
            // 右边：从上往下
            if (last) {
                p.lineTo(wd - h2, ht - r)
                p.arcTo(Rect(wd - 2 * r + h2, ht - 2 * r + h2, wd - h2, ht - h2), 0f, 90f, false)
                p.lineTo(r, ht - h2)
                p.arcTo(Rect(h2, ht - 2 * r + h2, 2 * r - h2, ht - h2), 90f, 90f, false)
            } else {
                p.lineTo(wd - h2, ht)
            }
            drawPath(p, border, style = Stroke(w))
        }
}

/** 把一组行画成一张卡片：每行一个 item，首尾圆角，行间分隔线（左侧缩进 [dividerInset]） */
internal fun <T> LazyListScope.cardGroup(
    list: List<T>,
    key: (T) -> Any,
    dividerInset: Dp? = null,
    row: @Composable (T) -> Unit,
) {
    val n = list.size
    for ((i, x) in list.withIndex()) {
        item(key = key(x), contentType = "groupRow") {
            CardCell(first = i == 0, last = i == n - 1, dividerInset = dividerInset) { row(x) }
        }
    }
}

/** 分组卡片的一格（非 Lazy 场景也能用） */
@Composable
internal fun CardCell(first: Boolean, last: Boolean, dividerInset: Dp? = null, content: @Composable () -> Unit) {
    val c = PaperTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .groupCell(first, last, PaperTheme.radius.lg, c.bgSurface, c.border, PaperTheme.sizes.border),
    ) {
        content()
        if (!last) Box(Modifier.padding(start = dividerInset ?: PaperTheme.spacing.xl)) { PaperDivider(subtle = true) }
    }
}
