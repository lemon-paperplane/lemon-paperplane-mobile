package cn.lemonit.paperplane.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.lemonit.paperplane.ui.gallery.GalleryPage
import cn.lemonit.paperplane.ui.gallery.GalleryScreen
import cn.lemonit.paperplane.ui.gallery.GallerySection
import cn.lemonit.paperplane.ui.i18n.I18n
import cn.lemonit.paperplane.ui.theme.PaperplaneTheme
import cn.lemonit.paperplane.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 组件陈列页截图（JVM 上用 Robolectric 渲染，Roborazzi 存 PNG）。
 * 运行：./gradlew testDebugUnitTest --tests '*GalleryScreenshotTest*'
 * 产物：app/build/outputs/roborazzi/gallery_<页>_<light|dark>.png
 * 只是生成图片供目测，不和基准图比较。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h2600dp-xhdpi")
class GalleryScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun page(name: String, mode: ThemeMode, vararg sections: GallerySection, locale: String = I18n.ZH) {
        I18n.setSetting(locale)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PaperplaneTheme(mode = mode) {
                GalleryPage(sections.toList(), Modifier.testTag("page"))
            }
        }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("page").captureRoboImage("build/outputs/roborazzi/gallery_${name}_${mode.key}.png")
    }

    private val p1 = arrayOf(GallerySection.Header, GallerySection.Colors, GallerySection.Typography)
    private val p2 = arrayOf(GallerySection.Buttons, GallerySection.Badges, GallerySection.Cards)
    private val p3 = arrayOf(GallerySection.Groups, GallerySection.Inputs)
    private val p4 = arrayOf(GallerySection.Choices, GallerySection.Sheets, GallerySection.Toasts)
    private val p5 = arrayOf(GallerySection.Menus, GallerySection.Feedback, GallerySection.Alerts, GallerySection.Code)
    private val p6 = arrayOf(GallerySection.Icons)

    @Test fun p1Light() = page("1_basics", ThemeMode.Light, *p1)
    @Test fun p1Dark() = page("1_basics", ThemeMode.Dark, *p1)
    @Test fun p2Light() = page("2_buttons_cards", ThemeMode.Light, *p2)
    @Test fun p2Dark() = page("2_buttons_cards", ThemeMode.Dark, *p2)
    @Test fun p3Light() = page("3_groups_inputs", ThemeMode.Light, *p3)
    @Test fun p3Dark() = page("3_groups_inputs", ThemeMode.Dark, *p3)
    @Test fun p4Light() = page("4_choices_sheets", ThemeMode.Light, *p4)
    @Test fun p4Dark() = page("4_choices_sheets", ThemeMode.Dark, *p4)
    @Test fun p5Light() = page("5_feedback", ThemeMode.Light, *p5)
    @Test fun p5Dark() = page("5_feedback", ThemeMode.Dark, *p5)
    @Test fun p6Light() = page("6_icons", ThemeMode.Light, *p6)
    @Test fun p6Dark() = page("6_icons", ThemeMode.Dark, *p6, locale = I18n.EN)

    /** 整个陈列页在手机屏幕尺寸下的第一屏（英文界面） */
    @Test
    @Config(qualifiers = "w400dp-h860dp-xhdpi")
    fun screenEnglish() {
        I18n.setSetting(I18n.EN)
        compose.mainClock.autoAdvance = false
        compose.setContent { PaperplaneTheme(mode = ThemeMode.Light) { GalleryScreen(Modifier.testTag("screen")) } }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("screen").captureRoboImage("build/outputs/roborazzi/gallery_screen_en_light.png")
    }
}
