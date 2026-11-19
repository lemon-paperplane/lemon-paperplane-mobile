package cn.lemonit.paperplane.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme

/** 选项（对照 Mac PPDropdown.Option）：值、标题、可选图标（内置图标名）、右侧说明 */
data class SelectOption<T>(val value: T, val title: String, val icon: String? = null, val detail: String? = null)

/**
 * 选择器：外观像输入框（当前值 + 上下箭头），点开从底部弹出单选列表（对照 Mac PPDropdown）。
 * 选项多于 12 个时列表顶部带筛选框。
 *
 * ```
 * PaperSelect(
 *     options = listOf(SelectOption("system", tr("settings.followSystem")), SelectOption("zh-Hans", "中文"), SelectOption("en", "English")),
 *     selected = lang, onSelect = { I18n.setSetting(it) }, title = tr("settings.language"),
 * )
 * ```
 *
 * @param title 弹出面板的标题（一般和字段名相同）
 */
@Composable
fun <T> PaperSelect(
    options: List<SelectOption<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    label: String? = null,
    placeholder: String = tr("common.select"),
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Regular,
) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.value == selected }
    val shape = RoundedCornerShape(if (size == ButtonSize.Regular) PaperTheme.radius.lg else PaperTheme.radius.md)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(sp.xs)) {
        if (label != null) Text(label, style = PaperTheme.typography.label, color = c.textSecondary)
        Row(
            Modifier
                .defaultMinSize(minHeight = if (size == ButtonSize.Regular) PaperTheme.sizes.controlHeight else PaperTheme.sizes.controlHeightSmall)
                .alpha(if (enabled) 1f else 0.6f)
                .background(if (enabled) c.bgInput else c.bgInputDisabled, shape)
                .border(PaperTheme.sizes.border, if (open) c.borderFocus else c.borderStrong, shape)
                .clickable(enabled = enabled, role = Role.DropdownList) { open = true }
                .padding(horizontal = if (size == ButtonSize.Regular) sp.lg else sp.md),
            horizontalArrangement = Arrangement.spacedBy(sp.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (current?.icon != null) PaperIcon(current.icon, size = PaperTheme.sizes.iconSmall)
            Text(
                current?.title ?: placeholder,
                style = if (size == ButtonSize.Regular) PaperTheme.typography.body else PaperTheme.typography.small,
                color = if (current == null) c.textTertiary else c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f),
            )
            PaperIcon("chevrons-up-down", size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
        }
    }
    if (open) {
        PaperSelectSheet(
            options = options,
            selected = selected,
            title = title ?: label,
            onDismiss = { open = false },
            onSelect = onSelect,
        )
    }
}

/**
 * 底部弹出的单选列表（选择器的弹出部分；也可以单独用，如「移到工作区」）。点一项即选中并收起。
 *
 * `if (pick) PaperSelectSheet(options, selected = ws, title = tr("card.moveTo"), onDismiss = { pick = false }) { move(it) }`
 */
@Composable
fun <T> PaperSelectSheet(
    options: List<SelectOption<T>>,
    selected: T?,
    onDismiss: () -> Unit,
    title: String? = null,
    onSelect: (T) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf("") }
    PaperBottomSheet(onDismiss = onDismiss, title = title, scrollable = false, edgeToEdge = true) { dismiss ->
        if (options.size > 12) {
            PaperSearchField(filter, { filter = it }, placeholder = tr("common.filter"), modifier = Modifier.padding(horizontal = PaperTheme.spacing.xl, vertical = PaperTheme.spacing.xs))
        }
        val shown = if (filter.isBlank()) options else options.filter { it.title.contains(filter.trim(), ignoreCase = true) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            items(shown) { o ->
                SelectRow(o, o.value == selected) {
                    onSelect(o.value)
                    dismiss()
                }
            }
        }
    }
}

/** 选项行（选择列表、陈列页） */
@Composable
fun <T> SelectRow(option: SelectOption<T>, selected: Boolean, onClick: () -> Unit) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = PaperTheme.sizes.rowHeight)
            .background(if (selected) c.accentSubtle else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = sp.xl),
        horizontalArrangement = Arrangement.spacedBy(sp.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (option.icon != null) PaperIcon(option.icon, tint = c.iconPrimary)
        Text(
            option.title,
            style = PaperTheme.typography.body,
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (option.detail != null) Text(option.detail, style = PaperTheme.typography.small, color = c.textTertiary, maxLines = 1)
        Box(Modifier.size(PaperTheme.sizes.icon), contentAlignment = Alignment.Center) {
            if (selected) PaperIcon("check", size = PaperTheme.sizes.iconSmall, tint = c.accent)
        }
    }
}
