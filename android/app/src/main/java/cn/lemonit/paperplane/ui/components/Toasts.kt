package cn.lemonit.paperplane.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** 提示条种类：info / success 停留 4 秒，warning / error 8 秒（设计变量 motion.toast*） */
enum class ToastKind(val tone: Tone) { Info(Tone.Info), Success(Tone.Success), Warning(Tone.Warning), Error(Tone.Danger) }

/** 一条提示 */
class Toast internal constructor(
    val id: Long,
    val kind: ToastKind,
    val message: String,
    val detail: String?,
    val actionText: String?,
    val action: (() -> Unit)?,
)

/**
 * 全局提示条（对照 Mac ToastStore）。任何线程、任何地方都能调：
 *
 * ```
 * Toasts.show(tr("common.saved"), ToastKind.Success)
 * Toasts.error(e)                              // 显示服务器给的 message
 * Toasts.error(e, title = tr("action.run.failed"))
 * Toasts.show(tr("import.done", 3), actionText = tr("card.open")) { … }
 * ```
 * 界面根上放一个 [PaperToastHost] 负责显示；最多同时显示 3 条。
 */
object Toasts {
    private const val MAX = 3
    private val seq = AtomicLong(0)
    private val _items = MutableStateFlow<List<Toast>>(emptyList())
    val items: StateFlow<List<Toast>> = _items.asStateFlow()

    /** 显示一条提示，返回 id（可以提前 [dismiss]）。detail 是第二行（错误详情等）。 */
    fun show(
        message: String,
        kind: ToastKind = ToastKind.Info,
        detail: String? = null,
        actionText: String? = null,
        action: (() -> Unit)? = null,
    ): Long {
        val t = Toast(seq.incrementAndGet(), kind, message, detail?.takeIf { it.isNotBlank() }, actionText, action)
        _items.update { (it + t).takeLast(MAX) }
        return t.id
    }

    fun success(message: String, detail: String? = null) = show(message, ToastKind.Success, detail)
    fun warning(message: String, detail: String? = null) = show(message, ToastKind.Warning, detail)
    fun error(message: String, detail: String? = null) = show(message, ToastKind.Error, detail)

    /**
     * 操作出错：有 title 时标题 + 错误信息两行，没有时直接显示错误信息（服务器给的 message）。取消不提示。
     */
    fun error(e: Throwable, title: String? = null) {
        if (e is CancellationException) return
        val msg = e.message?.takeIf { it.isNotBlank() }
        if (title != null) show(title, ToastKind.Error, msg) else show(msg ?: tr("common.failed"), ToastKind.Error)
    }

    fun dismiss(id: Long) = _items.update { list -> list.filterNot { it.id == id } }

    fun clear() = _items.update { emptyList() }
}

/**
 * 显示 [Toasts] 的宿主。放在界面根上（底部标签栏之上），提示条从底部往上叠。
 *
 * `Box(Modifier.fillMaxSize()) { … ; PaperToastHost(Modifier.align(Alignment.BottomCenter).padding(bottom = tabBarHeight)) }`
 */
@Composable
fun PaperToastHost(modifier: Modifier = Modifier) {
    val items by Toasts.items.collectAsState()
    val motion = PaperTheme.motion
    // 已经从列表里移除、还在播退出动画的条目也要留着（普通 Map：增删由 items 和动画状态的变化驱动重组）
    val states = remember { LinkedHashMap<Long, Pair<Toast, MutableTransitionState<Boolean>>>() }
    for (t in items) {
        if (t.id !in states) states[t.id] = t to MutableTransitionState(false).apply { targetState = true }
    }
    for ((id, pair) in states.toMap()) {
        val alive = items.any { it.id == id }
        pair.second.targetState = alive
        if (!alive && !pair.second.currentState && pair.second.isIdle) states.remove(id)
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = PaperTheme.spacing.lg, vertical = PaperTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for ((id, pair) in states.entries.sortedBy { it.key }) {
            val (toast, state) = pair
            key(id) {
                AnimatedVisibility(
                    visibleState = state,
                    enter = fadeIn(tween(motion.normal)) + slideInVertically(tween(motion.normal)) { it / 2 } + expandVertically(tween(motion.normal)),
                    exit = fadeOut(tween(motion.normal)) + shrinkVertically(tween(motion.normal)),
                ) {
                    PaperToastView(toast, onClose = { Toasts.dismiss(id) })
                }
                LaunchedEffect(id) {
                    delay(if (toast.kind == ToastKind.Error || toast.kind == ToastKind.Warning) motion.toastErrorMillis else motion.toastMillis)
                    Toasts.dismiss(id)
                }
            }
        }
    }
}

/** 一条提示的外观（陈列页、截图测试也直接用） */
@Composable
fun PaperToastView(toast: Toast, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = PaperTheme.colors
    val tone = c.tone(toast.kind.tone)
    val shape = RoundedCornerShape(PaperTheme.radius.lg)
    Row(
        modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .paperShadow(ShadowKind.Toast, shape)
            .background(c.bgElevated, shape)
            .border(PaperTheme.sizes.border, c.border, shape)
            .padding(start = PaperTheme.spacing.lg, end = PaperTheme.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        // 图标、关闭按钮和第一行文字对齐（多行时不居中）
        PaperIcon(toneIcon(toast.kind.tone), tint = tone.fg, modifier = Modifier.padding(top = PaperTheme.spacing.lg))
        Column(Modifier.weight(1f).padding(vertical = PaperTheme.spacing.lg), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xxs)) {
            Text(toast.message, style = PaperTheme.typography.bodyStrong, color = c.textPrimary)
            if (toast.detail != null) {
                Text(toast.detail, style = PaperTheme.typography.small, color = c.textSecondary, maxLines = 6)
            }
            if (toast.actionText != null && toast.action != null) {
                PaperButton(toast.actionText, onClick = {
                    toast.action.invoke()
                    onClose()
                }, kind = ButtonKind.Text, size = ButtonSize.Small, modifier = Modifier.padding(top = PaperTheme.spacing.xxs))
            }
        }
        PaperIconButton("x", tr("common.close"), onClick = onClose, tint = c.iconSecondary)
    }
}

/** 陈列页 / 测试用：直接构造一条提示 */
fun previewToast(message: String, kind: ToastKind, detail: String? = null, actionText: String? = null): Toast =
    Toast(-1, kind, message, detail, actionText, if (actionText != null) ({}) else null)
