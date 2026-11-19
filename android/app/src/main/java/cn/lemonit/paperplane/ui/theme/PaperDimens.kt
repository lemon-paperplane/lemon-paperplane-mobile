package cn.lemonit.paperplane.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 间距（design-tokens.json 的 spacing，原值，单位 dp） */
@Immutable
data class PaperSpacing(
    val xxs: Dp, val xs: Dp, val sm: Dp, val md: Dp, val lg: Dp, val xl: Dp, val xxl: Dp, val xxxl: Dp, val huge: Dp,
) {
    companion object {
        fun from(t: DesignTokens) = PaperSpacing(
            xxs = t.spacing("xxs", 2.0).dp, xs = t.spacing("xs", 4.0).dp, sm = t.spacing("sm", 6.0).dp,
            md = t.spacing("md", 8.0).dp, lg = t.spacing("lg", 12.0).dp, xl = t.spacing("xl", 16.0).dp,
            xxl = t.spacing("xxl", 20.0).dp, xxxl = t.spacing("xxxl", 24.0).dp, huge = t.spacing("huge", 32.0).dp,
        )
    }
}

/** 圆角（design-tokens.json 的 radius，原值） */
@Immutable
data class PaperRadius(val xs: Dp, val sm: Dp, val md: Dp, val lg: Dp, val xl: Dp, val pill: Dp) {
    companion object {
        fun from(t: DesignTokens) = PaperRadius(
            xs = t.radius("xs", 3.0).dp, sm = t.radius("sm", 4.0).dp, md = t.radius("md", 6.0).dp,
            lg = t.radius("lg", 8.0).dp, xl = t.radius("xl", 12.0).dp, pill = t.radius("pill", 999.0).dp,
        )
    }
}

/**
 * 控件尺寸。设计变量里的控件高度（28/24）是鼠标用的，手机上按触控改：
 * 可点的东西至少 [touchTarget]（44dp）；小号控件视觉上 32dp，点击范围由 Compose 自动扩到 48dp。
 * 图标尺寸 = 设计变量 × 1.25（16 → 20，14 → 18，20 → 25，32 → 40），线宽按显示尺寸查 strokeScale 表。
 * 其余（状态点、描边、弹出层宽度等）取设计变量原值。
 */
@Immutable
data class PaperSizes(
    val touchTarget: Dp = 44.dp,
    /** 常规按钮、输入框、下拉的高度 */
    val controlHeight: Dp = 44.dp,
    /** 小号按钮、分段控件的高度 */
    val controlHeightSmall: Dp = 32.dp,
    /** 列表行最小高度（单行）；带副标题时随内容变高 */
    val rowHeight: Dp = 48.dp,
    val iconButton: Dp = 44.dp,
    val iconButtonSmall: Dp = 32.dp,
    val icon: Dp = 20.dp,
    val iconSmall: Dp = 18.dp,
    val iconLarge: Dp = 25.dp,
    val iconEmpty: Dp = 40.dp,
    val statusDot: Dp = 8.dp,
    val border: Dp = 1.dp,
    val focusRing: Dp = 2.dp,
    /** i 说明气泡、菜单的最大宽度（还会受屏幕宽度限制） */
    val popoverMaxWidth: Dp = 420.dp,
    /** 底部弹出面板在平板上的最大宽度 */
    val sheetMaxWidth: Dp = 640.dp,
) {
    companion object {
        const val ICON_SCALE = 1.25

        fun from(t: DesignTokens): PaperSizes {
            fun icon(v: Double) = (v * ICON_SCALE).roundToInt().dp
            return PaperSizes(
                icon = icon(t.icon.size),
                iconSmall = icon(t.icon.sizeSmall),
                iconLarge = icon(t.icon.sizeLarge),
                iconEmpty = icon(t.icon.sizeEmpty),
                statusDot = t.size("statusDot", 8.0).dp,
                border = t.size("borderWidth", 1.0).dp,
                focusRing = t.size("focusRingWidth", 2.0).dp,
                popoverMaxWidth = t.size("popoverMaxWidth", 420.0).dp,
                sheetMaxWidth = t.size("dialogWidthLarge", 640.0).dp,
            )
        }
    }
}

/** 动效时长（design-tokens.json 的 motion） */
@Immutable
data class PaperMotion(
    val fast: Int,
    val normal: Int,
    val slow: Int,
    /** 普通提示条停留时长（毫秒） */
    val toastMillis: Long,
    /** 警告 / 错误提示条停留时长（毫秒） */
    val toastErrorMillis: Long,
) {
    companion object {
        fun from(t: DesignTokens) = PaperMotion(
            fast = t.motion("fast", 100.0).toInt(),
            normal = t.motion("normal", 160.0).toInt(),
            slow = t.motion("slow", 240.0).toInt(),
            toastMillis = (t.motion("toastSeconds", 4.0) * 1000).toLong(),
            toastErrorMillis = (t.motion("toastErrorSeconds", 8.0) * 1000).toLong(),
        )
    }
}
