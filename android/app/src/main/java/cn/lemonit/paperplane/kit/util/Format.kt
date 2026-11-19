package cn.lemonit.paperplane.kit.util

import java.util.Locale

// 对照 Mac PaperplaneKit/Util/Format.swift。数字一律用 Locale.ROOT 格式化（小数点，不随系统语言变成逗号）。

/** 图标引用（docs/api.md 第 4 节）：builtin:<name> 或 plugin:<pluginId>/<相对路径>。 */
sealed interface IconSource {
    /** shared/icons/<name>.svg */
    data class Builtin(val name: String) : IconSource

    /** GET /api/v1/plugins/<id>/files/<path> */
    data class Plugin(val id: String, val path: String) : IconSource

    /** 缓存键 */
    val key: String
        get() = when (this) {
            is Builtin -> "builtin:$name"
            is Plugin -> "plugin:$id/$path"
        }

    companion object {
        /** 解析引用。没有前缀时：给了 pluginId 就当作该插件目录里的相对路径，否则当作内置图标名（去掉 .svg）。 */
        fun parse(raw: String?, pluginId: String? = null): IconSource? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return null
            if (s.startsWith("builtin:")) return Builtin(s.removePrefix("builtin:"))
            if (s.startsWith("plugin:")) {
                val rest = s.removePrefix("plugin:")
                val slash = rest.indexOf('/')
                if (slash < 0) return null
                return Plugin(rest.substring(0, slash), rest.substring(slash + 1))
            }
            if (!pluginId.isNullOrEmpty()) return Plugin(pluginId, s)
            return Builtin(s.removeSuffix(".svg"))
        }
    }
}

object Format {
    /**
     * 当前服务器的主目录与路径规则（切换服务器时由集成层按 SystemInfo.home / pathSeparator 设置）。
     * 界面上显示的路径都是服务器上的，缩写 ~ 要按那台机器的主目录。
     */
    @Volatile
    var serverHome: String = ""

    @Volatile
    var serverPath: ServerPath = ServerPath.POSIX

    /** 把用户主目录缩写为 ~（默认按当前服务器） */
    fun abbreviatePath(path: String, home: String? = null): String = serverPath.abbreviate(path, home ?: serverHome)

    /** 内存：MB → 「512 MB」「1.2 GB」 */
    fun memory(mb: Double): String = when {
        mb >= 1024 -> String.format(Locale.ROOT, "%.1f GB", mb / 1024)
        mb >= 100 -> String.format(Locale.ROOT, "%.0f MB", mb)
        else -> String.format(Locale.ROOT, "%.1f MB", mb)
    }

    /** CPU 百分比 */
    fun cpu(v: Double): String = if (v >= 10) String.format(Locale.ROOT, "%.0f%%", v) else String.format(Locale.ROOT, "%.1f%%", v)

    /** 文件大小（十进制，和 Finder 一致）：「999 B」「12 KB」「1.2 MB」 */
    fun bytes(n: Long): String {
        if (n < 1000) return "$n B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        var v = n / 1000.0
        var i = 0
        while (v >= 999.5 && i < units.size - 1) {
            v /= 1000.0
            i++
        }
        return if (i == 0) String.format(Locale.ROOT, "%.0f %s", v, units[i]) else String.format(Locale.ROOT, "%.1f %s", v, units[i])
    }

    /** 持续时长：「3s」「2m 5s」「1h 4m」 */
    fun duration(seconds: Double): String {
        val s = maxOf(0.0, seconds).toLong()
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }

    /** 地址显示：去掉 http:// / https:// 前缀与末尾斜杠 */
    fun shortURL(url: String): String {
        var s = url
        for (p in listOf("http://", "https://")) if (s.startsWith(p)) s = s.removePrefix(p)
        return s.removeSuffix("/")
    }
}
