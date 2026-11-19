package cn.lemonit.paperplane.kit.model

import kotlinx.serialization.Serializable

// 文件操作（fs.*）与编辑器相关的数据结构，契约见 docs/editor.md。对照 Mac PaperplaneKit/Models/FileModels.swift。
// 路径一律是相对工程根、"/" 分隔；mtime 是 RFC3339Nano 字符串，原样回传给 expectedMtime（所以不转成 Instant）。

/** fs.list 的一项 */
@Serializable
data class FSEntry(
    val name: String = "",
    val path: String = "",
    val dir: Boolean = false,
    val size: Long = 0,
    val mtime: String? = null,
    val symlink: Boolean = false,
    val hidden: Boolean = false,
    /** node_modules / build / .venv 等：界面淡化显示，仍可展开 */
    val ignored: Boolean = false,
    /** 普通文件且有执行权限（Windows 上恒为 false） */
    val exec: Boolean = false,
    /** 能否作为启动项运行（内核判定）：script | binary | source；空表示不能（文档、数据、图片等） */
    val runnable: String? = null,
) {
    val id: String get() = path

    /**
     * 文件树长按菜单里提供「添加到启动」：脚本和可执行程序（和「添加脚本或程序」选择框的默认列表一致；
     * 没有 #! 的 .py/.js 等源码多是模块或配置文件，要加就在选择框里打开「也显示其他源码文件」）
     */
    val isLaunchable: Boolean get() = !dir && (runnable == "script" || runnable == "binary")

    /** 扩展名（小写，不带点）；以点开头且只有一个点的（.gitignore）没有扩展名 */
    val ext: String
        get() {
            val dot = name.lastIndexOf('.')
            if (dot <= 0) return ""
            return name.substring(dot + 1).lowercase()
        }
}

/** fs.list 输出 */
@Serializable
data class FSListResult(
    val entries: List<FSEntry> = emptyList(),
    val truncated: Boolean = false,
)

/** fs.read 输出 */
@Serializable
data class FSReadResult(
    val content: String = "",
    val encoding: String? = null,
    val bom: Boolean = false,
    /** lf | crlf | mixed */
    val lineEnding: String? = null,
    val size: Long = 0,
    val mtime: String? = null,
    val binary: Boolean = false,
    val tooLarge: Boolean = false,
    val readOnly: Boolean = false,
)

/** fs.write 输出 */
@Serializable
data class FSWriteResult(
    val mtime: String? = null,
    val size: Long = 0,
)

/** fs.stat 的一项 */
@Serializable
data class FSStat(
    val path: String = "",
    val exists: Boolean = false,
    val dir: Boolean = false,
    val size: Long = 0,
    val mtime: String? = null,
)

/** fs.delete 输出（移到废纸篓） */
@Serializable
data class FSDeleteResult(val trashed: List<String> = emptyList())

/** fs.search 的一项（按文件名模糊匹配） */
@Serializable
data class FSSearchHit(
    val path: String = "",
    val name: String = "",
    val score: Double = 0.0,
) {
    val id: String get() = path

    /** 所在目录（相对工程根） */
    val directory: String get() = if ('/' in path) path.substringBeforeLast('/') else ""
}

