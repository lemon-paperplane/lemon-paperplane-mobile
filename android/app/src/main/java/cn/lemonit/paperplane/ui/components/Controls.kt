package cn.lemonit.paperplane.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme

/**
 * 开关（自绘，三端一致：开 = 强调色，关 = switchOff）。
 *
 * `PaperSwitch(checked, onCheckedChange = { checked = it })`
 */
@Composable
fun PaperSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = PaperTheme.colors
    val ms = PaperTheme.motion.fast
    val track by animateColorAsState(if (checked) c.accent else c.switchOff, tween(ms), label = "track")
    val x by animateDpAsState(if (checked) 18.dp else 2.dp, tween(ms), label = "thumb")
    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier
    }
    Box(
        modifier
            .then(toggle)
            .alpha(if (enabled) 1f else 0.5f)
            .size(width = 40.dp, height = 24.dp)
            .background(track, CircleShape),
    ) {
        Box(
            Modifier
                .offset(x = x, y = 2.dp)
                .size(20.dp)
                .shadow(1.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

/**
 * 复选框，可带文字（整行可点，至少 44dp 高）。
 *
 * `PaperCheckbox(checked, onCheckedChange = { checked = it }, label = tr("deps.cleanInstall"))`
 */
@Composable
fun PaperCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
) {
    val c = PaperTheme.colors
    val shape = RoundedCornerShape(PaperTheme.radius.sm)
    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
    } else {
        Modifier
    }
    Row(
        modifier
            .then(toggle)
            .then(if (label != null) Modifier.defaultMinSize(minHeight = PaperTheme.sizes.touchTarget) else Modifier)
            .alpha(if (enabled) 1f else 0.5f),
        horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .background(if (checked) c.accent else c.bgInput, shape)
                .border(PaperTheme.sizes.border, if (checked) c.accent else c.borderStrong, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) PaperIcon("check", size = 16.dp, tint = c.textOnAccent)
        }
        if (label != null) Text(label, style = PaperTheme.typography.body, color = c.textPrimary)
    }
}

/**
 * 单选框（一组里选一个）。
 *
 * `PaperRadio(selected = mode == "auto", onClick = { mode = "auto" }, label = tr("settings.mirror.auto"))`
 */
@Composable
fun PaperRadio(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, label: String? = null, enabled: Boolean = true) {
    val c = PaperTheme.colors
    val ring by animateDpAsState(if (selected) 6.dp else 1.dp, tween(PaperTheme.motion.fast), label = "ring")
    Row(
        modifier
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .then(if (label != null) Modifier.defaultMinSize(minHeight = PaperTheme.sizes.touchTarget) else Modifier)
            .alpha(if (enabled) 1f else 0.5f),
        horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .background(c.bgInput, CircleShape)
                .border(ring, if (selected) c.accent else c.borderStrong, CircleShape),
        )
        if (label != null) Text(label, style = PaperTheme.typography.body, color = c.textPrimary)
    }
}

/**
 * 分段控件（2–4 个互斥选项：主题、镜像区域、视图切换）。fill 时各段等宽撑满。
 *
 * `PaperSegmented(listOf("system" to tr("settings.followSystem"), "light" to tr("settings.light"), "dark" to tr("settings.dark")), selected = theme, onSelect = { theme = it })`
 */
@Composable
fun <T> PaperSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    enabled: Boolean = true,
) {
    val c = PaperTheme.colors
    val outer = RoundedCornerShape(PaperTheme.radius.md)
    val inner = RoundedCornerShape(PaperTheme.radius.sm)
    Row(
        modifier
            .height(PaperTheme.sizes.controlHeightSmall + 4.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .background(c.neutralSubtle, outer)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((value, title) in options) {
            val on = value == selected
            val bg by animateColorAsState(if (on) c.bgSurface else Color.Transparent, tween(PaperTheme.motion.fast), label = "seg")
            Box(
                (if (fill) Modifier.weight(1f) else Modifier)
                    .fillMaxHeight()
                    .then(if (on && !c.isDark) Modifier.shadow(1.dp, inner) else Modifier)
                    .background(bg, inner)
                    .selectable(selected = on, enabled = enabled, role = Role.Tab, onClick = { onSelect(value) })
                    .padding(horizontal = PaperTheme.spacing.lg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title,
                    style = PaperTheme.typography.label,
                    color = if (on) c.textPrimary else c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
