package cn.lemonit.paperplane.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import kotlin.math.max

/**
 * i 说明按钮：点开在旁边弹出说明气泡，点外面或返回键关闭（界面上不铺解释性小字，说明都放这里）。
 * 图标视觉 18dp，点击范围 44dp。
 *
 * `PaperInfoButton(tr("settings.mirrors.info"))`
 */
@Composable
fun PaperInfoButton(text: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val c = PaperTheme.colors
    val label = tr("common.info")
    Box(
        modifier
            .size(PaperTheme.sizes.touchTarget - 12.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
            ) { open = !open }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        PaperIcon("info", size = PaperTheme.sizes.iconSmall, tint = if (open) c.accent else c.iconSecondary)
        InfoPopup(open, text) { open = false }
    }
}

/** 说明气泡本体（不弹出）：陈列页、截图测试用 */
@Composable
fun PaperInfoBubble(text: String, modifier: Modifier = Modifier) {
    val c = PaperTheme.colors
    val shape = RoundedCornerShape(PaperTheme.radius.lg)
    Box(
        modifier
            .widthIn(max = 300.dp)
            .paperShadow(ShadowKind.Popup, shape)
            .background(c.bgElevated, shape)
            .border(PaperTheme.sizes.border, c.border, shape)
            .padding(PaperTheme.spacing.lg),
    ) {
        SelectionContainer {
            Text(text, style = PaperTheme.typography.small, color = c.textPrimary)
        }
    }
}

@Composable
private fun InfoPopup(open: Boolean, text: String, onDismiss: () -> Unit) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = open
    if (!state.currentState && !state.targetState) return
    val density = LocalDensity.current
    val provider = remember(density) {
        BubblePositionProvider(with(density) { 12.dp.roundToPx() }, with(density) { 2.dp.roundToPx() })
    }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(state, enter = fadeIn(tween(PaperTheme.motion.fast)), exit = fadeOut(tween(PaperTheme.motion.fast))) {
            // 外面留 8dp 给阴影
            Box(Modifier.padding(8.dp)) { PaperInfoBubble(text) }
        }
    }
}

/** 气泡放在 i 图标下方（放不下翻到上方），水平以图标为中心，左右不出屏幕 */
private class BubblePositionProvider(private val margin: Int, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        var x = anchorBounds.center.x - w / 2
        var y = anchorBounds.bottom + gap
        if (y + h > windowSize.height - margin) y = anchorBounds.top - gap - h
        x = x.coerceIn(0, max(0, windowSize.width - w))
        y = y.coerceIn(0, max(0, windowSize.height - h))
        return IntOffset(x, y)
    }
}
