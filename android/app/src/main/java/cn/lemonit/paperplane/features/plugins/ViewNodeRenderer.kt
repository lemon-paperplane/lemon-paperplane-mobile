package cn.lemonit.paperplane.features.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.features.forms.FormFields
import cn.lemonit.paperplane.features.forms.FormValues
import cn.lemonit.paperplane.features.forms.KeyValueList
import cn.lemonit.paperplane.features.forms.KvItem
import cn.lemonit.paperplane.features.forms.PaperTabStrip
import cn.lemonit.paperplane.features.forms.StripTab
import cn.lemonit.paperplane.kit.model.ViewNode
import cn.lemonit.paperplane.kit.model.ViewTableRow
import cn.lemonit.paperplane.kit.runLogs
import cn.lemonit.paperplane.kit.util.IconSource
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperBadge
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperCheckbox
import cn.lemonit.paperplane.ui.components.PaperCodeText
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperInfoButton
import cn.lemonit.paperplane.ui.components.PaperLink
import cn.lemonit.paperplane.ui.components.PaperLongPressArea
import cn.lemonit.paperplane.ui.components.PaperMoreButton
import cn.lemonit.paperplane.ui.components.PaperProgressBar
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.icons.RefIcon
import cn.lemonit.paperplane.ui.shell.LocalWideLayout
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// 插件界面描述语言（sdk.ViewNode）渲染器，对照 Mac Renderers/ViewNodeRenderer。节点类型见插件 SDK 的 view.go。
// 手机上的调整：row / toolbar 放不下时换行；多列表格在窄屏上每行显示成「首列标题 + 其余列的键值」，平板上是真表格；
// 行内操作多于一个时收进「···」菜单（长按行也能打开）。

private fun icon(raw: String?, model: PluginViewModel): IconSource? = IconSource.parse(raw, model.plugin)

/** 内置图标名（PaperButton / PaperBadge 只收内置图标；插件自带图标的按钮不显示图标） */
private fun builtinIcon(raw: String?, model: PluginViewModel): String? = (icon(raw, model) as? IconSource.Builtin)?.name

private fun dataValues(n: ViewNode): Map<String, JsonElement> = n.data.orEmpty().mapValues { JsonPrimitive(it.value) }

private fun rowValues(r: ViewTableRow): Map<String, JsonElement> =
    mapOf<String, JsonElement>("id" to JsonPrimitive(r.id)) + r.cells.mapValues { JsonPrimitive(it.value) }

@Composable
private fun toneColor(tone: String?) = if (tone == null) PaperTheme.colors.textPrimary else PaperTheme.colors.tone(Tone.of(tone)).fg

