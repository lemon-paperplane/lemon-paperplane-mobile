package cn.lemonit.paperplane.ui.i18n

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import cn.lemonit.paperplane.ui.theme.SharedAssets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 界面文案。键和桌面共用：先读 shared/i18n/<语言>.json，再用 shared/i18n/android/<语言>.json 的同名键覆盖
 * （手机专属说法，规则同 shared/i18n/windows/）。占位符 {0}{1}…。
 *
 * 查找顺序（对照 Mac Localizer）：当前语言 → 英文 → 键本身。
 * 语言设置：system（跟随系统：系统首选语言是任意中文用 zh-Hans，否则 en）| zh-Hans | en，存在本机。
 */
object I18n {
    const val SYSTEM = "system"
    const val ZH = "zh-Hans"
    const val EN = "en"
    val supported = listOf(ZH, EN)

    private const val PREFS = "paperplane_ui"
    private const val KEY = "locale"

    private class Tables(val locale: String, val table: Map<String, String>, val fallback: Map<String, String>)

    @Volatile
    private var tables: Tables? = null

    private val _setting = MutableStateFlow(SYSTEM)
    private val _locale = MutableStateFlow(EN)
    private val _localeState = mutableStateOf(EN)
    private var settingLoaded = false

    /** 设置值：system | zh-Hans | en */
    val setting: StateFlow<String> get() = _setting.asStateFlow()

    /** 生效语言：zh-Hans | en */
    val locale: StateFlow<String> get() = _locale.asStateFlow()

    /** 生效语言的 Compose 状态：tr() 每次都读它，切换语言后用到文案的界面自动重组 */
    val localeState: State<String> get() = _localeState

    /** 记住 Context（PaperplaneTheme 调用；非界面代码第一次 tr() 时也会自己取应用 Context） */
    fun attach(context: Context) {
        SharedAssets.attach(context)
        ensureLoaded()
    }

    /** 切换语言：system | zh-Hans | en。持久化，立即生效。 */
    fun setSetting(value: String) {
        val s = if (value in supported) value else SYSTEM
        prefs()?.edit()?.putString(KEY, s)?.apply()
        _setting.value = s
        settingLoaded = true
        apply(resolve(s))
    }

    /** 跟随系统时，系统语言变化后调用（PaperplaneTheme 在配置变化时自动调） */
    fun refreshSystemLocale() {
        ensureLoaded()
        if (_setting.value == SYSTEM) apply(resolve(SYSTEM))
    }

    /** 取文案。args 依次替换 {0}{1}… */
    fun t(key: String, args: List<String> = emptyList()): String {
        _localeState.value // 组合中调用时登记依赖
        val t = tables ?: ensureLoaded()
        val s = t?.table?.get(key) ?: t?.fallback?.get(key)
        return format(s ?: key, args)
    }

    fun has(key: String): Boolean {
        val t = tables ?: ensureLoaded()
        return t != null && (t.table.containsKey(key) || t.fallback.containsKey(key))
    }

    /** 当前语言的全部键（测试、检查覆盖层用） */
    val keys: Set<String> get() = (tables ?: ensureLoaded())?.table?.keys ?: emptySet()

    /** 测试用：直接给表 */
    fun install(locale: String, table: Map<String, String>, fallback: Map<String, String> = emptyMap()) {
        tables = Tables(locale, table, fallback)
        _locale.value = locale
        _localeState.value = locale
        settingLoaded = true
    }

    fun format(s: String, args: List<String>): String {
        if (args.isEmpty() || !s.contains('{')) return s
        var out = s
        args.forEachIndexed { i, a -> out = out.replace("{$i}", a) }
        return out
    }

    /** 设置值 → 实际语言。system 时看系统首选语言列表：第一个中文或英文决定，都没有用英文。 */
    fun resolve(setting: String?, preferred: List<String> = systemLanguages()): String {
        if (setting != null && setting in supported) return setting
        for (lang in preferred) {
            if (lang.startsWith("zh")) return ZH
            if (lang.startsWith("en")) return EN
        }
        return EN
    }

    private fun systemLanguages(): List<String> {
        val list = Resources.getSystem().configuration.locales
        return (0 until list.size()).map { list[it].toLanguageTag() }
    }

    @Synchronized
    private fun ensureLoaded(): Tables? {
        if (!settingLoaded) {
            val p = prefs() ?: return tables
            settingLoaded = true
            _setting.value = p.getString(KEY, SYSTEM)?.takeIf { it in supported } ?: SYSTEM
        }
        val want = resolve(_setting.value)
        val cur = tables
        if (cur != null && cur.locale == want) return cur
        return load(want)?.also { publish(it) } ?: cur
    }

    private fun apply(locale: String) {
        if (tables?.locale == locale) return
        load(locale)?.let { publish(it) }
    }

    private fun publish(t: Tables) {
        tables = t
        _locale.value = t.locale
        _localeState.value = t.locale
    }

    private fun load(locale: String): Tables? {
        val en = readTable("i18n/$EN.json") ?: return null
        val enAll = en + (readTable("i18n/android/$EN.json") ?: emptyMap())
        if (locale == EN) return Tables(EN, enAll, enAll)
        val base = readTable("i18n/$locale.json") ?: emptyMap()
        val table = base + (readTable("i18n/android/$locale.json") ?: emptyMap())
        return Tables(locale, table, enAll)
    }

    private fun readTable(path: String): Map<String, String>? {
        val text = SharedAssets.text(path) ?: return null
        return parseTable(text)
    }

    /** 扁平 JSON 对象 → 键值表（非字符串值忽略） */
    fun parseTable(text: String): Map<String, String> = runCatching {
        (Json.parseToJsonElement(text) as JsonObject).mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun prefs() = SharedAssets.context()?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * 取文案：`tr("common.cancel")`、`tr("card.changes", 3)` 替换 {0}{1}…。
 * 在 Composable 里和普通代码里都能用；在 Composable 里调用时切换语言会自动重组。
 * 注意别把结果存进不带语言做 key 的 remember 里。
 */
fun tr(key: String, vararg args: Any?): String =
    I18n.t(key, if (args.isEmpty()) emptyList() else args.map { it?.toString() ?: "" })
