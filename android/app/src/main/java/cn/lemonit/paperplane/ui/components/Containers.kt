package cn.lemonit.paperplane.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.ui.theme.PaperTheme

/**
 * 卡片容器：浅底（bgSurface）+ 描边 + 圆角（对照 Mac PPCard / ProjectCard）。
 * 给了 onClick / menu 就可点、可长按出菜单；selected 时强调色描边。
 *
 * `PaperCard(onClick = { open(p) }, menu = { cardMenu(p) }) { Text(p.name, style = PaperTheme.typography.title) … }`
 */
@Composable
fun PaperCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    menu: (() -> List<MenuEntry>)? = null,
    selected: Boolean = false,
    padding: Dp = PaperTheme.spacing.lg,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = PaperTheme.colors
    val shape = RoundedCornerShape(PaperTheme.radius.lg)
    PaperLongPressArea(
        onClick = onClick,
        menu = menu,
        modifier = modifier
            .background(c.bgSurface, shape)
            .border(PaperTheme.sizes.border, if (selected) c.accent else c.border, shape)
            .clip(shape),
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md), content = content)
    }
}

/**
 * 区块标题：标题 + 可选 i 说明 + 右侧附加内容（按钮、计数）。分组、页面里的小节都用它。
 *
 * `PaperSectionHeader(tr("settings.mirrors"), info = tr("settings.mirrors.info")) { PaperButton(…, size = ButtonSize.Small) }`
 */
@Composable
fun PaperSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    info: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = PaperTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = PaperTheme.typography.bodyStrong,
                color = PaperTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (info != null) PaperInfoButton(info)
        }
        if (trailing != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}

/**
 * 分组（设置页那种）：组标题（可带 i 说明）+ 一张卡片，里面的行之间自动加分隔线。
 * 内容一般是若干 [PaperListRow] / [PaperToggleRow]；高度为 0 的子项（如 if 没成立）不算。
 * 说明文字不铺在页面上，放进标题旁的 i 气泡（[info]）。
 *
 * ```
 * PaperGroup(tr("settings.behavior"), info = tr("settings.autoDeps.info")) {
 *     PaperToggleRow(tr("settings.autoDeps"), checked, onCheckedChange = { … })
 *     PaperListRow(tr("settings.logLines"), value = "5000", showChevron = true, onClick = { … })
 * }
 * ```
 *
 * @param dividerInset 分隔线左侧缩进；行带前置图标时传 [PaperGroupDefaults.IconRowInset] 让线和文字对齐
 */
@Composable
fun PaperGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    info: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    dividerInset: Dp = PaperTheme.spacing.xl,
    content: @Composable () -> Unit,
) {
    val c = PaperTheme.colors
    val shape = RoundedCornerShape(PaperTheme.radius.lg)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm)) {
        if (title != null) PaperSectionHeader(title, info = info, trailing = trailing)
        val dividerColor = c.borderSubtle
        val border = PaperTheme.sizes.border
        Box(
            Modifier
                .fillMaxWidth()
                .background(c.bgSurface, shape)
                .border(border, c.border, shape)
                .clip(shape),
        ) {
            SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
                val loose = constraints.copy(minHeight = 0)
                val items = subcompose("content", content).map { it.measure(loose) }.filter { it.height > 0 }
                val insetPx = dividerInset.roundToPx()
                val lineConstraints = Constraints.fixed(
                    (constraints.maxWidth - insetPx).coerceAtLeast(0),
                    border.roundToPx().coerceAtLeast(1),
                )
                val lines = subcompose("dividers") {
                    repeat((items.size - 1).coerceAtLeast(0)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth().background(dividerColor))
                    }
                }.map { it.measure(lineConstraints) }
                val width = constraints.maxWidth
                val height = items.sumOf { it.height }
                layout(width, height) {
                    var y = 0
                    items.forEachIndexed { i, p ->
                        p.place(0, y)
                        y += p.height
                        if (i < lines.size) lines[i].place(insetPx, y - lines[i].height)
                    }
                }
            }
        }
    }
}

object PaperGroupDefaults {
    /** 行带前置图标时分隔线的缩进：左边距 16 + 图标 20 + 间距 12 */
    val IconRowInset: Dp = 48.dp
}

/**
 * 分隔线。subtle 用更淡的颜色（卡片内部）。
 *
 * `PaperDivider()`、`PaperDivider(subtle = true)`、`PaperDivider(vertical = true, modifier = Modifier.height(20.dp))`
 */
@Composable
fun PaperDivider(modifier: Modifier = Modifier, vertical: Boolean = false, subtle: Boolean = false) {
    val color = if (subtle) PaperTheme.colors.borderSubtle else PaperTheme.colors.border
    val w = PaperTheme.sizes.border
    if (vertical) {
        Box(modifier.width(w).fillMaxHeight().background(color))
    } else {
        Box(modifier.fillMaxWidth().height(w).background(color))
    }
}
