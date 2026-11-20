package cn.lemonit.paperplane.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperBottomSheet
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperInfoButton
import cn.lemonit.paperplane.ui.components.PaperTextField
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 设置行：标题（紧跟 i 说明）… 值 / 尾部控件 / 箭头。放在 PaperGroup 里，和 PaperListRow 等高。
 * 有 onClick 时整行可点（打开选择面板、数字输入面板）。
 */
@Composable
internal fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    info: String? = null,
    value: String? = null,
    valueTone: Tone? = null,
    showChevron: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = PaperTheme.sizes.rowHeight)
            .padding(start = sp.xl, end = if (trailing != null || showChevron) sp.lg else sp.xl, top = sp.xs, bottom = sp.xs),
        horizontalArrangement = Arrangement.spacedBy(sp.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标后的间距和 PaperListRow 一致（文字和分组分隔线对齐）
        if (icon != null) PaperIcon(icon, tint = c.iconPrimary, modifier = Modifier.padding(end = sp.lg - sp.sm))
        // 标题占满剩余宽度（i 紧跟标题），值和尾部控件靠右
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = PaperTheme.typography.body, color = if (enabled) c.textPrimary else c.textDisabled, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (info != null) PaperInfoButton(info)
        }
        if (value != null) {
            Text(
                value,
                style = PaperTheme.typography.body,
                color = valueTone?.let { c.tone(it).fg } ?: c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
        }
        trailing?.invoke(this)
        if (showChevron) PaperIcon("chevron-right", size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
    }
}

/**
 * 输入面板（数字 / 文字）：和 PaperInputSheet 类似，但可以清空（文字）、可以限定数字范围。
 * onSubmit 抛错时显示在面板里。
 */
@Composable
internal fun ValueInputSheet(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    number: Boolean = false,
    range: IntRange? = null,
    suffix: String? = null,
    placeholder: String? = null,
    allowEmpty: Boolean = false,
    onSubmit: suspend (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val v = text.trim()
    val invalid: String? = when {
        number && v.isNotEmpty() && (v.toIntOrNull() == null || (range != null && v.toInt() !in range)) ->
            if (range != null) tr("mobile.settings.range", range.first, range.last) else ""
        else -> null
    }
    val canSubmit = invalid == null && (allowEmpty || v.isNotEmpty()) && !busy
    PaperBottomSheet(onDismiss = onDismiss, title = title, showClose = false) { dismiss ->
        fun submit() {
            if (!canSubmit) return
            busy = true
            error = null
            scope.launch {
                try {
                    onSubmit(v)
                    dismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: tr("common.failed")
                } finally {
                    busy = false
                }
            }
        }
        PaperTextField(
            text,
            { text = it },
            placeholder = placeholder,
            mono = number,
            suffix = suffix,
            clearable = !number,
            keyboardType = if (number) KeyboardType.Number else KeyboardType.Text,
            error = invalid,
            focusRequester = focus,
            onSubmit = { submit() },
        )
        error?.let { PaperAlert(Tone.Danger, it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md)) {
            PaperButton(tr("common.cancel"), onClick = { dismiss() }, modifier = Modifier.weight(1f))
            PaperButton(tr("common.save"), onClick = { submit() }, kind = ButtonKind.Primary, loading = busy, enabled = canSubmit, modifier = Modifier.weight(1f))
        }
    }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { focus.requestFocus() }
    }
}

/** 日期显示（本机时区） */
internal object DateText {
    private val short = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val full = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    fun short(i: Instant?): String = if (i == null || i.epochSecond <= 0) "-" else short.format(i.atZone(ZoneId.systemDefault()))

    fun full(i: Instant?): String = if (i == null || i.epochSecond <= 0) "-" else full.format(i.atZone(ZoneId.systemDefault()))
}
