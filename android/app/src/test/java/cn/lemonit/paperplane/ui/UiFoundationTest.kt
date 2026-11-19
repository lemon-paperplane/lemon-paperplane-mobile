package cn.lemonit.paperplane.ui

import androidx.compose.ui.graphics.Color
import cn.lemonit.paperplane.ui.i18n.I18n
import cn.lemonit.paperplane.ui.icons.SvgRenderer
import cn.lemonit.paperplane.ui.theme.DesignTokens
import cn.lemonit.paperplane.ui.theme.PaperColors
import cn.lemonit.paperplane.ui.theme.parseHexColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 主题 / 文案 / 图标的纯逻辑部分（读构建时拷进来的 shared 资源，不需要 Android 运行时） */
class UiFoundationTest {
    private val shared = File("build/generated/paperplane-assets/shared")

    @Test
    fun tokensHaveEveryColorInBothThemes() {
        val t = DesignTokens.parse(File(shared, "design-tokens.json").readText())
        for (theme in listOf("light", "dark")) {
            val m = t.color.getValue(theme)
            val missing = PaperColors.names.filter { it !in m }
            assertTrue("$theme 缺颜色：$missing", missing.isEmpty())
            assertEquals(16, t.ansi.getValue(theme).size)
        }
        assertEquals(13.0, t.typography.getValue("body").size, 0.0)
        assertEquals(0.75, t.icon.strokeScale(16.0), 0.0)
        assertEquals(0.875, t.icon.strokeScale(18.0), 0.0)
        assertEquals(1.0, t.icon.strokeScale(40.0), 0.0)
    }

    @Test
    fun hexColorsUseCssOrder() {
        assertEquals(Color(0xFF3574F0), parseHexColor("#3574F0"))
        // #RRGGBBAA：透明度在最后
        assertEquals(Color(0x4D3574F0), parseHexColor("#3574F04D"))
        assertEquals(Color(0xFFFFFFFF), parseHexColor("#fff"))
        assertEquals(null, parseHexColor("nope"))
    }

    @Test
    fun i18nFormatAndResolve() {
        assertEquals("3 处改动", I18n.format("{0} 处改动", listOf("3")))
        assertEquals("a-b-a", I18n.format("{0}-{1}-{0}", listOf("a", "b")))
        assertEquals(I18n.ZH, I18n.resolve("system", listOf("zh-Hant-TW", "en-US")))
        assertEquals(I18n.EN, I18n.resolve("system", listOf("ja-JP", "en-GB")))
        assertEquals(I18n.EN, I18n.resolve("system", listOf("ja-JP")))
        assertEquals(I18n.EN, I18n.resolve("en", listOf("zh-CN")))
    }

    @Test
    fun i18nFallsBackToEnglishThenKey() {
        I18n.install("zh-Hans", mapOf("a" to "甲"), mapOf("a" to "A", "b" to "B"))
        assertEquals("甲", I18n.t("a"))
        assertEquals("B", I18n.t("b"))
        assertEquals("missing.key", I18n.t("missing.key"))
    }

    @Test
    fun androidOverlayKeysMatchAndExist() {
        val zh = I18n.parseTable(File(shared, "i18n/android/zh-Hans.json").readText())
        val en = I18n.parseTable(File(shared, "i18n/android/en.json").readText())
        assertEquals("android 覆盖层两种语言的键要一致", zh.keys, en.keys)
    }

    @Test
    fun builtinIconsAreMonochrome() {
        val icons = File(shared, "icons").listFiles { f -> f.name.endsWith(".svg") }!!
        assertTrue(icons.size > 150)
        val colored = icons.filterNot { SvgRenderer.isMonochrome(it.readText()) }.map { it.name }
        assertTrue("这些内置图标不是单色：$colored", colored.isEmpty())
        assertFalse(SvgRenderer.isMonochrome(File(shared, "branding/app-icon.svg").readText()))
        assertTrue(SvgRenderer.isMonochrome(File(shared, "branding/menubar.svg").readText()))
    }

    @Test
    fun strokeWidthScaling() {
        val svg = """<svg stroke-width="2"><path style="stroke-width: 1.5" d="M0 0"/></svg>"""
        val out = SvgRenderer.scaleStrokes(svg, 0.75f)
        assertTrue(out, out.contains("stroke-width=\"1.5\""))
        assertTrue(out, out.contains("stroke-width:1.125"))
    }
}
