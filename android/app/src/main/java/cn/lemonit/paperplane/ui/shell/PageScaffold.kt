package cn.lemonit.paperplane.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.theme.PaperTheme

/**
 * 页面怎么「返回」：压栈打开的页面 = 出栈；作为根页面显示时（平板侧栏选中的工具链、设置等）为 null，不显示返回按钮。
 * 由壳提供，页面一般不用自己读，用 [PageScaffold] 即可。
 */
val LocalPageBack = compositionLocalOf<(() -> Unit)?> { null }

/** 页面是否在平板宽屏布局里（侧栏常驻）。功能页可据此换双栏等布局。 */
val LocalWideLayout = compositionLocalOf { false }

/** 页面是不是要自己处理状态栏留白（壳在手机上全屏显示压栈页面时为 true；平板内容区里为 false） */
val LocalEdgeToEdge = compositionLocalOf { true }

/**
 * 详情页的标准外框：标题栏（返回、标题与副标题、右侧操作）+ 内容。
 * 标题栏下方可放 [header]（如标签切换），内容区自己决定滚动方式。
 *
 * @param bottomBar 固定在底部的操作栏（例如提交按钮），会让出系统导航栏
 */
@Composable
fun PageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = LocalPageBack.current,
    actions: @Composable RowScope.() -> Unit = {},
    header: (@Composable ColumnScope.() -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val c = PaperTheme.colors
    val edge = LocalEdgeToEdge.current
    Column(modifier.fillMaxSize().background(c.bgContent)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.bgChrome)
                .then(if (edge) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier),
        ) {
            PageTitleBar(title = title, subtitle = subtitle, onBack = onBack, actions = actions)
            header?.invoke(this)
        }
        PaperDivider(subtle = true)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CompositionLocalProvider(LocalEdgeToEdge provides false) { content() }
        }
        if (bottomBar != null) {
            PaperDivider(subtle = true)
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(c.bgChrome)
                    .then(if (edge) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier),
            ) { bottomBar() }
        }
    }
}

/** 标题栏一行（56dp）：返回 + 标题（可两行：标题 / 副标题）+ 右侧操作 */
@Composable
fun PageTitleBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val t = PaperTheme.typography
    val c = PaperTheme.colors
    val s = PaperTheme.spacing
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = s.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            PaperIconButton("chevron-left", tr("common.back"), onClick = onBack, tint = c.iconPrimary)
        } else {
            Spacer(Modifier.width(s.md))
        }
        Column(Modifier.weight(1f).padding(horizontal = s.xs), verticalArrangement = Arrangement.Center) {
            Text(title, style = t.title, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = t.small, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}
