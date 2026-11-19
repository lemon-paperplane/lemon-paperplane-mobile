package cn.lemonit.paperplane.kit.ansi

// ANSI 转义解析（对照 Mac PaperplaneKit/ANSI/ANSIParser.swift），输出与界面无关的 span 列表，
// UI 层（ui/components 的 ANSI 文本）再把颜色映射成设计变量的 ansi 调色板并转成 AnnotatedString。
//
// 用法：每个控制台各用一个解析器实例（样式跨行保留，和终端一致）：
//   val parser = ANSIParser()
//   for (line in lines) render(parser.parse(line.text))
// 只要纯文本时用 ANSIParser.strip(text)。

/** 终端颜色：默认 / 调色板序号（0–15 是 16 色，16–255 是 256 色）/ 真彩。 */
sealed interface ANSIColor {
    data object Default : ANSIColor

    /** 0–7 标准色、8–15 亮色（取设计变量的 ansi 数组），16–255 用 ANSIPalette.rgb */
    data class Palette(val index: Int) : ANSIColor

    data class Rgb(val r: Int, val g: Int, val b: Int) : ANSIColor
}

data class ANSIStyle(
    val fg: ANSIColor = ANSIColor.Default,
    val bg: ANSIColor = ANSIColor.Default,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    /** 前景背景互换（UI 层处理） */
    val inverse: Boolean = false,
    val strikethrough: Boolean = false,
) {
    val isPlain: Boolean get() = this == PLAIN

    companion object {
        val PLAIN = ANSIStyle()
    }
}

data class ANSISpan(val text: String, val style: ANSIStyle)

/**
 * 支持 SGR（16/256/真彩、粗体、暗淡、斜体、下划线、反显、删除线），
 * 丢弃光标控制等其他 CSI、OSC（含超链接）、DCS/SOS/PM/APC 以及单字符 ESC 序列。不是线程安全的。
 */
class ANSIParser {
    /** 当前样式（跨行保留） */
    var style: ANSIStyle = ANSIStyle.PLAIN

    fun reset() {
        style = ANSIStyle.PLAIN
    }

    private class Piece(val text: StringBuilder, val style: ANSIStyle)

    /** 解析一行（不含换行）。行内的 \r 视为回到行首：只保留最后一段非空内容（进度条）。 */
    fun parse(line: String): List<ANSISpan> {
        val spans = ArrayList<Piece>()
        val current = StringBuilder()
        var curStyle = style
        var segmentStart = 0 // \r 之后的 spans 起点
        var keptBeforeCR: List<Piece> = emptyList()

        fun flush() {
            if (current.isEmpty()) return
            val last = spans.lastOrNull()
            if (last != null && last.style == curStyle && spans.size > segmentStart) {
                last.text.append(current)
            } else {
                spans.add(Piece(StringBuilder(current), curStyle))
            }
            current.setLength(0)
        }

        val cps = line.codePoints().toArray()
        val n = cps.size
        var i = 0
        while (i < n) {
            val c = cps[i]
            when {
                c == 0x1B -> { // ESC
                    if (i + 1 >= n) {
                        i++
                        continue
                    }
                    val next = cps[i + 1]
                    if (next == 0x5B) { // CSI: ESC [
                        var j = i + 2
                        val params = StringBuilder()
                        var final = 0
                        while (j < n) {
                            val v = cps[j]
                            if (v in 0x40..0x7E) {
                                final = v
                                break
                            }
                            params.appendCodePoint(v)
                            j++
                        }
                        if (final == 0x6D) { // 'm' = SGR
                            flush()
                            applySGR(params.toString())
                            curStyle = style
                        }
                        i = j + 1
                    } else if (next == 0x5D || next == 0x50 || next == 0x58 || next == 0x5E || next == 0x5F) {
                        // OSC / DCS / SOS / PM / APC：到 BEL 或 ESC \ 为止
                        var j = i + 2
                        while (j < n) {
                            if (cps[j] == 0x07) {
                                j++
                                break
                            }
                            if (cps[j] == 0x1B && j + 1 < n && cps[j + 1] == 0x5C) {
                                j += 2
                                break
                            }
                            j++
                        }
                        i = j
                    } else {
                        // ESC + 中间字节(0x20–0x2F)* + 终结字节
                        var j = i + 1
                        while (j < n && cps[j] in 0x20..0x2F) j++
                        i = minOf(j + 1, n)
                    }
                }
                c == 0x0D -> { // \r
                    flush()
                    if (spans.size > segmentStart) keptBeforeCR = spans.subList(segmentStart, spans.size).toList()
                    while (spans.size > segmentStart) spans.removeAt(spans.size - 1)
                    segmentStart = spans.size
                    i++
                }
                c == 0x08 -> { // 退格
                    if (current.isNotEmpty()) {
                        removeLastCodePoint(current)
                    } else if (spans.size > segmentStart && spans.last().text.isNotEmpty()) {
                        removeLastCodePoint(spans.last().text)
                    }
                    i++
                }
                c == 0x09 -> { // tab 保留
                    current.appendCodePoint(c)
                    i++
                }
                c in 0x00..0x1F || c == 0x7F -> i++ // 其他控制字符丢弃
                else -> {
                    current.appendCodePoint(c)
                    i++
                }
            }
        }
        flush()
        // 行尾的 \r（CRLF 残留）不应清空内容
        if (spans.size == segmentStart && keptBeforeCR.isNotEmpty()) spans.addAll(keptBeforeCR)
        return spans.filter { it.text.isNotEmpty() }.map { ANSISpan(it.text.toString(), it.style) }
    }

