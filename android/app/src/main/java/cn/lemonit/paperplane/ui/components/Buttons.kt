package cn.lemonit.paperplane.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme

/** 按钮种类（对照 Mac PPButtonKind） */
enum class ButtonKind {
    /** 强调色实心：页面 / 面板里的主操作，一处只放一个 */
    Primary,

    /** 描边：普通操作 */
    Secondary,

    /** 文字按钮：无底无框，强调色文字（次要操作、行内操作） */
    Text,

    /** 危险操作实心红（删除、强制停止） */
    Danger,
}

/** 按钮大小：常规 44dp（满足触控），小号 32dp（卡片、行内；点击范围自动扩到 48dp） */
enum class ButtonSize { Regular, Small }

/**
 * 文字按钮（可带图标）。
 *
 * `PaperButton(tr("action.run"), onClick = { … }, kind = ButtonKind.Primary, icon = "play")`
 * 撑满宽度用 `modifier = Modifier.fillMaxWidth()`。
 *
 * @param loading 显示转圈（替换图标）并且不响应点击
 */
@Composable
fun PaperButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    size: ButtonSize = ButtonSize.Regular,
    icon: String? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val c = PaperTheme.colors
    val t = PaperTheme.typography
    val s = PaperTheme.sizes
    val sp = PaperTheme.spacing
    val r = PaperTheme.radius
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(if (size == ButtonSize.Regular) r.lg else r.md)
    val (bg, fg, border) = when (kind) {
        ButtonKind.Primary -> Triple(if (pressed) c.accentPressed else c.accent, c.textOnAccent, Color.Transparent)
        ButtonKind.Danger -> Triple(if (pressed) c.dangerHover else c.danger, c.textOnAccent, Color.Transparent)
        ButtonKind.Secondary -> Triple(if (pressed) c.bgPressed else c.buttonSecondaryBg, c.textPrimary, c.buttonSecondaryBorder)
        ButtonKind.Text -> Triple(if (pressed) c.bgPressed else Color.Transparent, c.textLink, Color.Transparent)
    }
    val height = if (size == ButtonSize.Regular) s.controlHeight else s.controlHeightSmall
    val hPad = when {
        kind == ButtonKind.Text -> sp.md
        size == ButtonSize.Regular -> sp.xl
        else -> sp.lg
    }
    val style = if (size == ButtonSize.Regular) t.body.copy(fontWeight = FontWeight.Medium) else t.label
    Row(
        modifier = modifier
            .defaultMinSize(minWidth = height)
            .height(height)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(bg, shape)
            .border(PaperTheme.sizes.border, border, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = hPad),
        horizontalArrangement = Arrangement.spacedBy(sp.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val iconSize = if (size == ButtonSize.Regular) s.icon else s.iconSmall
        CompositionLocalProvider(LocalContentColor provides fg) {
            if (loading) {
                PaperSpinner(size = iconSize, tint = fg)
            } else if (icon != null) {
                PaperIcon(icon, size = iconSize, tint = fg)
            }
            Text(text, style = style, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 只有图标的方形按钮（顶栏、卡片运行区、行尾操作）。
 *
 * `PaperIconButton("rotate-cw", tr("action.restart"), onClick = { … })`
 *
 * @param contentDescription 读屏文字（必填：只有图标的按钮必须能读出来）
 * @param tint 图标颜色，默认 iconPrimary；[active] 时强调色加浅底（开关型按钮的「开」）
 */
@Composable
fun PaperIconButton(
    icon: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: ButtonSize = ButtonSize.Regular,
    tint: Color? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val c = PaperTheme.colors
    val s = PaperTheme.sizes
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(PaperTheme.radius.md)
    val box: Dp = if (size == ButtonSize.Regular) s.iconButton else s.iconButtonSmall
    val iconSize = if (size == ButtonSize.Regular) s.icon else s.iconSmall
    val fg = when {
        !enabled -> c.textDisabled
        tint != null -> tint
        active -> c.accent
        else -> c.iconPrimary
    }
    val bg = when {
        pressed -> c.bgPressed
        active -> c.accentSubtle
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .size(box)
            .clip(shape)
            .background(bg, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) PaperSpinner(size = iconSize, tint = fg) else PaperIcon(icon, size = iconSize, tint = fg)
    }
}

/**
 * 可点击的文本链接（URL、路径）。按下时加下划线。
 *
 * `PaperLink("http://localhost:5173", onClick = { … }, mono = true, icon = "external-link")`
 */
@Composable
fun PaperLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    icon: String? = null,
) {
    val c = PaperTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) PaperIcon(icon, size = PaperTheme.sizes.iconSmall, tint = c.textLink)
        Text(
            text,
            style = (if (mono) PaperTheme.typography.monoSmall else PaperTheme.typography.small)
                .copy(textDecoration = if (pressed) TextDecoration.Underline else null),
            color = c.textLink,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}
