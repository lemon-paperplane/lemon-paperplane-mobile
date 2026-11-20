package cn.lemonit.paperplane.features.forms

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.kit.model.Option
import cn.lemonit.paperplane.kit.util.IconSource
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperCheckbox
import cn.lemonit.paperplane.ui.components.PaperCountBadge
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperInfoButton
import cn.lemonit.paperplane.ui.components.PaperTextField
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.RefIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import java.util.concurrent.atomic.AtomicLong

// 功能 2 各页面共用的小部件：标签条、键值列表、表单里的键值 / 列表 / 多选编辑器、字段标题。

/** 标签条的一项 */
data class StripTab<T>(
    val value: T,
    val title: String,
    val icon: IconSource? = null,
    /** 数量（0 或 null 不显示） */
    val badge: Int? = null,
    val badgeTone: Tone = Tone.Neutral,
)

/**
 * 横向可滚动的标签条（工程详情的标签、插件界面的 tabs 节点）：图标 + 标题 + 计数，选中项下方强调色横线；
 * 选中项不在可见范围时自动滚进来。高度 44dp。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> PaperTabStrip(tabs: List<StripTab<T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val requesters = remember(tabs.size) { List(tabs.size) { BringIntoViewRequester() } }
    val index = tabs.indexOfFirst { it.value == selected }
    LaunchedEffect(index, tabs.size) {
        if (index >= 0) runCatching { requesters[index].bringIntoView() }
    }
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = sp.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { i, t ->
            val on = t.value == selected
            val accent = c.accent
            Row(
                Modifier
                    .bringIntoViewRequester(requesters[i])
                    .height(PaperTheme.sizes.touchTarget)
                    .clickable(role = Role.Tab) { onSelect(t.value) }
                    .drawBehind {
                        if (on) {
                            val h = 2.dp.toPx()
                            val inset = 6.dp.toPx()
                            drawRect(accent, topLeft = Offset(inset, size.height - h), size = Size(size.width - inset * 2, h))
                        }
                    }
                    .padding(horizontal = sp.lg),
                horizontalArrangement = Arrangement.spacedBy(sp.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (t.icon != null) {
                    RefIcon(t.icon, size = PaperTheme.sizes.iconSmall, tint = if (on) c.accent else c.iconSecondary)
                }
                Text(
                    t.title,
                    style = if (on) PaperTheme.typography.bodyStrong else PaperTheme.typography.body,
                    color = if (on) c.textPrimary else c.textSecondary,
                    maxLines = 1,
                )
                if ((t.badge ?: 0) > 0) PaperCountBadge(t.badge ?: 0, tone = t.badgeTone)
            }
        }
    }
}

/** 键值列表的一行 */
data class KvItem(val key: String, val value: String, val mono: Boolean = false, val tone: String? = null, val href: String? = null)

/** 键值列表（模块信息、插件 kv 节点、关于）：键在左（次要色、定宽），值在右可选中复制，长值换行 */
@Composable
fun KeyValueList(rows: List<KvItem>, modifier: Modifier = Modifier, keyWidth: androidx.compose.ui.unit.Dp = 112.dp) {
    val c = PaperTheme.colors
    val t = PaperTheme.typography
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm)) {
        for (r in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md)) {
                Text(r.key, style = t.small, color = c.textSecondary, modifier = Modifier.width(keyWidth))
                val color = when {
                    r.href != null -> c.textLink
                    r.tone != null -> c.tone(Tone.of(r.tone)).fg
                    else -> c.textPrimary
                }
                val style = if (r.mono) t.monoSmall else t.small
                if (r.href != null) {
                    Text(
                        r.value,
                        style = style,
                        color = color,
                        modifier = Modifier.weight(1f).clickable(role = Role.Button) { Actions.openExternal(r.href) },
                    )
                } else {
                    SelectionContainer(Modifier.weight(1f)) { Text(r.value, style = style, color = color) }
                }
            }
        }
    }
}

/** 字段标题：名称 + 必填星号 + i 说明；出错时标红 */
@Composable
fun FieldLabel(title: String, modifier: Modifier = Modifier, required: Boolean = false, info: String? = null, invalid: Boolean = false) {
    val c = PaperTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = PaperTheme.typography.label,
            color = if (invalid) c.danger else c.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (required) Text("*", style = PaperTheme.typography.label, color = c.danger)
        if (!info.isNullOrEmpty()) PaperInfoButton(info)
    }
}

private val rowIds = AtomicLong()

private class KvRow(val id: Long, val key: String, val value: String)