    private fun removeLastCodePoint(sb: StringBuilder) {
        if (sb.isEmpty()) return
        val len = sb.length
        val cut = if (len >= 2 && Character.isLowSurrogate(sb[len - 1]) && Character.isHighSurrogate(sb[len - 2])) 2 else 1
        sb.setLength(len - cut)
    }

    private fun applySGR(raw: String) {
        // 参数用 ; 分隔，子参数可用 :（如 38:2::r:g:b）
        val groups = if (raw.isEmpty()) listOf("") else raw.split(';')
        fun intAt(k: Int): Int? = groups.getOrNull(k)?.toIntOrNull()
        var s = style
        var idx = 0
        while (idx < groups.size) {
            val g = groups[idx]
            if (':' in g) {
                val sub = g.split(':').map { it.toIntOrNull() }
                val first = sub.firstOrNull()
                if (first != null) {
                    if (first == 38 || first == 48) {
                        extendedColor(sub.drop(1))?.let { color -> s = if (first == 38) s.copy(fg = color) else s.copy(bg = color) }
                    } else if (first == 4) {
                        s = s.copy(underline = (if (sub.size > 1) (sub[1] ?: 1) else 1) != 0)
                    }
                }
                idx++
                continue
            }
            val code = g.toIntOrNull() ?: 0
            when (code) {
                0 -> s = ANSIStyle.PLAIN
                1 -> s = s.copy(bold = true)
                2 -> s = s.copy(dim = true)
                3 -> s = s.copy(italic = true)
                4, 21 -> s = s.copy(underline = true)
                7 -> s = s.copy(inverse = true)
                9 -> s = s.copy(strikethrough = true)
                22 -> s = s.copy(bold = false, dim = false)
                23 -> s = s.copy(italic = false)
                24 -> s = s.copy(underline = false)
                27 -> s = s.copy(inverse = false)
                29 -> s = s.copy(strikethrough = false)
                in 30..37 -> s = s.copy(fg = ANSIColor.Palette(code - 30))
                39 -> s = s.copy(fg = ANSIColor.Default)
                in 40..47 -> s = s.copy(bg = ANSIColor.Palette(code - 40))
                49 -> s = s.copy(bg = ANSIColor.Default)
                in 90..97 -> s = s.copy(fg = ANSIColor.Palette(code - 90 + 8))
                in 100..107 -> s = s.copy(bg = ANSIColor.Palette(code - 100 + 8))
                38, 48 -> {
                    val n5 = intAt(idx + 2)
                    if (intAt(idx + 1) == 5 && n5 != null) {
                        val c = ANSIColor.Palette(clamp(n5))
                        s = if (code == 38) s.copy(fg = c) else s.copy(bg = c)
                        idx += 2
                    } else {
                        val r = intAt(idx + 2)
                        val gg = intAt(idx + 3)
                        val b = intAt(idx + 4)
                        if (intAt(idx + 1) == 2 && r != null && gg != null && b != null) {
                            val c = ANSIColor.Rgb(clamp(r), clamp(gg), clamp(b))
                            s = if (code == 38) s.copy(fg = c) else s.copy(bg = c)
                            idx += 4
                        }
                    }
                }
            }
            idx++
        }
        style = s
    }

    private fun extendedColor(rest: List<Int?>): ANSIColor? {
        val mode = rest.firstOrNull() ?: return null
        if (mode == 5 && rest.size >= 2) {
            val n = rest[1] ?: return null
            return ANSIColor.Palette(clamp(n))
        }
        if (mode == 2) {
            // 38:2:r:g:b 或 38:2:<色彩空间>:r:g:b
            val vals = rest.drop(1)
            val rgb = if (vals.size >= 4) vals.takeLast(3) else vals
            if (rgb.size == 3) {
                val r = rgb[0]
                val g = rgb[1]
                val b = rgb[2]
                if (r != null && g != null && b != null) return ANSIColor.Rgb(clamp(r), clamp(g), clamp(b))
            }
        }
        return null
    }

    private fun clamp(v: Int): Int = v.coerceIn(0, 255)

    companion object {
        /** 去掉全部转义序列，只留文本。 */
        fun strip(line: String): String = ANSIParser().parse(line).joinToString("") { it.text }
    }
}

object ANSIPalette {
    /** 256 色调色板中 16–255 的 RGB（0–15 由设计变量的 ansi 数组提供，返回 null）。 */
    fun rgb(index: Int): ANSIColor.Rgb? {
        if (index < 16 || index > 255) return null
        if (index < 232) {
            val v = index - 16
            val levels = intArrayOf(0, 95, 135, 175, 215, 255)
            return ANSIColor.Rgb(levels[v / 36], levels[(v / 6) % 6], levels[v % 6])
        }
        val gray = 8 + (index - 232) * 10
        return ANSIColor.Rgb(gray, gray, gray)
    }
}
