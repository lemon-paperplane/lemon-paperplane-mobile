package cn.lemonit.paperplane.ui.icons

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.ui.theme.PaperTheme
import kotlin.math.roundToInt

/**
 * 内置图标（assets/shared/icons/<name>.svg，和桌面同一套 Lucide 图标）。所有界面图标都走它，不用 Material 图标、不用 emoji。
 *
 * `PaperIcon("play")`、`PaperIcon("trash-2", tint = PaperTheme.colors.danger, size = PaperTheme.sizes.iconSmall)`
 *
 * @param tint 单色图标的颜色，默认跟随当前前景色（LocalContentColor）；彩色图标忽略
 * @param contentDescription 读屏文字；纯装饰图标传 null
 */
@Composable
fun PaperIcon(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = PaperTheme.sizes.icon,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    val scale = SvgRenderer.strokeScale(size.value)
    val doc = remember(name, scale) { SvgRenderer.builtin(name, scale) }
    SvgCanvas(doc, "i:$name@$scale", modifier.size(size), tint, contentDescription)
}

/**
 * 调用方给字节的 SVG（插件图标：字节由调用方经 API 取来并按插件版本缓存）。单色的按 tint 着色，彩色的保留原色。
 *
 * `SvgImage(iconBytes, size = PaperTheme.sizes.iconLarge)`
 */
@Composable
fun SvgImage(
    bytes: ByteArray,
    modifier: Modifier = Modifier,
    size: Dp = PaperTheme.sizes.icon,
    tint: Color? = LocalContentColor.current,
    contentDescription: String? = null,
) {
    val scale = SvgRenderer.strokeScale(size.value)
    val doc = remember(bytes, scale) { SvgRenderer.bytes(bytes, scale) }
    val key = remember(bytes, scale) { "d:${bytes.contentHashCode()}:${bytes.size}@$scale" }
    SvgCanvas(doc, key, modifier.size(size), tint, contentDescription)
}

/**
 * 品牌图形（assets/shared/branding/<name>.svg：app-icon 应用图标、menubar 单色纸飞机）。宽度按图形宽高比。
 *
 * `PaperBrandImage("app-icon", height = 64.dp)`
 */
@Composable
fun PaperBrandImage(
    name: String,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    tint: Color? = LocalContentColor.current,
    contentDescription: String? = null,
) {
    val doc = remember(name) { SvgRenderer.branding(name) }
    val width = height * (doc?.aspect ?: 1f)
    SvgCanvas(doc, "b:$name", modifier.size(width, height), tint, contentDescription)
}

@Composable
private fun SvgCanvas(doc: SvgRenderer.Doc?, key: String, modifier: Modifier, tint: Color?, contentDescription: String?) {
    val sem = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else {
        Modifier
    }
    Spacer(
        modifier.then(sem).drawWithCache {
            val bmp = doc?.let { SvgRenderer.bitmap(it, key, size.width.roundToInt(), size.height.roundToInt()) }
            val filter = if (doc?.monochrome == true && tint != null) ColorFilter.tint(tint) else null
            onDrawBehind { if (bmp != null) drawImage(bmp, colorFilter = filter) }
        },
    )
}
