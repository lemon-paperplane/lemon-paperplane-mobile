package cn.lemonit.paperplane.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.RunStates
import cn.lemonit.paperplane.ui.theme.Tone
import cn.lemonit.paperplane.ui.theme.runState

/**
 * 徽标 / 标签（语气色浅底 + 描边）。技术标签（facet）用默认的 Neutral。
 *
 * `PaperBadge("Vite")`、`PaperBadge(tr("deps.outdated"), tone = Tone.Warning)`、`PaperBadge("main", icon = "git-branch", mono = true)`
 */
@Composable
fun PaperBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    icon: String? = null,
    mono: Boolean = false,
) {
    val c = PaperTheme.colors.tone(tone)
    val shape = RoundedCornerShape(PaperTheme.radius.sm)
    Row(
        modifier = modifier
            .height(22.dp)
            .background(c.bg, shape)
            .border(PaperTheme.sizes.border, c.border.copy(alpha = 0.6f), shape)
            .padding(horizontal = PaperTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) PaperIcon(icon, size = 14.dp, tint = c.fg)
        Text(
            text,
            style = if (mono) PaperTheme.typography.monoSmall else PaperTheme.typography.caption,
            color = c.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 数量小圆块（标签栏、分组标题旁的计数）。count 为 0 时调用方自己决定是否显示。
 *
 * `PaperCountBadge(3)`、`PaperCountBadge(2, tone = Tone.Danger)`
 */
@Composable
fun PaperCountBadge(count: Int, modifier: Modifier = Modifier, tone: Tone = Tone.Neutral) {
    val c = PaperTheme.colors.tone(tone)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .background(c.bg, CircleShape)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), style = PaperTheme.typography.caption, color = c.fg, maxLines = 1)
    }
}

/**
 * 状态圆点（运行状态、工程状态）；准备 / 启动 / 停止中呼吸闪烁。状态名见 [RunStates]。
 *
 * `PaperStatusDot(run.state)`
 */
@Composable
fun PaperStatusDot(state: String, modifier: Modifier = Modifier, size: Dp = PaperTheme.sizes.statusDot) {
    val color = PaperTheme.colors.runState(state)
    val busy = RunStates.isBusy(state)
    val a = if (busy) {
        val tr = rememberInfiniteTransition(label = "pulse")
        tr.animateFloat(1f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha").value
    } else {
        1f
    }
    val title = RunStates.title(state)
    Box(
        modifier
            .size(size)
            .alpha(a)
            .background(color, CircleShape)
            .semantics { contentDescription = title },
    )
}
