package cn.lemonit.paperplane.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 一套主题（浅或深）的颜色角色。名字与 shared/design-tokens.json 的 color.light / color.dark 一一对应
 * （对照 Mac Design/ColorTokens.swift）。缺的颜色显示成品红，一眼能看出来。
 *
 * 界面代码一律用这些角色，不写死颜色值：`PaperTheme.colors.textSecondary`。
 */
@Immutable
class PaperColors(val isDark: Boolean, private val hex: Map<String, String>, ansiHex: List<String>) {
    private fun c(name: String): Color = parseHexColor(hex[name]) ?: Color(0xFFFF00FF)

    val bgChrome: Color = c("bgChrome")
    val bgContent: Color = c("bgContent")
    val bgSurface: Color = c("bgSurface")
    val bgSubtle: Color = c("bgSubtle")
    val bgElevated: Color = c("bgElevated")
    val bgInput: Color = c("bgInput")
    val bgInputDisabled: Color = c("bgInputDisabled")
    val bgHover: Color = c("bgHover")
    val bgPressed: Color = c("bgPressed")
    val bgSelected: Color = c("bgSelected")
    val bgSelectedInactive: Color = c("bgSelectedInactive")
    val bgCode: Color = c("bgCode")
    val border: Color = c("border")
    val borderSubtle: Color = c("borderSubtle")
    val borderStrong: Color = c("borderStrong")
    val borderFocus: Color = c("borderFocus")
    val focusRing: Color = c("focusRing")
    val textPrimary: Color = c("textPrimary")
    val textSecondary: Color = c("textSecondary")
    val textTertiary: Color = c("textTertiary")
    val textDisabled: Color = c("textDisabled")
    val textOnAccent: Color = c("textOnAccent")
    val textLink: Color = c("textLink")
    val iconPrimary: Color = c("iconPrimary")
    val iconSecondary: Color = c("iconSecondary")
    val accent: Color = c("accent")
    val accentHover: Color = c("accentHover")
    val accentPressed: Color = c("accentPressed")
    val accentSubtle: Color = c("accentSubtle")
    val accentSubtleBorder: Color = c("accentSubtleBorder")
    val success: Color = c("success")
    val successSubtle: Color = c("successSubtle")
    val successBorder: Color = c("successBorder")
    val warning: Color = c("warning")
    val warningSubtle: Color = c("warningSubtle")
    val warningBorder: Color = c("warningBorder")
    val danger: Color = c("danger")
    val dangerHover: Color = c("dangerHover")
    val dangerSubtle: Color = c("dangerSubtle")
    val dangerBorder: Color = c("dangerBorder")
    val info: Color = c("info")
    val infoSubtle: Color = c("infoSubtle")
    val infoBorder: Color = c("infoBorder")
    val neutralSubtle: Color = c("neutralSubtle")
    val neutralBorder: Color = c("neutralBorder")
    val statusIdle: Color = c("statusIdle")
    val statusStarting: Color = c("statusStarting")
    val statusRunning: Color = c("statusRunning")
    val statusFailed: Color = c("statusFailed")
    val statusStopping: Color = c("statusStopping")
    val buttonSecondaryBg: Color = c("buttonSecondaryBg")
    val buttonSecondaryHover: Color = c("buttonSecondaryHover")
    val buttonSecondaryBorder: Color = c("buttonSecondaryBorder")
    val switchOff: Color = c("switchOff")
    val scrim: Color = c("scrim")
    val shadow: Color = c("shadow")
    val consoleBg: Color = c("consoleBg")
    val consoleText: Color = c("consoleText")
    val consoleSystem: Color = c("consoleSystem")
    val consoleStderr: Color = c("consoleStderr")
    val consoleSelection: Color = c("consoleSelection")
    val searchMatch: Color = c("searchMatch")
    val searchMatchCurrent: Color = c("searchMatchCurrent")
    val scrollbar: Color = c("scrollbar")
    val graphLane0: Color = c("graphLane0")
    val graphLane1: Color = c("graphLane1")
    val graphLane2: Color = c("graphLane2")
    val graphLane3: Color = c("graphLane3")
    val graphLane4: Color = c("graphLane4")
    val graphLane5: Color = c("graphLane5")
    val graphLane6: Color = c("graphLane6")
    val graphLane7: Color = c("graphLane7")
    val diffAddedBg: Color = c("diffAddedBg")
    val diffAddedText: Color = c("diffAddedText")
    val diffRemovedBg: Color = c("diffRemovedBg")
    val diffRemovedText: Color = c("diffRemovedText")
    val diffHunkBg: Color = c("diffHunkBg")
    val brandLemon: Color = c("brandLemon")

