package cn.lemonit.paperplane.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.ui.theme.DesignTokens

/** 阴影种类（design-tokens.json 的 shadow）：popup 弹出层、dialog 面板、card 卡片、toast 提示条 */
enum class ShadowKind(val key: String) { Popup("popup"), Dialog("dialog"), Card("card"), Toast("toast") }

/**
 * 按设计变量加阴影。Android 的阴影是按高度算的，这里取 blur / 2 作高度、opacity 作颜色透明度，观感接近桌面。
 * 深色主题下阴影几乎看不见，靠描边区分层次（和桌面一样）。
 */
fun Modifier.paperShadow(kind: ShadowKind, shape: Shape): Modifier {
    val s = DesignTokens.current.shadow[kind.key] ?: return this
    val color = Color.Black.copy(alpha = (s.opacity * 2).coerceAtMost(1.0).toFloat())
    return shadow(elevation = (s.blur / 2).dp, shape = shape, clip = false, ambientColor = color, spotColor = color)
}