/**
 * 键值表编辑（环境变量、keyvalue 字段）：每行「键 | 值 | 删除」，底部「添加变量」。
 * 行在组件内部维护（空键的行也保留，方便先填值）；变化时回调去掉首尾空白的键。[resetKey] 变化时按 [pairs] 重建。
 */
@Composable
fun KeyValueEditor(
    pairs: List<Pair<String, String>>,
    onChange: (List<Pair<String, String>>) -> Unit,
    modifier: Modifier = Modifier,
    keyPlaceholder: String = "KEY",
    valuePlaceholder: String = "value",
    enabled: Boolean = true,
    resetKey: Any? = Unit,
) {
    val rows = remember(resetKey) { mutableStateListOf<KvRow>().apply { addAll(pairs.map { KvRow(rowIds.incrementAndGet(), it.first, it.second) }) } }
    fun emit() = onChange(rows.map { it.key.trim() to it.value })
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm)) {
        rows.forEachIndexed { i, r ->
            androidx.compose.runtime.key(r.id) {
                Row(horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    PaperTextField(
                        r.key,
                        { v ->
                            rows[i] = KvRow(r.id, v, rows[i].value)
                            emit()
                        },
                        placeholder = keyPlaceholder,
                        mono = true,
                        enabled = enabled,
                        modifier = Modifier.weight(0.42f),
                    )
                    PaperTextField(
                        r.value,
                        { v ->
                            rows[i] = KvRow(r.id, rows[i].key, v)
                            emit()
                        },
                        placeholder = valuePlaceholder,
                        mono = true,
                        enabled = enabled,
                        modifier = Modifier.weight(0.58f),
                    )
                    PaperIconButton("x", tr("common.remove"), onClick = {
                        rows.removeAt(i)
                        emit()
                    }, size = ButtonSize.Small, tint = PaperTheme.colors.iconSecondary, enabled = enabled)
                }
            }
        }
        PaperButton(tr("form.addRow"), onClick = { rows.add(KvRow(rowIds.incrementAndGet(), "", "")) }, kind = ButtonKind.Text, size = ButtonSize.Small, icon = "plus", enabled = enabled)
    }
}

private class ListRow(val id: Long, val text: String)

/** 字符串列表编辑（list 字段：JVM 参数、程序参数等）：每行一项，空行提交时去掉 */
@Composable
fun StringListEditor(
    items: List<String>,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    resetKey: Any? = Unit,
) {
    val rows = remember(resetKey) { mutableStateListOf<ListRow>().apply { addAll(items.map { ListRow(rowIds.incrementAndGet(), it) }) } }
    fun emit() = onChange(rows.map { it.text }.filter { it.isNotEmpty() })
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm)) {
        rows.forEachIndexed { i, r ->
            androidx.compose.runtime.key(r.id) {
                Row(horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    PaperTextField(
                        r.text,
                        { v ->
                            rows[i] = ListRow(r.id, v)
                            emit()
                        },
                        placeholder = placeholder,
                        mono = true,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                    PaperIconButton("x", tr("common.remove"), onClick = {
                        rows.removeAt(i)
                        emit()
                    }, size = ButtonSize.Small, tint = PaperTheme.colors.iconSecondary, enabled = enabled)
                }
            }
        }
        PaperButton(tr("form.addItem"), onClick = { rows.add(ListRow(rowIds.incrementAndGet(), "")) }, kind = ButtonKind.Text, size = ButtonSize.Small, icon = "plus", enabled = enabled)
    }
}

/** 多选：带描边的复选框列表；没有选项时退化成字符串列表 */
@Composable
fun MultiSelectEditor(options: List<Option>, selection: List<String>, onChange: (List<String>) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    if (options.isEmpty()) {
        StringListEditor(selection, onChange, modifier, enabled = enabled)
        return
    }
    val c = PaperTheme.colors
    val shape = RoundedCornerShape(PaperTheme.radius.lg)
    Column(
        modifier
            .fillMaxWidth()
            .background(c.bgInput, shape)
            .border(PaperTheme.sizes.border, c.borderStrong, shape)
            .padding(horizontal = PaperTheme.spacing.lg, vertical = PaperTheme.spacing.xs),
    ) {
        for (o in options) {
            val on = o.value in selection
            PaperCheckbox(
                on,
                onCheckedChange = { v -> onChange(if (v) selection + o.value else selection.filter { it != o.value }) },
                label = o.displayLabel,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 页内小节之间的标题（灰色小字，分组更大的层级用 PaperGroup 的标题） */
@Composable
fun SectionCaption(text: String, modifier: Modifier = Modifier, info: String? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = PaperTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = PaperTheme.typography.label, color = PaperTheme.colors.textTertiary, modifier = Modifier.weight(1f, fill = false))
        if (info != null) PaperInfoButton(info)
    }
}