    /** 16 个 ANSI 基础色（控制台用） */
    val ansi: List<Color> = ansiHex.map { parseHexColor(it) ?: Color.Magenta }

    /** 按名字取（插件给的颜色名等）；不认识的名字返回 null */
    operator fun get(name: String): Color? = hex[name]?.let { parseHexColor(it) }

    /** 语气色：前景、浅底、描边（徽标、提示条、插件界面的 tone） */
    fun tone(tone: Tone): ToneColors = when (tone) {
        Tone.Success -> ToneColors(success, successSubtle, successBorder)
        Tone.Warning -> ToneColors(warning, warningSubtle, warningBorder)
        Tone.Danger -> ToneColors(danger, dangerSubtle, dangerBorder)
        Tone.Info -> ToneColors(info, infoSubtle, infoBorder)
        Tone.Neutral -> ToneColors(textSecondary, neutralSubtle, neutralBorder)
    }

    /** 提交图车道颜色（循环取 8 色） */
    fun graphLane(index: Int): Color {
        val all = listOf(graphLane0, graphLane1, graphLane2, graphLane3, graphLane4, graphLane5, graphLane6, graphLane7)
        return all[((index % all.size) + all.size) % all.size]
    }

    companion object {
        /** 设计变量里要求每个主题都有的颜色名（与 Mac DesignTokens.requiredColors 一致） */
        val names: List<String> = listOf(
            "bgChrome", "bgContent", "bgSurface", "bgSubtle", "bgElevated", "bgInput",
            "bgInputDisabled", "bgHover", "bgPressed", "bgSelected", "bgSelectedInactive", "bgCode",
            "border", "borderSubtle", "borderStrong", "borderFocus", "focusRing", "textPrimary",
            "textSecondary", "textTertiary", "textDisabled", "textOnAccent", "textLink", "iconPrimary",
            "iconSecondary", "accent", "accentHover", "accentPressed", "accentSubtle", "accentSubtleBorder",
            "success", "successSubtle", "successBorder", "warning", "warningSubtle", "warningBorder",
            "danger", "dangerHover", "dangerSubtle", "dangerBorder", "info", "infoSubtle",
            "infoBorder", "neutralSubtle", "neutralBorder", "statusIdle", "statusStarting", "statusRunning",
            "statusFailed", "statusStopping", "buttonSecondaryBg", "buttonSecondaryHover", "buttonSecondaryBorder", "switchOff",
            "scrim", "shadow", "consoleBg", "consoleText", "consoleSystem", "consoleStderr",
            "consoleSelection", "searchMatch", "searchMatchCurrent", "scrollbar", "graphLane0", "graphLane1",
            "graphLane2", "graphLane3", "graphLane4", "graphLane5", "graphLane6", "graphLane7",
            "diffAddedBg", "diffAddedText", "diffRemovedBg", "diffRemovedText", "diffHunkBg", "brandLemon",
        )

        fun from(tokens: DesignTokens, dark: Boolean): PaperColors {
            val theme = if (dark) "dark" else "light"
            return PaperColors(dark, tokens.color[theme] ?: emptyMap(), tokens.ansi[theme] ?: emptyList())
        }
    }
}

/** 语气色三件套 */
@Immutable
data class ToneColors(val fg: Color, val bg: Color, val border: Color)

/**
 * 解析 #RGB / #RRGGBB / #RRGGBBAA（CSS 顺序：透明度在最后，和设计变量文件一致；注意和 Android 的 #AARRGGBB 相反）。
 */
fun parseHexColor(s: String?): Color? {
    if (s == null) return null
    var h = s.trim().removePrefix("#")
    if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
    val v = h.toLongOrNull(16) ?: return null
    return when (h.length) {
        6 -> Color(0xFF000000L or v)
        8 -> Color(((v and 0xFF) shl 24) or (v ushr 8))
        else -> null
    }
}
