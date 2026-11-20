package cn.lemonit.paperplane.features.terminal

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 终端下方的辅助键栏（软键盘上没有的键）。两行，每个键至少 44dp 高：
//   Esc  /  -  |  Home  ↑  End  PgUp  键盘
//   Tab Ctrl Alt ~  ←   ↓   →  PgDn  粘贴
// Ctrl / Alt 是粘滞修饰键：点一下对下一个键生效（软键盘输入也算），再点一下锁定，再点关掉。方向键、翻页键按住连发。

/** 键栏上的一个键 */
private sealed interface BarKey {
    data class Special(val key: TermKey, val label: String? = null, val icon: String? = null, val repeat: Boolean = false) : BarKey
    data class Glyph(val text: String) : BarKey
    data class Mod(val which: String, val label: String) : BarKey
    data object Keyboard : BarKey
    data object Paste : BarKey
}

private val ROW1 = listOf(
    BarKey.Special(TermKey.ESC, label = "Esc"),
    BarKey.Glyph("/"),
    BarKey.Glyph("-"),
    BarKey.Glyph("|"),
    BarKey.Special(TermKey.HOME, label = "Home"),
    BarKey.Special(TermKey.UP, icon = "arrow-up", repeat = true),
    BarKey.Special(TermKey.END, label = "End"),
    BarKey.Special(TermKey.PAGE_UP, label = "PgUp", repeat = true),
    BarKey.Keyboard,
)

private val ROW2 = listOf(
    BarKey.Special(TermKey.TAB, label = "Tab"),
    BarKey.Mod("ctrl", "Ctrl"),
    BarKey.Mod("alt", "Alt"),
    BarKey.Glyph("~"),
    BarKey.Special(TermKey.LEFT, icon = "arrow-left", repeat = true),
    BarKey.Special(TermKey.DOWN, icon = "arrow-down", repeat = true),
    BarKey.Special(TermKey.RIGHT, icon = "arrow-right", repeat = true),
    BarKey.Special(TermKey.PAGE_DOWN, label = "PgDn", repeat = true),
    BarKey.Paste,
)

/**
 * @param keyboardVisible 软键盘当前是否弹出（键盘键显示收起 / 弹出）
 */
@Composable
internal fun TerminalKeyBar(
    session: TerminalSession,
    keyboardVisible: Boolean,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PaperTheme.colors
    Column(modifier.fillMaxWidth().background(c.bgChrome)) {
        PaperDivider(subtle = true)
        for (row in listOf(ROW1, ROW2)) {
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 2.dp)) {
                for (k in row) KeyCell(k, session, keyboardVisible, onPaste)
            }
        }
    }
}

@Composable
private fun RowScope.KeyCell(k: BarKey, session: TerminalSession, keyboardVisible: Boolean, onPaste: () -> Unit) {
    val c = PaperTheme.colors
    when (k) {
        is BarKey.Special -> Key(
            label = k.label, icon = k.icon, description = k.label ?: keyName(k.key), repeat = k.repeat,
        ) { session.key(k.key) }
        is BarKey.Glyph -> Key(label = k.text, description = k.text, mono = true) { session.text(k.text) }
        is BarKey.Mod -> {
            val st = if (k.which == "ctrl") session.ctrl else session.alt
            Key(
                label = k.label,
                description = k.label,
                background = when (st) {
                    ModState.OFF -> Color.Transparent
                    ModState.ONCE -> c.accentSubtle
                    ModState.LOCKED -> c.accent
                },
                foreground = when (st) {
                    ModState.OFF -> c.textPrimary
                    ModState.ONCE -> c.accent
                    ModState.LOCKED -> c.textOnAccent
                },
            ) { session.toggleMod(k.which) }
        }
        BarKey.Keyboard -> Key(
            icon = "keyboard",
            description = tr("mobile.terminal.keyboard"),
            foreground = if (keyboardVisible) c.accent else c.iconPrimary,
        ) { session.showKeyboard(!keyboardVisible) }
        BarKey.Paste -> Key(icon = "clipboard", description = tr("mobile.terminal.paste"), action = onPaste)
    }
}

private fun keyName(k: TermKey): String = when (k) {
    TermKey.UP -> "Up"
    TermKey.DOWN -> "Down"
    TermKey.LEFT -> "Left"
    TermKey.RIGHT -> "Right"
    else -> k.name
}

@Composable
private fun RowScope.Key(
    label: String? = null,
    icon: String? = null,
    description: String,
    mono: Boolean = false,
    repeat: Boolean = false,
    background: Color = Color.Transparent,
    foreground: Color = PaperTheme.colors.textPrimary,
    action: () -> Unit,
) {
    val c = PaperTheme.colors
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val fire by rememberUpdatedState(action)
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(PaperTheme.radius.md)
    // 点击范围是整格（键帽之间的缝也算），键帽画在里面
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .semantics {
                role = Role.Button
                contentDescription = description
            }
            .pointerInput(repeat) {
                // 按下就发（不等抬起，打字更跟手）；可连发的键按住 400ms 后每 50ms 一次
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    fire()
                    var job: Job? = null
                    if (repeat) {
                        job = scope.launch {
                            delay(400)
                            while (true) {
                                fire()
                                delay(50)
                            }
                        }
                    }
                    waitForUpOrCancellation()?.consume()
                    job?.cancel()
                    pressed = false
                }
            }
            .padding(horizontal = 2.dp, vertical = 3.dp)
            .clip(shape)
            .background(if (pressed) c.bgPressed else if (background == Color.Transparent) c.buttonSecondaryBg else background, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            PaperIcon(icon, size = PaperTheme.sizes.iconSmall, tint = foreground)
        } else if (label != null) {
            // 窄屏上一格不到 40dp：文字键统一用小一号的字（Home、PgUp 也放得下）
            val style = if (mono) PaperTheme.typography.mono else PaperTheme.typography.caption
            Text(
                label,
                style = style.copy(fontWeight = FontWeight.Medium),
                color = foreground,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
