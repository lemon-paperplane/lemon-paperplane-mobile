package cn.lemonit.paperplane.kit.util

import java.net.URLEncoder

// 对照 Mac PaperplaneKit/Util/ServerPath.swift。

/**
 * 服务器上的路径：一律按服务器的分隔符处理（手机连 Windows 服务器时是 C:\…），
 * 不用 java.io.File 这些按本机规则的函数。sep 取 SystemInfo.pathSeparator。
 */
class ServerPath(sep: String) {
    /** 路径分隔符："/" 或 "\" */
    val sep: String = sep.ifEmpty { "/" }

    val isWindows: Boolean get() = sep == "\\"

    /** Windows 上 "/" 也是分隔符 */
    private fun isSep(c: Char): Boolean = c.toString() == sep || (isWindows && c == '/')

    /** 根目录："/"、"C:\"、"C:" */
    fun isRoot(path: String): Boolean {
        if (!isWindows) return path == "/"
        val p = path.replace('/', '\\')
        if (p.length == 3 && p.endsWith(":\\")) return true
        if (p.length == 2 && p.endsWith(":")) return true
        return false
    }

    fun isAbsolute(path: String): Boolean {
        if (!isWindows) return path.startsWith("/")
        val p = path.replace('/', '\\')
        if (p.startsWith("\\\\")) return true
        return p.length >= 3 && p[0].isLetter() && p[1] == ':' && p[2] == '\\'
    }

    /** 去掉末尾多余的分隔符（根目录保留） */
    fun normalize(path: String): String {
        var p = path.trim()
        if (isWindows) p = p.replace('/', '\\')
        while (p.length > 1 && isSep(p.last()) && !isRoot(p)) p = p.dropLast(1)
        if (isWindows && p.length == 2 && p.endsWith(":")) p += "\\"
        return p
    }

    /** 拼接：base + 分隔符 + name（name 可以含多级，Windows 下 "/" 换成 "\"） */
    fun join(base: String, name: String): String {
        var n = if (isWindows) name.replace('/', '\\') else name
        n = n.dropWhile { isSep(it) }
        if (base.isEmpty()) return n
        if (n.isEmpty()) return base
        if (isSep(base.last())) return base + n
        return base + sep + n
    }

    /** 最后一段（文件夹名）；根目录返回自身 */
    fun lastComponent(path: String): String {
        val p = normalize(path)
        if (isRoot(p)) return p
        val idx = p.indexOfLast { isSep(it) }
        if (idx < 0) return p
        return p.substring(idx + 1)
    }

    /** 上一级；已在根目录返回 null */
    fun parent(path: String): String? {
        val p = normalize(path)
        if (isRoot(p)) return null
        val idx = p.indexOfLast { isSep(it) }
        if (idx < 0) return null
        val head = p.substring(0, idx)
        if (head.isEmpty()) return sep // "/foo" → "/"
        if (isWindows && head.length == 2 && head.endsWith(":")) return "$head\\" // "C:\foo" → "C:\"
        return head
    }

    /** 两个路径是否相同（Windows 不分大小写） */
    fun same(a: String, b: String): Boolean {
        val x = normalize(a)
        val y = normalize(b)
        return if (isWindows) x.equals(y, ignoreCase = true) else x == y
    }

    /** path 在 root 之内时返回相对路径（用 "/" 连接，和内核 fs.* 的相对路径一致；相同返回 ""），否则 null */
    fun relative(path: String, root: String): String? {
        val p = normalize(path)
        val r = normalize(root)
        if (same(p, r)) return ""
        val prefix = if (r.endsWith(sep)) r else r + sep
        val match = if (isWindows) p.lowercase().startsWith(prefix.lowercase()) else p.startsWith(prefix)
        if (!match) return null
        val rest = p.substring(prefix.length)
        return if (isWindows) rest.replace('\\', '/') else rest
    }