/** 递归渲染一个节点 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NodeView(node: ViewNode, model: PluginViewModel, path: String) {
    val c = PaperTheme.colors
    val t = PaperTheme.typography
    val sp = PaperTheme.spacing
    val key = node.id ?: path
    @Composable
    fun children() = node.children.forEachIndexed { i, ch -> NodeView(ch, model, "$path.$i") }

    when (node.type) {
        "column" -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sp.md)) { children() }
        "row" -> FlowRow(
            horizontalArrangement = Arrangement.spacedBy(sp.md),
            verticalArrangement = Arrangement.spacedBy(sp.sm),
        ) { node.children.forEachIndexed { i, ch -> Box(Modifier.align(Alignment.CenterVertically)) { NodeView(ch, model, "$path.$i") } } }
        "toolbar" -> FlowRow(
            horizontalArrangement = Arrangement.spacedBy(sp.sm),
            verticalArrangement = Arrangement.spacedBy(sp.sm),
        ) { node.children.forEachIndexed { i, ch -> Box(Modifier.align(Alignment.CenterVertically)) { NodeView(ch, model, "$path.$i") } } }
        "section" -> SectionNode(node, model, path, key)
        "divider" -> PaperDivider(subtle = true)
        "spacer" -> Spacer(Modifier.size(sp.md))
        "tabs" -> TabsNode(node, model, path, key)
        "heading" -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(node.text ?: node.title.orEmpty(), style = t.heading, color = c.textPrimary, modifier = Modifier.weight(1f, fill = false))
            node.info?.let { PaperInfoButton(it) }
        }
        "text" -> Row(verticalAlignment = Alignment.Top) {
            SelectionContainer(Modifier.weight(1f, fill = false)) {
                Text(node.text.orEmpty(), style = if (node.mono) t.mono else t.body, color = toneColor(node.tone))
            }
            node.info?.let { PaperInfoButton(it) }
        }
        "code" -> PaperCodeText(node.text.orEmpty(), wrap = false)
        "markdown" -> MarkdownText(node.text.orEmpty())
        "kv" -> KeyValueList(node.items.map { KvItem(it.key, it.value, it.mono, it.tone, it.href) })
        "badge" -> PaperBadge(node.text.orEmpty(), tone = Tone.of(node.tone), icon = builtinIcon(node.icon, model))
        "alert" -> PaperAlert(
            Tone.of(node.tone ?: "info"),
            node.title ?: node.text.orEmpty(),
            message = if (node.title == null) null else node.text,
            actions = if (node.children.isEmpty()) null else ({ children() }),
        )
        "stat" -> StatNode(node)
        "progress" -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
            if (node.title != null || node.text != null) {
                Row {
                    Text(node.title.orEmpty(), style = t.small, color = c.textPrimary, modifier = Modifier.weight(1f))
                    Text(node.text ?: if (node.value >= 0) "${node.value.toInt()}%" else "", style = t.small, color = c.textSecondary)
                }
            }
            PaperProgressBar(if (node.value < 0) null else (node.value / 100).toFloat(), tone = node.tone?.let { Tone.of(it) })
        }
        "empty" -> Column(
            Modifier.fillMaxWidth().padding(sp.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(sp.md),
        ) {
            RefIcon(icon(node.icon, model) ?: IconSource.Builtin("inbox"), size = PaperTheme.sizes.iconEmpty, tint = c.iconSecondary)
            node.title?.let { Text(it, style = t.body, color = c.textSecondary, textAlign = TextAlign.Center) }
            node.text?.let { Text(it, style = t.small, color = c.textTertiary, textAlign = TextAlign.Center) }
        }
        "icon" -> RefIcon(icon(node.icon, model), tint = if (node.tone == null) c.iconPrimary else c.tone(Tone.of(node.tone)).fg)
        "table" -> TableNode(node, model, key)
        "list" -> ListNode(node, model)
        "tree" -> Column(Modifier.fillMaxWidth()) { for (r in node.rows) TreeRow(r, 0, model) }
        "button" -> NodeButton(node, model)
        "link" -> PaperLink(node.text ?: node.href.orEmpty(), icon = if (node.icon != null) "external-link" else null, onClick = {
            val h = node.href
            if (!h.isNullOrEmpty()) openLink(h) else model.fire(node.action, dataValues(node))
        })
        "form" -> FormNode(node, model, key)
        "console" -> ConsoleNode(node.console.orEmpty())
        else -> {}
    }
}

@Composable
private fun NodeButton(n: ViewNode, model: PluginViewModel) {
    val kind = when (n.style) {
        "primary" -> ButtonKind.Primary
        "danger" -> ButtonKind.Danger
        "link" -> ButtonKind.Text
        else -> ButtonKind.Secondary
    }
    PaperButton(
        n.text ?: n.title.orEmpty(),
        onClick = { model.fire(n.action, dataValues(n), confirm = n.confirm, title = n.text) },
        kind = kind,
        size = ButtonSize.Small,
        icon = builtinIcon(n.icon, model),
        enabled = !n.disabled,
        loading = n.action != null && model.busyAction == n.action,
    )
}

/** 可折叠区块：标题行（i 说明、展开箭头）+ 子节点 */
@Composable
private fun SectionNode(node: ViewNode, model: PluginViewModel, path: String, key: String) {
    val open = model.expanded["sec:$key"] ?: !node.collapsed
    val c = PaperTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = PaperTheme.spacing.xs), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md)) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = PaperTheme.sizes.touchTarget)
                .clickable(role = Role.Button) { model.expanded["sec:$key"] = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(node.title.orEmpty(), style = PaperTheme.typography.bodyStrong, color = c.textPrimary, modifier = Modifier.weight(1f, fill = false))
                node.info?.let { PaperInfoButton(it) }
            }
            PaperIcon(if (open) "chevron-down" else "chevron-right", size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
        }
        if (open) node.children.forEachIndexed { i, ch -> NodeView(ch, model, "$path.$i") }
    }
}

