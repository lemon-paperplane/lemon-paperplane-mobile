package cn.lemonit.paperplane.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.theme.PaperTheme
import kotlinx.coroutines.launch

/**
 * 底部弹出面板（手机上代替居中对话框）：顶部拖动条、标题行（可带关闭按钮）、内容区。
 * 内容默认可滚动；内容自己是 LazyColumn 时传 scrollable = false。下滑、点遮罩、返回键都会关闭。
 * edgeToEdge = true 时内容不留左右边距（整行可点的列表，如选择器）。
 *
 * ```
 * if (show) PaperBottomSheet(onDismiss = { show = false }, title = tr("server.add")) { dismiss ->
 *     PaperTextField(…)
 *     PaperButton(tr("common.ok"), onClick = { save(); dismiss() }, kind = ButtonKind.Primary, modifier = Modifier.fillMaxWidth())
 * }
 * ```
 * 内容收到的 dismiss 先播收起动画再回调 onDismiss（按钮里关面板用它，不要直接改 show）。
 * ```
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperBottomSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    showClose: Boolean = true,
    scrollable: Boolean = true,
    edgeToEdge: Boolean = false,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    val c = PaperTheme.colors
    val r = PaperTheme.radius
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val hide: () -> Unit = {
        scope.launch { state.hide() }.invokeOnCompletion { if (!state.isVisible) onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        sheetMaxWidth = PaperTheme.sizes.sheetMaxWidth,
        shape = RoundedCornerShape(topStart = r.xl, topEnd = r.xl),
        containerColor = c.bgElevated,
        contentColor = c.textPrimary,
        tonalElevation = 0.dp,
        scrimColor = c.scrim,
        dragHandle = { SheetHandle() },
    ) {
        SheetBody(title, if (showClose) hide else null, scrollable, edgeToEdge) { content(hide) }
    }
}

/**
 * 面板外观的静态版本（不弹出）：陈列页、截图测试里看面板长什么样。
 */
@Composable
fun PaperSheetPreview(
    modifier: Modifier = Modifier,
    title: String? = null,
    showClose: Boolean = true,
    edgeToEdge: Boolean = false,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    val c = PaperTheme.colors
    val shape = RoundedCornerShape(topStart = PaperTheme.radius.xl, topEnd = PaperTheme.radius.xl)
    Column(
        modifier
            .fillMaxWidth()
            .background(c.bgElevated, shape)
            .border(PaperTheme.sizes.border, c.border, shape),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SheetHandle() }
        SheetBody(title, if (showClose) ({}) else null, scrollable = false, edgeToEdge = edgeToEdge) { content {} }
    }
}

@Composable
private fun SheetHandle() {
    Box(
        Modifier
            .padding(top = PaperTheme.spacing.md, bottom = PaperTheme.spacing.xs)
            .size(width = 36.dp, height = 4.dp)
            .background(PaperTheme.colors.borderStrong, CircleShape),
    )
}

@Composable
private fun SheetBody(
    title: String?,
    onClose: (() -> Unit)?,
    scrollable: Boolean,
    edgeToEdge: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sp = PaperTheme.spacing
    Column(Modifier.fillMaxWidth()) {
        if (title != null || onClose != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(start = sp.xl, end = sp.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title ?: "",
                    style = PaperTheme.typography.heading,
                    color = PaperTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onClose != null) {
                    PaperIconButton("x", tr("common.close"), onClick = onClose, tint = PaperTheme.colors.iconSecondary)
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(
                    start = if (edgeToEdge) 0.dp else sp.xl,
                    end = if (edgeToEdge) 0.dp else sp.xl,
                    top = sp.xs,
                    bottom = if (edgeToEdge) sp.md else sp.xxl,
                ),
            verticalArrangement = Arrangement.spacedBy(if (edgeToEdge) 0.dp else sp.lg),
            content = content,
        )
    }
}
