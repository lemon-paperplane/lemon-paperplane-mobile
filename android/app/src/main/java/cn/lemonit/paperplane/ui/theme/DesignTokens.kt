package cn.lemonit.paperplane.ui.theme

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * shared/design-tokens.json（三端共用的设计变量），对照 Mac PaperplaneKit/Design/DesignTokens.swift。
 * 第一次用到时从 assets 同步读一次并缓存（文件约 10 KB）。读不到时各处用代码里的默认值（和 json 当前值一致）。
 */
class DesignTokens(
    val typography: Map<String, TextSpec>,
    val spacing: Map<String, Double>,
    val radius: Map<String, Double>,
    val size: Map<String, Double>,
    val icon: IconSpec,
    val shadow: Map<String, ShadowSpec>,
    val motion: Map<String, Double>,
    /** light / dark → 颜色名 → #RRGGBB 或 #RRGGBBAA（CSS 顺序，透明度在最后） */
    val color: Map<String, Map<String, String>>,
    /** light / dark → 16 个 ANSI 颜色 */
    val ansi: Map<String, List<String>>,
) {
    data class TextSpec(val size: Double, val weight: Int, val lineHeight: Double)
    data class StrokeStep(val maxSize: Double, val scale: Double)
    data class ShadowSpec(val x: Double, val y: Double, val blur: Double, val opacity: Double)

    data class IconSpec(
        val size: Double = 16.0,
        val sizeSmall: Double = 14.0,
        val sizeLarge: Double = 20.0,
        val sizeEmpty: Double = 32.0,
        val strokeScale: List<StrokeStep> = listOf(StrokeStep(16.0, 0.75), StrokeStep(20.0, 0.875), StrokeStep(9999.0, 1.0)),
    ) {
        /** 按显示尺寸取线宽系数（Lucide 的 2 单位线宽在小尺寸下显得粗，和 Mac 一样按表缩放） */
        fun strokeScale(forSize: Double): Double {
            for (s in strokeScale.sortedBy { it.maxSize }) if (forSize <= s.maxSize) return s.scale
            return 1.0
        }
    }

    fun spacing(key: String, fallback: Double): Double = spacing[key] ?: fallback
    fun radius(key: String, fallback: Double): Double = radius[key] ?: fallback
    fun size(key: String, fallback: Double): Double = size[key] ?: fallback
    fun motion(key: String, fallback: Double): Double = motion[key] ?: fallback

    companion object {
        @Volatile
        private var cached: DesignTokens? = null

        /** 已加载的设计变量；还没加载时尝试用应用 Context 读一次，读不到返回空表（全部走默认值）。 */
        val current: DesignTokens
            get() = cached ?: synchronized(this) {
                cached ?: (SharedAssets.text("design-tokens.json")?.let { runCatching { parse(it) }.getOrNull() })
                    ?.also { cached = it }
                ?: EMPTY
            }

        /** 用指定 Context 加载（PaperplaneTheme 调用）。 */
        fun get(context: Context): DesignTokens {
            cached?.let { return it }
            SharedAssets.attach(context)
            return current
        }

        /** 测试用：直接给 json 文本。 */
        fun install(json: String) {
            cached = parse(json)
        }

        val EMPTY = DesignTokens(emptyMap(), emptyMap(), emptyMap(), emptyMap(), IconSpec(), emptyMap(), emptyMap(), emptyMap(), emptyMap())

        fun parse(text: String): DesignTokens {
            val root = Json.parseToJsonElement(text) as JsonObject
            fun obj(e: JsonElement?) = e as? JsonObject ?: JsonObject(emptyMap())
            fun num(e: JsonElement?) = (e as? JsonPrimitive)?.doubleOrNull
            fun numbers(e: JsonElement?) = obj(e).mapNotNull { (k, v) -> num(v)?.let { k to it } }.toMap()

            val typography = obj(root["typography"]).mapNotNull { (k, v) ->
                val o = obj(v)
                val size = num(o["size"]) ?: return@mapNotNull null
                k to TextSpec(size, (o["weight"] as? JsonPrimitive)?.intOrNull ?: 400, num(o["lineHeight"]) ?: size * 1.35)
            }.toMap()
            val iconObj = obj(root["icon"])
            val steps = (iconObj["strokeScale"] as? JsonArray)?.mapNotNull { s ->
                val o = obj(s)
                val m = num(o["maxSize"]) ?: return@mapNotNull null
                StrokeStep(m, num(o["scale"]) ?: 1.0)
            }
            val defaults = IconSpec()
            val icon = IconSpec(
                size = num(iconObj["size"]) ?: defaults.size,
                sizeSmall = num(iconObj["sizeSmall"]) ?: defaults.sizeSmall,
                sizeLarge = num(iconObj["sizeLarge"]) ?: defaults.sizeLarge,
                sizeEmpty = num(iconObj["sizeEmpty"]) ?: defaults.sizeEmpty,
                strokeScale = steps?.takeIf { it.isNotEmpty() } ?: defaults.strokeScale,
            )
            val shadow = obj(root["shadow"]).mapValues { (_, v) ->
                val o = obj(v)
                ShadowSpec(num(o["x"]) ?: 0.0, num(o["y"]) ?: 0.0, num(o["blur"]) ?: 0.0, num(o["opacity"]) ?: 0.0)
            }
            val color = obj(root["color"]).mapValues { (_, v) ->
                obj(v).mapNotNull { (k, c) -> (c as? JsonPrimitive)?.content?.let { k to it } }.toMap()
            }
            val ansi = obj(root["ansi"]).mapValues { (_, v) ->
                (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
            }
            return DesignTokens(
                typography = typography,
                spacing = numbers(root["spacing"]),
                radius = numbers(root["radius"]),
                size = numbers(root["size"]),
                icon = icon,
                shadow = shadow,
                motion = numbers(root["motion"]),
                color = color,
                ansi = ansi,
            )
        }
    }
}