@Composable
private fun TabsNode(node: ViewNode, model: PluginViewModel, path: String, key: String) {
    val idx = (model.tabIndex[key] ?: 0).coerceIn(0, (node.children.size - 1).coerceAtLeast(0))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md)) {
        PaperTabStrip(
            node.children.mapIndexed { i, ch -> StripTab(i, ch.title ?: "${i + 1}", icon(ch.icon, model)) },
            selected = idx,
            onSelect = { model.tabIndex[key] = it },
        )
        PaperDivider(subtle = true)
        node.children.getOrNull(idx)?.let { tab ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md)) {
                tab.children.forEachIndexed { i, ch -> NodeView(ch, model, "$path.$idx.$i") }
            }
        }
    }
}

@Composable
private fun StatNode(node: ViewNode) {
    val c = PaperTheme.colors
    Column(
        Modifier
            .widthIn(min = 120.dp)
            .background(c.bgSubtle, RoundedCornerShape(PaperTheme.radius.md))
            .padding(PaperTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(node.title.orEmpty(), style = PaperTheme.typography.small, color = c.textSecondary)
            node.info?.let { PaperInfoButton(it) }
        }
        Text(node.text.orEmpty(), style = PaperTheme.typography.stat, color = toneColor(node.tone))
    }
}

// ---------------------------------------------------------------- 表格、列表、树

/** 行内操作：一个时直接放按钮，多个时收进「···」菜单 */
@Composable
private fun RowActions(actions: List<ViewNode>, row: ViewTableRow, model: PluginViewModel) {
    if (actions.isEmpty()) return
    if (actions.size == 1) {
        val a = actions[0]
        PaperButton(
            a.text.orEmpty(),
            onClick = { model.fire(a.action, dataValues(a) + ("id" to JsonPrimitive(row.id)), confirm = a.confirm, title = a.text) },
            kind = when (a.style) {
                "primary" -> ButtonKind.Primary
                "danger" -> ButtonKind.Danger
                else -> ButtonKind.Text
            },
            size = ButtonSize.Small,
            enabled = !a.disabled,
        )
    } else {
        PaperMoreButton({ actionEntries(actions, row, model) }, size = ButtonSize.Small)
    }
}

private fun actionEntries(actions: List<ViewNode>, row: ViewTableRow, model: PluginViewModel): List<MenuEntry> = actions.map { a ->
    MenuEntry.Item(a.text.orEmpty(), destructive = a.style == "danger", enabled = !a.disabled) {
        model.fire(a.action, dataValues(a) + ("id" to JsonPrimitive(row.id)), confirm = a.confirm, title = a.text)
    }
}