/** fs.grep 输出 */
@Serializable
data class FSGrepResult(
    val matches: List<FSGrepMatch> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
data class FSGrepMatch(
    val path: String = "",
    val line: Int = 0,
    val column: Int = 0,
    val text: String = "",
)

/** fs.write 冲突时 ApiException.reason 的值 */
object FSReason {
    const val MODIFIED_EXTERNALLY = "modifiedExternally"
}

// ---------------------------------------------------------------- 编辑器设置与插件贡献

/** settings.editor（缺字段时取内核的默认值） */
@Serializable
data class EditorSettings(
    /** 空表示按平台默认（手机上见 PLATFORM_DEFAULT_KEYMAP） */
    val keymap: String = "",
    /** 空表示跟随 App 明暗；否则为插件配色主题 id */
    val theme: String = "",
    val fontSize: Int = 13,
    val fontFamily: String = "",
    val tabSize: Int = 4,
    val insertSpaces: Boolean = true,
    val wordWrap: Boolean = false,
    /** off | onFocusChange | afterDelay */
    val autoSave: String = "off",
    val trimTrailingWhitespace: Boolean = false,
    val insertFinalNewline: Boolean = true,
    /** 显示空格与制表符 */
    val showWhitespace: Boolean = false,
) {
    /** 实际用的快捷键方案：没设置时用本机（手机）的默认方案 */
    fun effectiveKeymap(platformDefault: String = PLATFORM_DEFAULT_KEYMAP): String = keymap.ifEmpty { platformDefault }

    companion object {
        /** 手机外接键盘用 Ctrl 组合键，和 Windows 方案一致（Mac 版是 eclipse-macos） */
        const val PLATFORM_DEFAULT_KEYMAP = "eclipse-windows"
    }
}

/** 语言贡献（着色模块） */
@Serializable
data class LanguageInfo(
    val id: String = "",
    val name: String = "",
    val extensions: List<String> = emptyList(),
    val filenames: List<String> = emptyList(),
    val filenamePatterns: List<String> = emptyList(),
    val module: String? = null,
    val lineComment: String? = null,
    val blockComment: List<String> = emptyList(),
    val priority: Int = 0,
    val plugin: String = "",
)

/** 快捷键方案贡献 */
@Serializable
data class KeymapInfo(
    val id: String = "",
    val name: String = "",
    /** macos | windows | linux | any */
    val platform: String = "",
    val file: String? = null,
    val module: String? = null,
    val base: String? = null,
    val plugin: String = "",
)

/** 编辑器配色主题贡献 */
@Serializable
data class EditorThemeInfo(
    val id: String = "",
    val name: String = "",
    /** light | dark */
    val kind: String = "",
    val file: String? = null,
    val plugin: String = "",
)

/** 按文件名找语言：精确文件名 > 扩展名 > 文件名模式（glob），同等匹配取 priority 高的。 */
object LanguageResolver {
    fun language(fileName: String, languages: List<LanguageInfo>): LanguageInfo? {
        val lower = fileName.lowercase()
        fun best(list: List<LanguageInfo>): LanguageInfo? = list.maxByOrNull { it.priority }

        best(languages.filter { l -> l.filenames.any { it.lowercase() == lower } })?.let { return it }
        // 扩展名：最长匹配优先（.d.ts 优先于 .ts）
        val extHits = mutableListOf<Pair<Int, LanguageInfo>>()
        for (l in languages) {
            for (e in l.extensions) {
                val ext = (if (e.startsWith(".")) e else ".$e").lowercase()
                if (lower.endsWith(ext) && lower.length > ext.length) extHits.add(ext.length to l)
            }
        }
        extHits.maxOfOrNull { it.first }?.let { maxLen ->
            best(extHits.filter { it.first == maxLen }.map { it.second })?.let { return it }
        }
        return best(languages.filter { l -> l.filenamePatterns.any { glob(it, lower) } })
    }

    /** 简单 glob：* 与 ?（不区分大小写） */
    fun glob(pattern: String, name: String): Boolean {
        val p = pattern.lowercase()
        val s = name.lowercase()
        var pi = 0
        var si = 0
        var star = -1
        var mark = 0
        while (si < s.length) {
            if (pi < p.length && (p[pi] == '?' || p[pi] == s[si])) {
                pi++; si++
            } else if (pi < p.length && p[pi] == '*') {
                star = pi; mark = si; pi++
            } else if (star >= 0) {
                pi = star + 1; mark++; si = mark
            } else {
                return false
            }
        }
        while (pi < p.length && p[pi] == '*') pi++
        return pi == p.length
    }
}

/** 文件类型图标（通用 SVG，shared/icons 里的名字） */
object FileIcon {
    fun builtinName(entry: FSEntry, expanded: Boolean = false): String {
        if (entry.dir) {
            if (entry.name == ".git") return "folder-git"
            return if (expanded) "folder-open" else "folder"
        }
        return builtinName(entry.name)
    }

    fun builtinName(fileName: String): String {
        val lower = fileName.lowercase()
        when (lower) {
            "dockerfile", "makefile", "procfile", "jenkinsfile", "vagrantfile" -> return "file-cog"
            "license", "licence", "license.md", "license.txt", "notice" -> return "file-text"
            ".gitignore", ".gitattributes", ".gitmodules", ".dockerignore", ".npmrc", ".nvmrc", ".editorconfig",
            ".env", ".env.local", ".python-version", ".tool-versions" -> return "file-cog"
        }
        if (lower.startsWith(".env")) return "file-lock"
        val ext = if ('.' in lower) lower.substringAfterLast('.') else ""
        return when (ext) {
            "json", "jsonc", "json5" -> "file-json"
            "md", "markdown", "txt", "rst", "adoc", "log" -> "file-text"
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "tiff", "heic", "avif" -> "file-image"
            "mp4", "mov", "webm", "avi", "mkv" -> "file-video"
            "mp3", "wav", "ogg", "flac", "aac", "m4a" -> "file-music"
            "zip", "tar", "gz", "tgz", "xz", "bz2", "7z", "rar", "jar", "war", "aar", "whl" -> "file-archive"
            "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1" -> "file-terminal"
            "yml", "yaml", "toml", "ini", "cfg", "conf", "properties", "xml", "plist", "gradle", "lock" -> "file-cog"
            "csv", "tsv", "xls", "xlsx", "ods" -> "file-spreadsheet"
            "pem", "key", "crt", "cer", "p12", "jks", "keystore" -> "file-key"
            "ttf", "otf", "woff", "woff2" -> "file-type"
            "db", "sqlite", "sqlite3", "sql" -> "database"
            "pdf" -> "file-text"
            "diff", "patch" -> "file-diff"
            "" -> "file"
            else -> "file-code"
        }
    }
}

/**
 * 编辑器窗口地址（docs/editor.md 第 5 节）：<base>/editor/index.html#token=…&projectId=…&file=…&line=…&locale=…&theme=…
 * Android 上 base 是 App 内本地网关（net.LocalGateway）的 http://127.0.0.1:端口，token 是网关的本地令牌。
 */
object EditorURL {
    fun make(base: String, token: String, projectId: String, file: String?, line: Int?, locale: String, theme: String): String {
        val pairs = mutableListOf("token" to token, "projectId" to projectId)
        if (!file.isNullOrEmpty()) pairs.add("file" to file)
        if (line != null && line > 0) pairs.add("line" to line.toString())
        pairs.add("locale" to locale)
        pairs.add("theme" to theme)
        val fragment = pairs.joinToString("&") { (k, v) -> "$k=${encodeFragmentValue(v)}" }
        return base.trimEnd('/') + "/editor/index.html#" + fragment
    }

    /** 只保留字母数字与 -._~/，其余百分号编码（和 Mac 一致） */
    private fun encodeFragmentValue(v: String): String {
        val sb = StringBuilder()
        for (b in v.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-._~/")) sb.append(ch)
            else sb.append('%').append("%02X".format(c))
        }
        return sb.toString()
    }
}
