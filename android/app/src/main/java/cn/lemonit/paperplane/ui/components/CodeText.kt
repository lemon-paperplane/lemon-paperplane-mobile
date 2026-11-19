package cn.lemonit.paperplane.ui.components

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.theme.PaperTheme
import kotlinx.coroutines.launch

/**
 * 代码 / 命令 / 路径文本：等宽字体、代码底色，长按可选择；带复制按钮（复制后提示「已复制」）。
 * wrap = false 时不换行、横向滚动（长命令、日志行）。
 *
 * `PaperCodeText("npm run dev -- --host 0.0.0.0")`、`PaperCodeText(fingerprint, small = true, wrap = true)`
 */
@Composable
fun PaperCodeText(
    text: String,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    wrap: Boolean = true,
    copyable: Boolean = true,
    framed: Boolean = true,
) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val shape = RoundedCornerShape(PaperTheme.radius.md)
    val style: TextStyle = if (small) PaperTheme.typography.monoSmall else PaperTheme.typography.mono
    val copy = rememberCopyAction()
    Row(
        modifier
            .fillMaxWidth()
            .then(if (framed) Modifier.background(c.bgCode, shape).border(PaperTheme.sizes.border, c.borderSubtle, shape) else Modifier),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .weight(1f)
                .then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                .padding(horizontal = if (framed) sp.lg else 0.dp, vertical = if (framed) sp.md + 2.dp else 0.dp),
        ) {
            SelectionContainer {
                Text(text, style = style, color = c.textPrimary, softWrap = wrap)
            }
        }
        if (copyable) {
            PaperIconButton("copy", tr("common.copy"), onClick = { copy(text) }, size = ButtonSize.Small, tint = c.iconSecondary)
        }
    }
}

/** 复制到剪贴板并提示「已复制」：`val copy = rememberCopyAction(); copy(url)` */
@Composable
fun rememberCopyAction(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return { s ->
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", s)))
            Toasts.show(tr("common.copied"), ToastKind.Success)
        }
    }
}
