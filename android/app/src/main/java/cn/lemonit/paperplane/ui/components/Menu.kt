package cn.lemonit.paperplane.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
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
import kotlin.math.roundToInt

/** 菜单项（对照 Mac MenuEntry；手机上不做子菜单，需要时换成底部弹出面板） */
sealed interface MenuEntry {
    /** 可点的项。destructive 显示红色；checked 在图标位置打勾 */
    data class Item(
        val title: String,
        val icon: String? = null,
        val destructive: Boolean = false,
        val enabled: Boolean = true,
        val checked: Boolean = false,
        val onClick: () -> Unit,
    ) : MenuEntry

    /** 小标题（分组名） */
    data class Header(val title: String) : MenuEntry

    /** 分隔线（首尾和连续的会被去掉） */
    data object Divider : MenuEntry
}

/** 去掉首尾与连续的分隔线 */
fun normalizeMenu(entries: List<MenuEntry>): List<MenuEntry> {
    val out = ArrayList<MenuEntry>(entries.size)
    for (e in entries) {
        if (e is MenuEntry.Divider && (out.isEmpty() || out.last() is MenuEntry.Divider)) continue
        out += e
    }
    while (out.lastOrNull() is MenuEntry.Divider) out.removeAt(out.size - 1)
    return out
}

/**
 * 菜单面板本体（不含弹出层）：陈列页、截图测试直接画它；正常用 [PaperMenu] / [PaperMoreButton] / 长按。
 */
@Composable
fun PaperMenuPanel(entries: List<MenuEntry>, onItemClick: (MenuEntry.Item) -> Unit, modifier: Modifier = Modifier) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val shape = RoundedCornerShape(PaperTheme.radius.lg)
    Column(
        modifier = modifier
            .paperShadow(ShadowKind.Popup, shape)
            .background(c.bgElevated, shape)
            .border(PaperTheme.sizes.border, c.border, shape)
            .clip(shape)
            .widthIn(min = 200.dp, max = 300.dp)
            .width(IntrinsicSize.Max)
            .heightIn(max = 480.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = sp.xs),
    ) {
        for (e in normalizeMenu(entries)) {
            when (e) {
                is MenuEntry.Divider -> Box(
                    Modifier.padding(vertical = sp.xs).fillMaxWidth().height(PaperTheme.sizes.border).background(c.borderSubtle),
                )
                is MenuEntry.Header -> Text(
                    e.title,
                    style = PaperTheme.typography.caption,
                    color = c.textTertiary,
                    maxLines = 1,
                    modifier = Modifier.padding(start = sp.lg, end = sp.lg, top = sp.md, bottom = sp.xs),
                )
                is MenuEntry.Item -> MenuRow(e) { onItemClick(e) }
            }
        }
    }
}

@Composable
private fun MenuRow(item: MenuEntry.Item, onClick: () -> Unit) {
    val c = PaperTheme.colors
    val fg = when {
        !item.enabled -> c.textDisabled
        item.destructive -> c.danger
        else -> c.textPrimary
    }
    val iconTint = when {
        item.destructive -> c.danger
        else -> c.iconPrimary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PaperTheme.sizes.rowHeight)
            .clickable(enabled = item.enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = PaperTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(PaperTheme.sizes.icon), contentAlignment = Alignment.Center) {
            when {
                item.checked -> PaperIcon("check", size = PaperTheme.sizes.iconSmall, tint = c.accent)
                item.icon != null -> PaperIcon(item.icon, tint = iconTint, modifier = Modifier.alpha(if (item.enabled) 1f else 0.45f))
            }
        }
        Text(item.title, style = PaperTheme.typography.body, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 弹出菜单。放在锚点（按钮、行）所在的 Box 里：
 * - [pressOffset] 为空：出现在锚点下方（[alignEnd] 时右对齐），下方放不下就翻到上方；
 * - 给了 [pressOffset]（相对锚点左上角的像素）：出现在手指按下的位置（长按菜单）。
 * 点外面或返回键关闭；点了某项先关菜单再执行。
 */
@Composable
fun PaperMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    entries: List<MenuEntry>,
    pressOffset: IntOffset? = null,
    alignEnd: Boolean = false,
) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = expanded
    if (!state.currentState && !state.targetState) return
    val density = LocalDensity.current
    val provider = remember(pressOffset, alignEnd, density) {
        MenuPositionProvider(pressOffset, alignEnd, with(density) { 8.dp.roundToPx() }, with(density) { 4.dp.roundToPx() })
    }
    val fast = PaperTheme.motion.fast
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(
            visibleState = state,
            enter = fadeIn(tween(fast)) + scaleIn(tween(fast), initialScale = 0.96f),
            exit = fadeOut(tween(fast)),
        ) {
            // 给阴影留边
            Box(Modifier.padding(8.dp)) {
                PaperMenuPanel(entries, onItemClick = { item ->
                    onDismiss()
                    item.onClick()
                })
            }
        }
    }
}