    /** 工程内相对路径（内核用 "/"）→ 绝对路径 */
    fun absolute(rel: String, root: String): String = if (rel.isEmpty()) root else join(root, rel)

    /** 主目录缩写为 ~ */
    fun abbreviate(path: String, home: String): String {
        if (home.isEmpty()) return path
        val h = normalize(home)
        val match = if (isWindows) path.lowercase().startsWith(h.lowercase()) else path.startsWith(h)
        if (!match) return path
        val rest = path.substring(h.length)
        if (rest.isEmpty()) return "~"
        if (isSep(rest.first())) return "~$rest"
        return path
    }

    override fun equals(other: Any?): Boolean = other is ServerPath && other.sep == sep
    override fun hashCode(): Int = sep.hashCode()
    override fun toString(): String = "ServerPath($sep)"

    companion object {
        val POSIX = ServerPath("/")
        val WINDOWS = ServerPath("\\")
    }
}

/** 服务器地址与运行地址的处理 */
object ServerURL {
    /** 拆出 scheme、主机（IPv6 带方括号）、端口、其余部分 */
    data class Parts(
        val scheme: String,
        /** 含末尾的 @，没有时为空 */
        val userinfo: String,
        val host: String,
        val port: Int?,
        /** 路径、查询、片段 */
        val rest: String,
    ) {
        val string: String get() = scheme + "://" + userinfo + host + (port?.let { ":$it" } ?: "") + rest

        /** 端口（没写时按 scheme 的默认值） */
        val effectivePort: Int?
            get() = port ?: when (scheme.lowercase()) {
                "http", "ws" -> 80
                "https", "wss" -> 443
                else -> null
            }
    }

    /** 远程监听默认端口 */
    const val DEFAULT_PORT = 47290

