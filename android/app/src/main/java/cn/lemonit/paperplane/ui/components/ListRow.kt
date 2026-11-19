package cn.lemonit.paperplane.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme

/**
 * 列表行：前置图标（或自定义前置内容）、标题、副标题、尾部内容 / 值、箭头；可点击，长按出菜单。
 * 单行最小 48dp，带副标题时随内容变高。放在 [PaperGroup] 里时自动加分隔线。
 *
 * ```
 * PaperListRow(p.name, subtitle = path, leading = { PaperStatusDot(p.status) }, showChevron = true,
 *              onClick = { open(p) }, menu = { cardMenu(p) })
 * PaperListRow(tr("settings.language"), icon = "languages", value = "中文", showChevron = true, onClick = { … })
 * ```
 *
 * @param icon 前置图标名（内置图标）；[leading] 优先
 * @param value 尾部的值文字（次要色），在 [trailing] 之前
 * @param menu 长按菜单（对应桌面右键菜单）；没给 onClick 时点按也弹菜单
 */
@Composable
fun PaperListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: String? = null,
    iconTint: Color? = null,
    leading: (@Composable () -> Unit)? = null,
    value: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    showChevron: Boolean = false,
    selected: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    subtitleMono: Boolean = false,
    subtitleMaxLines: Int = 1,
    onClick: (() -> Unit)? = null,
    menu: (() -> List<MenuEntry>)? = null,
) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val t = PaperTheme.typography
    PaperLongPressArea(
        onClick = onClick,
        menu = menu,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) c.bgSelected else Color.Transparent),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = PaperTheme.sizes.rowHeight)
                .alpha(if (enabled) 1f else 0.5f)
                .padding(horizontal = sp.xl, vertical = sp.md),
            horizontalArrangement = Arrangement.spacedBy(sp.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Box(Modifier.widthIn(min = PaperTheme.sizes.icon), contentAlignment = Alignment.Center) { leading() }
            } else if (icon != null) {
                PaperIcon(icon, tint = iconTint ?: if (destructive) c.danger else c.iconPrimary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = t.body,
                    color = if (destructive) c.danger else c.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        subtitle,
                        style = if (subtitleMono) t.monoSmall else t.small,
                        color = c.textSecondary,
                        maxLines = subtitleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (value != null) {
                Text(
                    value,
                    style = t.body,
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
            }
            if (trailing != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalAlignment = Alignment.CenterVertically, content = trailing)
            }
            if (showChevron) {
                PaperIcon("chevron-right", size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
            }
        }
    }
}

/**
 * 带开关的行（设置项）。整行可点切换；说明放 [info] 的 i 气泡里。
 *
 * `PaperToggleRow(tr("settings.autoDeps"), checked = s.autoDeps, onCheckedChange = { update(it) }, info = tr("settings.autoDeps.info"))`
 */
@Composable
fun PaperToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: String? = null,
    info: String? = null,
    enabled: Boolean = true,
) {
    PaperListRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        enabled = enabled,
        modifier = modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
        onClick = { onCheckedChange(!checked) },
        trailing = {
            if (info != null) PaperInfoButton(info)
            PaperSwitch(checked, onCheckedChange = onCheckedChange, enabled = enabled)
        },
    )
}
