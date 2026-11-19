package cn.lemonit.paperplane.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import cn.lemonit.paperplane.ui.i18n.I18n
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 深浅色：跟随系统 / 浅色 / 深色（key 与 Mac 设置值一致：system | light | dark） */
enum class ThemeMode(val key: String) {
    System("system"), Light("light"), Dark("dark");

    companion object {
        fun of(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: System
    }
}

/** 界面主题设置（存在本机，和 Mac 一样连远程服务器时也跟着本机走）。设置页调 [set]，界面即时切换。 */
object Appearance {
    private const val PREFS = "paperplane_ui"
    private const val KEY = "theme"
    private val _mode = MutableStateFlow(ThemeMode.System)
    private var loaded = false

    val mode: StateFlow<ThemeMode>
        get() {
            ensureLoaded()
            return _mode.asStateFlow()
        }

    fun set(mode: ThemeMode) {
        ensureLoaded()
        prefs()?.edit()?.putString(KEY, mode.key)?.apply()
        _mode.value = mode
    }

    @Synchronized
    internal fun ensureLoaded() {
        if (loaded) return
        val p = prefs() ?: return
        loaded = true
        _mode.value = ThemeMode.of(p.getString(KEY, null))
    }

    private fun prefs() = SharedAssets.context()?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** 一套主题的全部取值（颜色以外深浅两套共用） */
@androidx.compose.runtime.Immutable
class PaperThemeValues(
    val colors: PaperColors,
    val typography: PaperTypography,
    val spacing: PaperSpacing,
    val radius: PaperRadius,
    val sizes: PaperSizes,
    val motion: PaperMotion,
) {
    val isDark: Boolean get() = colors.isDark

    companion object {
        @Volatile
        private var cache: Pair<DesignTokens, Pair<PaperThemeValues, PaperThemeValues>>? = null

        /** 由设计变量算出浅色、深色两套（按设计变量对象缓存） */
        fun of(tokens: DesignTokens, dark: Boolean): PaperThemeValues {
            val c = cache?.takeIf { it.first === tokens }?.second ?: run {
                val type = PaperTypography.from(tokens)
                val spacing = PaperSpacing.from(tokens)
                val radius = PaperRadius.from(tokens)
                val sizes = PaperSizes.from(tokens)
                val motion = PaperMotion.from(tokens)
                val pair = PaperThemeValues(PaperColors.from(tokens, false), type, spacing, radius, sizes, motion) to
                    PaperThemeValues(PaperColors.from(tokens, true), type, spacing, radius, sizes, motion)
                cache = tokens to pair
                pair
            }
            return if (dark) c.second else c.first
        }
    }
}

val LocalPaperTheme = staticCompositionLocalOf<PaperThemeValues> {
    error("没有包在 PaperplaneTheme { } 里")
}

/** 取当前主题：`PaperTheme.colors.textSecondary`、`PaperTheme.typography.body`、`PaperTheme.spacing.md` */
object PaperTheme {
    val colors: PaperColors
        @Composable @ReadOnlyComposable get() = LocalPaperTheme.current.colors
    val typography: PaperTypography
        @Composable @ReadOnlyComposable get() = LocalPaperTheme.current.typography
    val spacing: PaperSpacing
        @Composable @ReadOnlyComposable get() = LocalPaperTheme.current.spacing
    val radius: PaperRadius
        @Composable @ReadOnlyComposable get() = LocalPaperTheme.current.radius
    val sizes: PaperSizes
        @Composable @ReadOnlyComposable get() = LocalPaperTheme.current.sizes
    val motion: PaperMotion
        @Composable @ReadOnlyComposable get() = LocalPaperTheme.current.motion
    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalPaperTheme.current.isDark
}

/**
 * 所有界面的根：注入我们的颜色角色 / 字号 / 间距 / 圆角，同时把 Material3 的 colorScheme、typography
 * 映射好（偶尔用到的 ModalBottomSheet、DropdownMenu 不突兀），默认文字样式、前景色、按下反馈、选中文字颜色，
 * 并让状态栏 / 导航栏图标颜色跟着深浅色走。
 *
 * @param mode 为 null 时用 [Appearance] 里存的设置（默认跟随系统）；传值则强制深 / 浅（陈列页、截图测试用）。
 * @param systemBars 是否按深浅色设置状态栏 / 导航栏图标颜色。页面里嵌套一小块别的主题（如深浅对照）时传 false。
 */
@Composable
fun PaperplaneTheme(mode: ThemeMode? = null, systemBars: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val tokens = remember(context) { DesignTokens.get(context) }
    I18n.attach(context)
    Appearance.ensureLoaded()
    val saved by Appearance.mode.collectAsState()
    val effective = mode ?: saved
    val dark = when (effective) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val values = PaperThemeValues.of(tokens, dark)
    val c = values.colors

    // 「跟随系统」语言时，系统语言变化后重新选文案
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration.locales) { I18n.refreshSystemLocale() }
    // 读一次语言状态：切换语言后整棵树里用到 tr() 的地方都会重组
    I18n.localeState.value