    /** 解析不了（没有 scheme://、主机为空、方括号不配对）返回 null */
    fun parse(s: String): Parts? {
        val schemeEnd = s.indexOf("://")
        if (schemeEnd < 0) return null
        val scheme = s.substring(0, schemeEnd)
        if (scheme.isEmpty() || !scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
        val after = s.substring(schemeEnd + 3)
        val authEnd = after.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) after.length else it }
        var authority = after.substring(0, authEnd)
        val rest = after.substring(authEnd)
        var userinfo = ""
        val at = authority.lastIndexOf('@')
        if (at >= 0) {
            userinfo = authority.substring(0, at + 1)
            authority = authority.substring(at + 1)
        }
        var host = authority
        var port: Int? = null
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            host = authority.substring(0, close + 1)
            val tail = authority.substring(close + 1)
            if (tail.startsWith(":")) port = tail.substring(1).toIntOrNull()
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon >= 0) {
                host = authority.substring(0, colon)
                port = authority.substring(colon + 1).toIntOrNull()
            }
        }
        if (host.isEmpty()) return null
        return Parts(scheme, userinfo, host, port, rest)
    }

    /**
     * 和内核 remote.NormalizeAddress 一样补全用户填的地址：只写主机名或 IP 时用 https 和 47290；
     * 写了 https:// 没写端口时用 443；http:// 不支持（返回 null）。
     */
    fun normalizeAddress(raw: String): String? {
        var a = raw.trim()
        if (a.isEmpty() || a.lowercase().startsWith("http://")) return null
        val https = a.lowercase().startsWith("https://")
        if (https) a = a.substring(8)
        a.indexOf('/').takeIf { it >= 0 }?.let { a = a.substring(0, it) }
        var host = a
        var port: Int? = null
        if (a.startsWith("[")) {
            val close = a.indexOf(']')
            if (close < 0) return null
            host = a.substring(1, close)
            val tail = a.substring(close + 1)
            if (tail.startsWith(":")) port = tail.substring(1).toIntOrNull() ?: return null
        } else if (a.count { it == ':' } == 1) {
            val colon = a.indexOf(':')
            host = a.substring(0, colon)
            port = a.substring(colon + 1).toIntOrNull() ?: return null
        }
        if (host.isEmpty()) return null
        val p = port ?: if (https) 443 else DEFAULT_PORT
        if (p !in 1..65535) return null
        val h = if (':' in host) "[$host]" else host
        return "https://$h:$p"
    }

    /** 地址里的主机名（IPv6 带方括号）；只有 host[:port] 的写法也认 */
    fun host(url: String): String = parse(url)?.host ?: parse("https://$url")?.host ?: url

    /** 比较用：小写 scheme / 主机、去掉末尾斜杠 */
    fun normalize(url: String): String {
        val p = parse(url.trim()) ?: return url.lowercase()
        return p.copy(scheme = p.scheme.lowercase(), host = p.host.lowercase(), rest = p.rest.trimEnd('/')).string
    }

    /** 指向本机回环（或全部网卡）的主机名 */
    fun isLoopbackHost(host: String): Boolean {
        val h = host.lowercase()
        return when (h) {
            "localhost", "127.0.0.1", "0.0.0.0", "[::1]", "[::]", "::1", "::", "[0:0:0:0:0:0:0:1]" -> true
            else -> h.startsWith("127.") || h.endsWith(".localhost")
        }
    }

    /** 运行地址在服务器上的端口（本机回环地址才算，别的主机名返回 null） */
    fun loopbackPort(runURL: String): Int? {
        val p = parse(runURL) ?: return null
        if (!isLoopbackHost(p.host)) return null
        return p.effectivePort
    }

    /** 把运行地址里的 localhost / 127.0.0.1 / 0.0.0.0 换成服务器主机名（其他主机名原样返回） */
    fun direct(runURL: String, serverHost: String): String {
        val p = parse(runURL) ?: return runURL
        if (!isLoopbackHost(p.host) || serverHost.isEmpty()) return runURL
        val h = if (':' in serverHost && !serverHost.startsWith("[")) "[$serverHost]" else serverHost
        return p.copy(host = h).string
    }

    /** 运行地址经端口转发后在手机上的地址：换成 localhost:本机端口，保留路径 */
    fun forwarded(runURL: String, localPort: Int): String {
        val p = parse(runURL) ?: return "http://localhost:$localPort"
        return p.copy(host = "localhost", port = localPort).string
    }

    /** 显示用：去掉 scheme 与末尾斜杠 */
    fun display(url: String): String = Format.shortURL(url)
}

/**
 * 插件 webview 地址的改写（对照 Mac APIClient.rebasedWebviewURL）：服务器 view.render 给的地址是它自己的回环地址与令牌，
 * 或者只是相对路径；换成 App 本地网关的地址（baseUrl，如 http://127.0.0.1:端口）和本地令牌，
 * 网关会去掉 token 参数、换成访问密钥转发给服务器。
 */
object WebviewURL {
    fun rebase(raw: String, baseUrl: String, token: String): String {
        val rest = ServerURL.parse(raw)?.rest ?: if (raw.startsWith("/")) raw else "/$raw"
        val hashAt = rest.indexOf('#')
        val beforeHash = if (hashAt >= 0) rest.substring(0, hashAt) else rest
        val fragment = if (hashAt >= 0) rest.substring(hashAt) else ""
        val q = beforeHash.indexOf('?')
        val path = if (q >= 0) beforeHash.substring(0, q) else beforeHash
        val items = if (q >= 0) beforeHash.substring(q + 1).split('&').filter { it.isNotEmpty() } else emptyList()
        val kept = items.filter { it.substringBefore('=') != "token" }
        val query = (listOf("token=" + URLEncoder.encode(token, "UTF-8")) + kept).joinToString("&")
        val base = ServerURL.parse(baseUrl)?.copy(rest = "")?.string ?: baseUrl.trimEnd('/')
        return "$base${path.ifEmpty { "/" }}?$query$fragment"
    }
}
