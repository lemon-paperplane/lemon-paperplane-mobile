package cn.lemonit.paperplane.net

/**
 * 服务器地址处理（对照内核 `remote.NormalizeAddress`）。
 */
object ServerAddress {
    /** 远程监听默认端口 */
    const val DEFAULT_PORT = 47290

    /**
     * 「主机」「主机:端口」「https://…」都转成 https://主机:端口；不合法返回 null。
     * 没写协议也没写端口时用默认端口；写了 https:// 但没写端口时用 443（反向代理的情况）；http:// 不接受。
     * 主机名统一成小写，便于去重和比较。
     */
    fun normalize(address: String, defaultPort: Int = DEFAULT_PORT): String? {
        var a = address.trim()
        if (a.isEmpty()) return null
        if (a.startsWith("http://", ignoreCase = true)) return null
        val scheme = a.startsWith("https://", ignoreCase = true)
        if (scheme) a = a.substring(8)
        a = a.removeSuffix("/")
        a.indexOf('/').takeIf { it >= 0 }?.let { a = a.substring(0, it) }
        val (host, port) = splitHostPort(a) ?: Pair(a.trim('[', ']'), if (scheme) 443 else defaultPort)
        if (host.isEmpty()) return null
        if (port !in 1..65535) return null
        return "https://" + joinHostPort(host.lowercase(), port)
    }

    /** 和 Go net.SplitHostPort 相同的规则：IPv6 必须带方括号才能带端口；没有端口或格式不对返回 null */
    internal fun splitHostPort(a: String): Pair<String, Int>? {
        if (a.startsWith("[")) {
            val end = a.indexOf(']')
            if (end < 0 || end + 1 >= a.length || a[end + 1] != ':') return null
            val p = a.substring(end + 2)
            if (p.isEmpty() || !p.all { it.isDigit() } || p.length > 5) return Pair(a.substring(1, end), -1)
            return Pair(a.substring(1, end), p.toInt())
        }
        val i = a.lastIndexOf(':')
        if (i < 0) return null
        if (a.indexOf(':') != i) return null // 不带方括号的 IPv6
        val p = a.substring(i + 1)
        if (p.isEmpty() || !p.all { it.isDigit() } || p.length > 5) return Pair(a.substring(0, i), -1)
        return Pair(a.substring(0, i), p.toInt())
    }

    /** IPv6 加方括号 */
    fun joinHostPort(host: String, port: Int): String = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    /** 地址里的主机（IPv6 不带方括号）；解析不了返回原样 */
    fun host(url: String): String {
        val n = normalize(url) ?: return url
        val hp = n.removePrefix("https://")
        return splitHostPort(hp)?.first ?: hp
    }

    /** 显示用：去掉 https:// */
    fun display(url: String): String = url.removePrefix("https://").removeSuffix("/")

    /** 规范化后去重，丢掉不合法的 */
    fun normalizeAll(urls: Iterable<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (u in urls) normalize(u)?.let { out += it }
        return out.toList()
    }

    /** 两个地址是否相同（规范化后比较） */
    fun same(a: String, b: String): Boolean {
        val x = normalize(a) ?: return false
        val y = normalize(b) ?: return false
        return x == y
    }
}