    if (systemBars) SystemBarsAppearance(dark)

    MaterialTheme(colorScheme = materialColors(c), typography = materialTypography(values.typography)) {
        CompositionLocalProvider(
            LocalPaperTheme provides values,
            LocalContentColor provides c.textPrimary,
            LocalTextStyle provides values.typography.body,
            LocalIndication provides PressIndication(c.textPrimary.copy(alpha = if (dark) 0.10f else 0.06f)),
            LocalTextSelectionColors provides TextSelectionColors(handleColor = c.accent, backgroundColor = c.accent.copy(alpha = 0.3f)),
            content = content,
        )
    }
}

@Composable
private fun SystemBarsAppearance(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val activity = view.context.findActivity() ?: return@SideEffect
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun materialColors(c: PaperColors): ColorScheme {
    val base = if (c.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent,
        onPrimary = c.textOnAccent,
        primaryContainer = c.accentSubtle,
        onPrimaryContainer = c.accent,
        inversePrimary = c.accentHover,
        secondary = c.textSecondary,
        onSecondary = c.bgSurface,
        secondaryContainer = c.bgSelected,
        onSecondaryContainer = c.textPrimary,
        tertiary = c.info,
        onTertiary = c.textOnAccent,
        tertiaryContainer = c.infoSubtle,
        onTertiaryContainer = c.info,
        background = c.bgChrome,
        onBackground = c.textPrimary,
        surface = c.bgSurface,
        onSurface = c.textPrimary,
        surfaceVariant = c.bgSubtle,
        onSurfaceVariant = c.textSecondary,
        // 和 surface 相同：Material 的「色调抬升」不再给弹出层染色
        surfaceTint = c.bgSurface,
        inverseSurface = c.textPrimary,
        inverseOnSurface = c.bgSurface,
        error = c.danger,
        onError = c.textOnAccent,
        errorContainer = c.dangerSubtle,
        onErrorContainer = c.danger,
        outline = c.borderStrong,
        outlineVariant = c.border,
        scrim = c.scrim,
        surfaceBright = c.bgElevated,
        surfaceDim = c.bgChrome,
        surfaceContainerLowest = c.bgElevated,
        surfaceContainerLow = c.bgElevated,
        surfaceContainer = c.bgElevated,
        surfaceContainerHigh = c.bgElevated,
        surfaceContainerHighest = c.bgElevated,
    )
}

private fun materialTypography(t: PaperTypography): Typography = Typography(
    displayLarge = t.stat,
    displayMedium = t.stat,
    displaySmall = t.display,
    headlineLarge = t.display,
    headlineMedium = t.display,
    headlineSmall = t.heading,
    titleLarge = t.heading,
    titleMedium = t.title,
    titleSmall = t.bodyStrong,
    bodyLarge = t.body,
    bodyMedium = t.body,
    bodySmall = t.small,
    labelLarge = t.label,
    labelMedium = t.label,
    labelSmall = t.caption,
)

/** 不透明度叠加：在 [base] 上盖一层 [over]（按下、悬停底色等合成用） */
fun Color.over(base: Color): Color {
    val a = alpha + base.alpha * (1 - alpha)
    if (a == 0f) return Color.Transparent
    fun ch(x: Float, y: Float) = (x * alpha + y * base.alpha * (1 - alpha)) / a
    return Color(ch(red, base.red), ch(green, base.green), ch(blue, base.blue), a)
}
