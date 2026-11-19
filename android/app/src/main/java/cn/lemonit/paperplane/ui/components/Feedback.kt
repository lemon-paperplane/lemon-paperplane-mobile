package cn.lemonit.paperplane.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone

/**
 * 转圈（loader-circle 图标旋转，和桌面一致）。
 *
 * `PaperSpinner()`、`PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = PaperTheme.colors.accent)`
 */
@Composable
fun PaperSpinner(modifier: Modifier = Modifier, size: Dp = PaperTheme.sizes.icon, tint: Color = LocalContentColor.current) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    PaperIcon("loader-circle", modifier = modifier.rotate(angle), size = size, tint = tint)
}

/**
 * 整页加载：居中转圈，可带一句话。
 *
 * `PaperLoadingPage()`、`PaperLoadingPage(text = tr("connection.connecting"))`
 */
@Composable
fun PaperLoadingPage(modifier: Modifier = Modifier, text: String? = null) {
    Column(
        modifier = modifier.fillMaxSize().padding(PaperTheme.spacing.huge),
        verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.lg, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PaperSpinner(size = PaperTheme.sizes.iconLarge, tint = PaperTheme.colors.iconSecondary)
        Text(
            text ?: tr("common.loading"),
            style = PaperTheme.typography.body,
            color = PaperTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 进度条。progress 为 0..1；null 为不确定进度（来回滑动）。
 *
 * `PaperProgressBar(0.4f)`、`PaperProgressBar(null)`、`PaperProgressBar(1f, tone = Tone.Success)`
 */
@Composable
fun PaperProgressBar(progress: Float?, modifier: Modifier = Modifier, tone: Tone? = null, height: Dp = 4.dp) {
    val c = PaperTheme.colors
    val track = c.neutralSubtle
    val fill = if (tone == null) c.accent else c.tone(tone).fg
    val sem = Modifier.semantics {
        progressBarRangeInfo = if (progress == null) ProgressBarRangeInfo.Indeterminate else ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
    }
    if (progress == null) {
        val t = rememberInfiniteTransition(label = "progress")
        val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "phase")
        Canvas(modifier.fillMaxWidth().height(height).then(sem)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            val w = size.width * 0.3f
            drawRoundRect(fill, topLeft = Offset(phase * (size.width - w), 0f), size = Size(w, size.height), cornerRadius = r)
        }
    } else {
        val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(PaperTheme.motion.normal), label = "p")
        Canvas(modifier.fillMaxWidth().height(height).then(sem)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            if (p > 0f) drawRoundRect(fill, size = Size(size.width * p, size.height), cornerRadius = r)
        }
    }
}

/**
 * 空状态：一个图标 + 一句话 + 可选一个主按钮（不加解释性小字）。
 *
 * `PaperEmptyState("folder-open", tr("…"), actionText = tr("import.folder"), actionIcon = "plus") { … }`
 */
@Composable
fun PaperEmptyState(
    icon: String,
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    actionIcon: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(PaperTheme.spacing.huge),
        verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.lg, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PaperIcon(icon, size = PaperTheme.sizes.iconEmpty, tint = PaperTheme.colors.iconSecondary)
        Text(title, style = PaperTheme.typography.body, color = PaperTheme.colors.textSecondary, textAlign = TextAlign.Center)
        if (actionText != null && onAction != null) {
            PaperButton(actionText, onClick = onAction, kind = ButtonKind.Primary, icon = actionIcon)
        }
    }
}

/**
 * 页内提示条（错误、警告、说明；对照 Mac PPAlert）。对话框里的出错信息也用它。
 *
 * `PaperAlert(Tone.Danger, tr("common.failed"), message = e.message)`
 */
@Composable
fun PaperAlert(
    tone: Tone,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = PaperTheme.colors.tone(tone)
    val shape = RoundedCornerShape(PaperTheme.radius.lg)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.bg, shape)
            .border(PaperTheme.sizes.border, c.border, shape)
            .padding(PaperTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        PaperIcon(toneIcon(tone), size = PaperTheme.sizes.icon, tint = c.fg)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xxs)) {
            Text(title, style = PaperTheme.typography.bodyStrong, color = PaperTheme.colors.textPrimary)
            if (!message.isNullOrEmpty()) {
                Text(message, style = PaperTheme.typography.small, color = PaperTheme.colors.textSecondary)
            }
        }
        if (actions != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** 语气对应的图标（提示条、Toast） */
internal fun toneIcon(tone: Tone): String = when (tone) {
    Tone.Danger -> "circle-alert"
    Tone.Warning -> "triangle-alert"
    Tone.Success -> "circle-check"
    else -> "info"
}