@Composable
private fun TableNode(node: ViewNode, model: PluginViewModel, key: String) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val shape = RoundedCornerShape(PaperTheme.radius.md)
    val sel = model.selections[key].orEmpty()
    val grid = LocalWideLayout.current || node.columns.size <= 2
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(PaperTheme.sizes.border, c.border, shape),
    ) {
        if (grid || node.selectable) {
            // 表头（窄屏上只在可勾选时显示一行「全选」）
            Row(
                Modifier.fillMaxWidth().background(c.bgSubtle).defaultMinSize(minHeight = PaperTheme.sizes.rowHeight).padding(horizontal = sp.md),
                horizontalArrangement = Arrangement.spacedBy(sp.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (node.selectable) {
                    PaperCheckbox(node.rows.isNotEmpty() && sel.size == node.rows.size, onCheckedChange = { on ->
                        model.selections[key] = if (on) node.rows.map { it.id }.toSet() else emptySet()
                    })
                }
                if (grid) {
                    for (col in node.columns) {
                        Text(col.title, style = PaperTheme.typography.label, color = c.textSecondary, maxLines = 1, modifier = Modifier.weight(1f))
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
            PaperDivider(subtle = true)
        }
        node.rows.forEachIndexed { i, r ->
            if (i > 0) PaperDivider(subtle = true)
            TableRowView(r, node, model, key, grid)
        }
    }
}

@Composable
private fun TableRowView(row: ViewTableRow, node: ViewNode, model: PluginViewModel, key: String, grid: Boolean) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val selected = model.selections[key]?.contains(row.id) == true
    val color = toneColor(row.tone)
    PaperLongPressArea(
        onClick = row.action?.let { a -> { model.fire(a, rowValues(row)) } },
        menu = if (row.actions.size > 1) ({ actionEntries(row.actions, row, model) }) else null,
        modifier = Modifier.fillMaxWidth().background(if (selected) c.bgSelected else androidx.compose.ui.graphics.Color.Transparent),
    ) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = PaperTheme.sizes.rowHeight).padding(horizontal = sp.md, vertical = sp.sm),
            horizontalArrangement = Arrangement.spacedBy(sp.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (node.selectable) {
                PaperCheckbox(selected, onCheckedChange = { on ->
                    val s = model.selections[key].orEmpty()
                    model.selections[key] = if (on) s + row.id else s - row.id
                })
            }
            if (grid) {
                node.columns.forEachIndexed { i, col ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(sp.xs), verticalAlignment = Alignment.CenterVertically) {
                        if (i == 0 && row.icon != null) RefIcon(icon(row.icon, model), size = PaperTheme.sizes.iconSmall)
                        Text(
                            row.cells[col.key].orEmpty(),
                            style = if (col.mono) PaperTheme.typography.monoSmall else PaperTheme.typography.small,
                            color = color,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = when (col.align) {
                                "right" -> TextAlign.End
                                "center" -> TextAlign.Center
                                else -> TextAlign.Start
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                // 窄屏：首列做标题，其余列做「列名 值」
                if (row.icon != null) RefIcon(icon(row.icon, model), size = PaperTheme.sizes.icon)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val first = node.columns.firstOrNull()
                    Text(
                        first?.let { row.cells[it.key] }.orEmpty(),
                        style = if (first?.mono == true) PaperTheme.typography.mono else PaperTheme.typography.body,
                        color = color,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    for (col in node.columns.drop(1)) {
                        val v = row.cells[col.key].orEmpty()
                        if (v.isEmpty()) continue
                        Row(horizontalArrangement = Arrangement.spacedBy(sp.sm)) {
                            Text(col.title, style = PaperTheme.typography.caption, color = c.textTertiary)
                            Text(
                                v,
                                style = if (col.mono) PaperTheme.typography.monoSmall else PaperTheme.typography.small,
                                color = c.textSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            RowActions(row.actions, row, model)
        }
    }
}

/** list 节点：cells["title"] 标题、cells["subtitle"] 副标题 */
@Composable
private fun ListNode(node: ViewNode, model: PluginViewModel) {
    val sp = PaperTheme.spacing
    Column(Modifier.fillMaxWidth()) {
        node.rows.forEachIndexed { i, r ->
            if (i > 0) PaperDivider(subtle = true)
            PaperLongPressArea(
                onClick = r.action?.let { a -> { model.fire(a, rowValues(r)) } },
                menu = if (r.actions.size > 1) ({ actionEntries(r.actions, r, model) }) else null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = PaperTheme.sizes.rowHeight).padding(vertical = sp.sm),
                    horizontalArrangement = Arrangement.spacedBy(sp.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (r.icon != null) RefIcon(icon(r.icon, model))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(r.cells["title"].orEmpty(), style = PaperTheme.typography.body, color = toneColor(r.tone))
                        r.cells["subtitle"]?.takeIf { it.isNotEmpty() }?.let {
                            Text(it, style = PaperTheme.typography.small, color = PaperTheme.colors.textSecondary)
                        }
                    }
                    RowActions(r.actions, r, model)
                }
            }
        }
    }
}

@Composable
private fun TreeRow(row: ViewTableRow, depth: Int, model: PluginViewModel) {
    val c = PaperTheme.colors
    val open = model.expanded[row.id] ?: row.expanded
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = PaperTheme.sizes.rowHeight)
            .clickable(role = Role.Button) {
                if (row.children.isNotEmpty()) model.expanded[row.id] = !open
                row.action?.let { model.fire(it, rowValues(row)) }
            }
            .padding(start = (depth * 16).dp, end = PaperTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(PaperTheme.sizes.iconSmall)) {
            if (row.children.isNotEmpty()) PaperIcon(if (open) "chevron-down" else "chevron-right", size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
        }
        if (row.icon != null) RefIcon(icon(row.icon, model))
        Column(Modifier.weight(1f)) {
            Text(row.cells["title"] ?: row.cells.values.firstOrNull() ?: row.id, style = PaperTheme.typography.body, color = toneColor(row.tone), maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.cells["subtitle"]?.let { Text(it, style = PaperTheme.typography.small, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    if (open) for (ch in row.children) TreeRow(ch, depth + 1, model)
}

// ---------------------------------------------------------------- 表单、控制台、markdown

@Composable
private fun FormNode(node: ViewNode, model: PluginViewModel, key: String) {
    var showErrors by remember { mutableStateOf(false) }
    val values = model.formValues[key] ?: FormValues.initial(node.fields, node.values.orEmpty())
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md)) {
        node.title?.let { Text(it, style = PaperTheme.typography.title, color = PaperTheme.colors.textPrimary) }
        FormFields(node.fields, values, { model.formValues[key] = it }, showErrors = showErrors)
        PaperButton(
            node.submitText ?: tr("common.submit"),
            onClick = {
                if (FormValues.missingRequired(node.fields, values).isNotEmpty()) {
                    showErrors = true
                } else {
                    model.fire(node.action, FormValues.normalized(node.fields, values), confirm = node.confirm)
                }
            },
            kind = ButtonKind.Primary,
            size = ButtonSize.Small,
            enabled = !node.disabled,
            loading = node.action != null && model.busyAction == node.action,
        )
    }
}

/** console 节点：某个运行 / 任务控制台的最后 200 行（可见时每 2 秒刷新） */
@Composable
private fun ConsoleNode(consoleId: String) {
    val c = PaperTheme.colors
    var text by remember(consoleId) { mutableStateOf("") }
    val scroll = rememberScrollState()
    val foreground = AppModel.foreground
    LaunchedEffect(consoleId, foreground) {
        if (consoleId.isEmpty() || !foreground) return@LaunchedEffect
        while (true) {
            try {
                val r = AppModel.api().runLogs(consoleId, tail = 200)
                text = r.lines.joinToString("\n") { it.text }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            delay(2000)
        }
    }
    LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
    val shape = RoundedCornerShape(PaperTheme.radius.md)
    Box(
        Modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(shape)
            .background(c.consoleBg)
            .border(PaperTheme.sizes.border, c.border, shape)
            .verticalScroll(scroll)
            .padding(PaperTheme.spacing.md),
    ) {
        SelectionContainer { Text(text, style = PaperTheme.typography.monoSmall, color = c.consoleText) }
    }
}

/** 只支持粗体、行内代码、链接、列表（和桌面一致） */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val c = PaperTheme.colors
    val t = PaperTheme.typography
    val linkStyle = TextLinkStyles(SpanStyle(color = c.textLink))
    val codeStyle = SpanStyle(fontFamily = t.monoFamily, background = c.bgCode)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs)) {
        for (line in text.split("\n")) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> Row(horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm)) {
                    Text("•", style = t.body, color = c.textSecondary)
                    Text(inlineMarkdown(trimmed.drop(2), linkStyle, codeStyle), style = t.body, color = c.textPrimary)
                }
                trimmed.isEmpty() -> Spacer(Modifier.height(PaperTheme.spacing.xs))
                else -> Text(inlineMarkdown(line, linkStyle, codeStyle), style = t.body, color = c.textPrimary)
            }
        }
    }
}

private val inlineRe = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\(([^)\s]+)\)""")

/** 行内 markdown：**粗体**、`代码`、[文字](链接) */
internal fun inlineMarkdown(s: String, linkStyle: TextLinkStyles, codeStyle: SpanStyle): AnnotatedString = buildAnnotatedString {
    var i = 0
    for (m in inlineRe.findAll(s)) {
        append(s.substring(i, m.range.first))
        val (bold, code, label, url) = m.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
            code.isNotEmpty() -> withStyle(codeStyle) { append(code) }
            else -> withLink(LinkAnnotation.Url(url, linkStyle)) { append(label) }
        }
        i = m.range.last + 1
    }
    append(s.substring(i))
}