private class MenuPositionProvider(
    private val pressOffset: IntOffset?,
    private val alignEnd: Boolean,
    private val margin: Int,
    private val gap: Int,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        // 内容外面有 8dp 阴影留边（[margin] 正好也是 8dp），定位时按面板本体算
        val inset = margin
        val w = popupContentSize.width
        val h = popupContentSize.height
        var x: Int
        var y: Int
        if (pressOffset != null) {
            x = anchorBounds.left + pressOffset.x - inset
            y = anchorBounds.top + pressOffset.y - inset
            if (x + w > windowSize.width) x = anchorBounds.left + pressOffset.x - w + inset
            if (y + h > windowSize.height) y = anchorBounds.top + pressOffset.y - h + inset
        } else {
            val endAligned = alignEnd xor (layoutDirection == LayoutDirection.Rtl)
            x = if (endAligned) anchorBounds.right - w + inset else anchorBounds.left - inset
            y = anchorBounds.bottom + gap - inset
            if (y + h > windowSize.height) y = anchorBounds.top - gap - h + inset
        }
        x = x.coerceIn(0, max(0, windowSize.width - w))
        y = y.coerceIn(0, max(0, windowSize.height - h))
        return IntOffset(x, y)
    }
}

/**
 * 「更多」按钮（···），点开弹出菜单，右对齐在按钮下方。entries 在点开时才求值。
 *
 * `PaperMoreButton({ listOf(MenuEntry.Item(tr("common.copy"), "copy") { … }, MenuEntry.Divider, MenuEntry.Item(tr("common.delete"), "trash-2", destructive = true) { … }) })`
 */
@Composable
fun PaperMoreButton(
    entries: () -> List<MenuEntry>,
    modifier: Modifier = Modifier,
    size: ButtonSize = ButtonSize.Regular,
    icon: String = "ellipsis",
    contentDescription: String = tr("common.more"),
    tint: androidx.compose.ui.graphics.Color? = PaperTheme.colors.iconSecondary,
) {
    var open by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf(emptyList<MenuEntry>()) }
    Box(modifier) {
        PaperIconButton(icon, contentDescription, onClick = {
            items = entries()
            open = true
        }, size = size, tint = tint, active = open)
        PaperMenu(open, onDismiss = { open = false }, entries = items, alignEnd = true)
    }
}

/** 长按菜单的状态：记住最后一次按下的位置，长按时在那里弹出菜单 */
@Stable
class LongPressMenuState {
    var expanded by mutableStateOf(false)
    var offset by mutableStateOf(IntOffset.Zero)
    var entries by mutableStateOf(emptyList<MenuEntry>())
    internal var lastDown: Offset = Offset.Zero

    fun open(items: List<MenuEntry>) {
        if (items.isEmpty()) return
        entries = items
        offset = IntOffset(lastDown.x.roundToInt(), lastDown.y.roundToInt())
        expanded = true
    }
}

/**
 * 可点击、可长按出菜单的区域（列表行、卡片内部用；也可以包任意内容）。
 * 修饰顺序：调用方的 modifier（形状、底色）→ 点击 / 长按（按下反馈盖在形状内）。
 *
 * `PaperLongPressArea(onClick = { open(p) }, menu = { CardMenu.entries(p) }, modifier = Modifier.clip(shape)) { … }`
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PaperLongPressArea(
    onClick: (() -> Unit)?,
    menu: (() -> List<MenuEntry>)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = remember { LongPressMenuState() }
    val interactive = enabled && (onClick != null || menu != null)
    Box(
        modifier = modifier
            .then(
                if (menu != null) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            state.lastDown = down.position
                        }
                    }
                } else {
                    Modifier
                },
            )
            .then(
                if (interactive) {
                    Modifier.combinedClickable(
                        enabled = enabled,
                        onClick = { onClick?.invoke() ?: menu?.let { state.open(it()) } },
                        onLongClick = menu?.let { m -> { state.open(m()) } },
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        content()
        if (menu != null) {
            PaperMenu(state.expanded, onDismiss = { state.expanded = false }, entries = state.entries, pressOffset = state.offset)
        }
    }
}
