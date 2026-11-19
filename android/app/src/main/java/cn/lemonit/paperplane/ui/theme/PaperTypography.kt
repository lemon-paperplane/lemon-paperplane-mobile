package cn.lemonit.paperplane.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 字号层级（design-tokens.json 的 typography，名字与 Mac PPText 一致）。
 *
 * 手机上整体放大：设计变量是桌面尺寸（正文 13pt，看屏距离约 60 cm），手机拿在手里约 30 cm，
 * 正文 13sp 偏小。界面字号统一乘 [UI_SCALE]（正文 13 → 15sp），等宽字乘 [MONO_SCALE]（12 → 13sp），
 * 结果取到 0.5sp；层级之间的比例不变。
 */
@Immutable
class PaperTypography(
    val caption: TextStyle,
    val small: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val label: TextStyle,
    val title: TextStyle,
    val heading: TextStyle,
    val display: TextStyle,
    val stat: TextStyle,
    val mono: TextStyle,
    val monoSmall: TextStyle,
    /** 代码等宽字体（系统等宽字体；Mac 优先 JetBrains Mono，手机上没有内置） */
    val monoFamily: FontFamily,
) {
    companion object {
        const val UI_SCALE = 15.0 / 13.0
        const val MONO_SCALE = 13.0 / 12.0

        private val defaults = mapOf(
            "caption" to DesignTokens.TextSpec(11.0, 400, 14.0),
            "small" to DesignTokens.TextSpec(12.0, 400, 16.0),
            "body" to DesignTokens.TextSpec(13.0, 400, 18.0),
            "bodyStrong" to DesignTokens.TextSpec(13.0, 600, 18.0),
            "label" to DesignTokens.TextSpec(12.0, 500, 16.0),
            "title" to DesignTokens.TextSpec(14.0, 600, 20.0),
            "heading" to DesignTokens.TextSpec(16.0, 600, 22.0),
            "display" to DesignTokens.TextSpec(20.0, 600, 26.0),
            "stat" to DesignTokens.TextSpec(22.0, 600, 28.0),
            "mono" to DesignTokens.TextSpec(12.0, 400, 17.0),
            "monoSmall" to DesignTokens.TextSpec(11.0, 400, 15.0),
        )

        fun weight(w: Int): FontWeight = when {
            w < 350 -> FontWeight.Light
            w < 450 -> FontWeight.Normal
            w < 550 -> FontWeight.Medium
            w < 650 -> FontWeight.SemiBold
            else -> FontWeight.Bold
        }

        private fun half(v: Double): Double = (v * 2).roundToInt() / 2.0

        fun from(tokens: DesignTokens): PaperTypography {
            val mono = FontFamily.Monospace
            fun style(name: String): TextStyle {
                val spec = tokens.typography[name] ?: defaults.getValue(name)
                val isMono = name == "mono" || name == "monoSmall"
                val scale = if (isMono) MONO_SCALE else UI_SCALE
                return TextStyle(
                    fontFamily = if (isMono) mono else FontFamily.Default,
                    fontSize = half(spec.size * scale).sp,
                    fontWeight = weight(spec.weight),
                    lineHeight = half(spec.lineHeight * scale).sp,
                    // 行高居中、不额外留字体内边距：各端控件里文字垂直位置一致
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                )
            }
            return PaperTypography(
                caption = style("caption"),
                small = style("small"),
                body = style("body"),
                bodyStrong = style("bodyStrong"),
                label = style("label"),
                title = style("title"),
                heading = style("heading"),
                display = style("display"),
                stat = style("stat"),
                mono = style("mono"),
                monoSmall = style("monoSmall"),
                monoFamily = mono,
            )
        }
    }
}
