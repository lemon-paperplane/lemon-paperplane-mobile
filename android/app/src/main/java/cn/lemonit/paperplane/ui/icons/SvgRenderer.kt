package cn.lemonit.paperplane.ui.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import cn.lemonit.paperplane.ui.theme.DesignTokens
import cn.lemonit.paperplane.ui.theme.PaperSizes
import cn.lemonit.paperplane.ui.theme.SharedAssets
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import java.util.Locale

/**
 * SVG 渲染与缓存（对照 Mac Design/Icons.swift 的 IconStore）。
 *
 * - 单色图标（只用 currentColor / none）当模板图：currentColor 换成白色渲染一次，显示时按 tint 着色（SrcIn），
 *   同一尺寸的位图在各种颜色下共用；彩色图标保留原色、不着色。
 * - 线宽按显示尺寸乘系数（设计变量 icon.strokeScale：小图标 0.75，让 Lucide 的 2 单位线宽不显粗）。
 * - 解析结果按图标名 / 字节内容缓存；位图按「来源 + 像素尺寸 + 线宽系数」放 LRU（约 8 MB）。
 * 只在主线程调用（AndroidSVG 的 SVG 对象不是线程安全的）。
 */
object SvgRenderer {
    private const val TAG = "SvgRenderer"

    /** 解析后的文档 + 是否单色 */
    class Doc(val svg: SVG, val monochrome: Boolean) {
        /** 文档宽高比（宽 / 高），没有 viewBox 时为 1 */
        val aspect: Float
            get() = svg.documentViewBox?.let { if (it.height() > 0f) it.width() / it.height() else 1f } ?: 1f
    }

    private val docs = HashMap<String, Doc?>()
    private val bitmaps = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private val warned = HashSet<String>()

    /** 内置图标（assets/shared/icons/<name>.svg） */
    fun builtin(name: String, strokeScale: Float): Doc? = doc("i:$name@$strokeScale") {
        SharedAssets.text("icons/$name.svg") ?: run {
            if (warned.add(name)) Log.w(TAG, "没有这个图标：$name")
            null
        }
    }

    /** 品牌图形（assets/shared/branding/<name>.svg） */
    fun branding(name: String): Doc? = doc("b:$name") { SharedAssets.text("branding/$name.svg") }

    /** 调用方给的字节（插件图标等）。同样的内容只解析一次。 */
    fun bytes(data: ByteArray, strokeScale: Float = 1f): Doc? =
        doc("d:${data.contentHashCode()}:${data.size}@$strokeScale") { data.toString(Charsets.UTF_8) }

    /** 渲染成位图（px × px 或按宽高给定）；单色图标渲染成白色，显示时再着色。 */
    fun bitmap(doc: Doc, cacheKey: String, widthPx: Int, heightPx: Int): ImageBitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val key = "$cacheKey#${widthPx}x$heightPx"
        bitmaps.get(key)?.let { return it }
        return try {
            val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            doc.svg.renderToCanvas(Canvas(bmp), RenderOptions().viewPort(0f, 0f, widthPx.toFloat(), heightPx.toFloat()))
            bmp.asImageBitmap().also { bitmaps.put(key, it) }
        } catch (e: Exception) {
            Log.w(TAG, "SVG 渲染失败：$cacheKey", e)
            null
        }
    }

    /**
     * 线宽系数：手机上图标尺寸是设计变量 × 1.25（见 PaperSizes），按换算回的设计尺寸查 icon.strokeScale，
     * 线宽占图标的比例和桌面一致（20dp 图标 = 桌面 16pt，线宽 0.75）。
     */
    fun strokeScale(sizeDp: Float): Float =
        DesignTokens.current.icon.strokeScale(sizeDp / PaperSizes.ICON_SCALE).toFloat()

    /** 清掉调用方字节的缓存（插件更新后） */
    fun clearDataIcons() {
        docs.keys.removeAll { it.startsWith("d:") }
        bitmaps.snapshot().keys.filter { it.startsWith("d:") }.forEach { bitmaps.remove(it) }
    }

    private inline fun doc(key: String, source: () -> String?): Doc? {
        if (docs.containsKey(key)) return docs[key]
        val text = source()
        val d = text?.let { parse(it, key.substringAfterLast('@', "1").toFloatOrNull() ?: 1f) }
        docs[key] = d
        return d
    }

    private fun parse(text: String, strokeScale: Float): Doc? = try {
        val mono = isMonochrome(text)
        var t = if (mono) text.replace("currentColor", "#FFFFFF") else text
        if (strokeScale != 1f) t = scaleStrokes(t, strokeScale)
        val svg = SVG.getFromString(t)
        // 文档自带的 width / height（Lucide 是 24，品牌图形 1024）会让 AndroidSVG 按原尺寸画在左上角；
        // 改成 100% 后按 viewBox 缩放铺满给定的视口
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        Doc(svg, mono)
    } catch (e: Exception) {
        Log.w(TAG, "SVG 解析失败", e)
        null
    }

    private val paintRe = Regex("""(?:fill|stroke)\s*(?:=\s*["']|:\s*)([^"';>]+)""")
    private val strokeAttrRe = Regex("""stroke-width\s*=\s*(["'])([0-9.]+)\1""")
    private val strokeCssRe = Regex("""stroke-width\s*:\s*([0-9.]+)""")

    /** 只用 currentColor / none 着色（可以当模板图随主题着色），对照 Mac SVGDocument.isMonochrome */
    fun isMonochrome(text: String): Boolean = paintRe.findAll(text).all { m ->
        when (m.groupValues[1].trim().lowercase(Locale.ROOT)) {
            "none", "currentcolor", "transparent", "inherit" -> true
            else -> false
        }
    }

    internal fun scaleStrokes(text: String, scale: Float): String {
        fun fmt(v: Double) = String.format(Locale.ROOT, "%.3f", v * scale).trimEnd('0').trimEnd('.')
        return text
            .replace(strokeAttrRe) { m -> "stroke-width=${m.groupValues[1]}${fmt(m.groupValues[2].toDouble())}${m.groupValues[1]}" }
            .replace(strokeCssRe) { m -> "stroke-width:${fmt(m.groupValues[1].toDouble())}" }
    }
}
