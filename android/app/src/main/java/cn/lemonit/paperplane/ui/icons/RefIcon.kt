package cn.lemonit.paperplane.ui.icons

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.kit.util.IconSource
import cn.lemonit.paperplane.ui.theme.PaperTheme

/**
 * 按引用显示图标：内置图标直接画；插件图标（plugin:<id>/<path>）从当前服务器取 SVG（按服务器缓存），
 * 取到之前和取不到时显示 [fallback]。对照 Mac PPIcon(ref:)。
 */
@Composable
fun RefIcon(
    source: IconSource?,
    modifier: Modifier = Modifier,
    size: Dp = PaperTheme.sizes.icon,
    tint: Color = LocalContentColor.current,
    fallback: String = "puzzle",
    contentDescription: String? = null,
) {
    when (source) {
        null -> PaperIcon(fallback, modifier, size = size, tint = tint, contentDescription = contentDescription)
        is IconSource.Builtin -> PaperIcon(source.name, modifier, size = size, tint = tint, contentDescription = contentDescription)
        is IconSource.Plugin -> {
            val cache = AppModel.pluginIcons
            val bytes = cache.icons[source.key]
            LaunchedEffect(source.key) { cache.load(source) }
            if (bytes != null && bytes.isNotEmpty()) {
                // 插件图标按单色模板着色（和内置图标一致）
                SvgImage(bytes, modifier, size = size, tint = tint, contentDescription = contentDescription)
            } else {
                Box(modifier.size(size)) { PaperIcon(fallback, size = size, tint = tint, contentDescription = contentDescription) }
            }
        }
    }
}
