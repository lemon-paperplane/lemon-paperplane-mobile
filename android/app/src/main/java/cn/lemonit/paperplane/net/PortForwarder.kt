package cn.lemonit.paperplane.net

import cn.lemonit.paperplane.kit.ApiException
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

/**
 * 端口转发（对照桌面 gateway.forward）：把服务器 localhost 上的端口转到手机的 127.0.0.1。
 * 本机监听一个回环端口，每个连进来的连接新开一条到服务器的 TLS 连接（HTTP/1.1），
 * `GET /api/v1/tunnel?port=N` + `Connection: Upgrade` + `Upgrade: paperplane-tunnel` 握手，服务器回 101 后双向拷贝原始字节。
 *
 * 转发地址在 App 内置浏览器里打开（切到后台连接会断）。和桌面一样转发端口不要令牌：手机上的其他 App 也能连这个回环端口。
 */
class PortForwarder(
    private val client: RemoteClient,
    private val logger: NetLogger = NetLogger.NONE,
) : Closeable {

    data class Forward(val remotePort: Int, val localPort: Int) {
        /** App 内置浏览器打开的地址 */
        val url: String get() = "http://localhost:$localPort"
    }

    private class Entry(val forward: Forward, val listener: ServerSocket) {
        val conns: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    }

    private val entries = ConcurrentHashMap<Int, Entry>()
    private val threadNo = AtomicInteger()
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "paperplane-forward-${threadNo.incrementAndGet()}").apply { isDaemon = true }
    }

    @Volatile
    private var closed = false

    /** 当前的转发 */
    val forwards: List<Forward> get() = entries.values.map { it.forward }.sortedBy { it.remotePort }

    /**
     * 开始转发服务器的 [remotePort]：优先用同一个端口号（网页里写死端口的开发服务器热更新也能连上），被占用时用随机端口。
     * 已在转发则直接返回。只绑定本机端口，不连服务器（连接进来时才建隧道）。
     */
    @Synchronized
    fun forward(remotePort: Int): Forward {
        check(!closed) { "forwarder closed" }
        require(remotePort in 1..65535) { "invalid port $remotePort" }
        entries[remotePort]?.let { return it.forward }
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val ss = try {
            ServerSocket(remotePort, 64, loopback)
        } catch (e: IOException) {
            ServerSocket(0, 64, loopback)
        }
        val e = Entry(Forward(remotePort, ss.localPort), ss)
        entries[remotePort] = e
        pool.execute { acceptLoop(e) }
        logger.log("forward: 127.0.0.1:${ss.localPort} → server localhost:$remotePort")
        return e.forward
    }

    /** 停止一个转发（关监听端口和它上面的连接） */
    @Synchronized
    fun unforward(remotePort: Int) {
        entries.remove(remotePort)?.let { e -> offCallerThread("paperplane-unforward") { shut(e) } }
    }

    override fun close() {
        val all = synchronized(this) {
            closed = true
            entries.values.toList().also { entries.clear() }
        }
        // 到服务器的是 TLS 连接，关闭要发 close_notify：放到后台线程（安卓主线程上不允许网络操作）
        offCallerThread("paperplane-forward-close") {
            all.forEach { shut(it) }
            pool.shutdownNow()
        }
    }

    private fun shut(e: Entry) {
        closeQuietly(e.listener)
        e.conns.forEach { closeQuietly(it) }
    }

    private fun acceptLoop(e: Entry) {
        while (!closed) {
            val local = try {
                e.listener.accept()
            } catch (x: IOException) {
                return
            }
            e.conns += local
            try {
                pool.execute { serve(e, local) }
            } catch (x: Exception) {
                e.conns -= local
                closeQuietly(local)
            }
        }
    }

    private fun serve(e: Entry, local: Socket) {
        var remote: Tunnel? = null
        try {
            remote = dial(e.forward.remotePort)
            e.conns += remote.socket
            pipe(local, local.getInputStream(), remote.socket, remote.input)
        } catch (x: ApiException) {
            client.reportProblem(x)
            logger.log("forward: tunnel ${e.forward.remotePort}: ${x.code} ${x.message}")
        } catch (x: IOException) {
            logger.log("forward: tunnel ${e.forward.remotePort}: $x")
        } finally {
            e.conns -= local
            closeQuietly(local)
            remote?.let {
                e.conns -= it.socket
                closeQuietly(it.socket)
            }
        }
    }

    private class Tunnel(val socket: SSLSocket, val input: InputStream)

    /** 建一条隧道；还没发出请求就失败（连不上、证书不对）时换地址重试一次 */
    private fun dial(port: Int): Tunnel {
        var base = client.baseBlocking()
        try {
            return dialOnce(base, port)
        } catch (e: PreRequestFailure) {
            base = client.failoverBlocking(base)
            return try {
                dialOnce(base, port)
            } catch (x: PreRequestFailure) {
                throw NetErrors.map(x.cause ?: x)
            }
        }
    }

    private class PreRequestFailure(cause: Throwable) : IOException(cause.message, cause)

    private fun dialOnce(base: String, port: Int): Tunnel {
        val u = base.toHttpUrl()
        val tls = client.tls
        val raw = Socket()
        val ssl: SSLSocket
        try {
            raw.connect(InetSocketAddress(u.host, u.port), CONNECT_TIMEOUT_MS)
            ssl = tls.socketFactory.createSocket(raw, u.host, u.port, true) as SSLSocket
            ssl.soTimeout = HANDSHAKE_TIMEOUT_MS
            ssl.startHandshake()
            if (!tls.hostnameVerifier.verify(u.host, ssl.session)) throw SSLPeerUnverifiedException("hostname ${u.host} not verified")
        } catch (e: IOException) {
            closeQuietly(raw)
            throw PreRequestFailure(e)
        }
        try {
            val hostHeader = ServerAddress.joinHostPort(u.host, u.port)
            val req = "GET /api/v1/tunnel?port=$port HTTP/1.1\r\nHost: $hostHeader\r\n" +
                "Authorization: Bearer ${client.token}\r\nConnection: Upgrade\r\nUpgrade: paperplane-tunnel\r\n\r\n"
            ssl.outputStream.write(req.toByteArray())
            ssl.outputStream.flush()
            val input = BufferedInputStream(ssl.inputStream)
            val (status, headers) = readHead(input)
            if (status != 101) {
                val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 4096) ?: 0
                val body = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = input.read(body, read, len - read)
                    if (n < 0) break
                    read += n
                }
                closeQuietly(ssl)
                throw NetErrors.http(status, body.copyOf(read), null)
            }
            ssl.soTimeout = 0
            return Tunnel(ssl, input)
        } catch (e: IOException) {
            closeQuietly(ssl)
            throw NetErrors.map(e)
        }
    }

    /** 读响应状态行和头（逐字节读，头后面已到的数据留在 BufferedInputStream 里，之后先交给本地连接） */
    private fun readHead(input: InputStream): Pair<Int, Map<String, String>> {
        val lines = ArrayList<String>()
        val line = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("tunnel: connection closed during handshake")
            if (++total > 64 * 1024) throw IOException("tunnel: response header too large")
            if (b == '\n'.code) {
                val s = line.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
                line.reset()
                if (s.isEmpty()) break
                lines += s
            } else {
                line.write(b)
            }
        }
        val status = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull()
            ?: throw IOException("tunnel: bad status line")
        val headers = lines.drop(1).mapNotNull { h ->
            val i = h.indexOf(':')
            if (i <= 0) null else h.substring(0, i).trim().lowercase() to h.substring(i + 1).trim()
        }.toMap()
        return status to headers
    }

    /** 双向拷贝；任一方向结束后给另一方向最多 5 秒把剩余数据送完，然后两边都关（同内核 remote.Pipe） */
    private fun pipe(local: Socket, localIn: InputStream, remote: SSLSocket, remoteIn: InputStream) {
        val first = CountDownLatch(1)
        val both = CountDownLatch(2)
        fun copy(src: InputStream, dst: Socket, halfClose: Boolean) {
            try {
                val buf = ByteArray(16 * 1024)
                val out = dst.getOutputStream()
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    out.flush()
                }
                // TLS 连接不能半关闭（SSLSocket 不支持 shutdownOutput），只有本地连接半关闭
                if (halfClose) {
                    try {
                        dst.shutdownOutput()
                    } catch (e: Exception) {
                    }
                }
            } catch (e: IOException) {
            } finally {
                first.countDown()
                both.countDown()
            }
        }
        pool.execute { copy(remoteIn, local, true) }
        pool.execute { copy(localIn, remote, false) }
        first.await()
        both.await(5, TimeUnit.SECONDS)
        closeQuietly(local)
        closeQuietly(remote)
    }

    private fun closeQuietly(c: Closeable) {
        try {
            c.close()
        } catch (e: IOException) {
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
    }
}
