package cn.lemonit.paperplane.net

import cn.lemonit.paperplane.kit.ApiException
import okhttp3.tls.HandshakeCertificates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/** 假的隧道服务器：hello 回 200；隧道校验握手后回 101，并紧跟着在同一次写里发一行欢迎语（测试缓冲里的多余字节），之后回显 */
class FakeTunnelServer(private val acceptKey: String = TOKEN) : AutoCloseable {
    private val ss: SSLServerSocket
    val requests = ConcurrentLinkedQueue<String>()

    init {
        val certs = HandshakeCertificates.Builder().heldCertificate(TestCerts.selfSigned).build()
        ss = certs.sslContext().serverSocketFactory.createServerSocket(0, 16, TestCerts.loopback) as SSLServerSocket
        thread(isDaemon = true) {
            while (true) {
                val s = try {
                    ss.accept() as SSLSocket
                } catch (e: Exception) {
                    break
                }
                thread(isDaemon = true) { handle(s) }
            }
        }
    }

    val base get() = "https://127.0.0.1:${ss.localPort}"

    /**
     * 回完就关时先优雅收尾：发 close_notify 后等对方读完、关掉再关。
     * 直接 close 时 Linux 若接收缓冲里还有没读的字节会发 RST，对方还没读到的回复会被一起丢掉（macOS 不会），
     * 表现为客户端「unexpected end of stream」。
     */
    private fun closeGracefully(s: SSLSocket) {
        try {
            s.soTimeout = 2000
            s.shutdownOutput()
            val buf = ByteArray(1024)
            while (s.inputStream.read(buf) >= 0) Unit
        } catch (_: Exception) {
        }
    }

    private fun handle(s: SSLSocket) {
        s.use {
            val r = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
            val lines = generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.toList()
            val out = s.outputStream
            if (lines.firstOrNull()?.startsWith("GET /api/v1/auth/hello ") == true) {
                val body = helloJson()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
                out.flush()
                closeGracefully(s)
                return
            }
            requests += lines.joinToString("\n")
            val ok = lines.firstOrNull()?.startsWith("GET /api/v1/tunnel?port=") == true &&
                lines.any { it.equals("Upgrade: paperplane-tunnel", true) } &&
                lines.any { it == "Authorization: Bearer $acceptKey" }
            if (!ok) {
                val body = """{"error":{"code":"unauthorized","message":"invalid or revoked access key"}}"""
                out.write("HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
                out.flush()
                closeGracefully(s)
                return
            }
            out.write("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: paperplane-tunnel\r\n\r\nWELCOME\n".toByteArray())
            out.flush()
            while (true) {
                val line = r.readLine() ?: break
                out.write("echo:$line\n".toByteArray())
                out.flush()
            }
        }
    }

    override fun close() {
        ss.close()
    }
}

class PortForwarderTest {
    private lateinit var server: FakeTunnelServer
    private lateinit var c: RemoteClient
    private lateinit var fwd: PortForwarder
    private val l = RecordingListener()

    /** 转发与客户端的日志：失败时附在异常里（云端 Linux 上排查用） */
    private val logs = ConcurrentLinkedQueue<String>()
    private val logger = NetLogger { logs += "${System.nanoTime() / 1_000_000} [${Thread.currentThread().name}] $it" }

    @get:org.junit.Rule
    val attachLogs = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } catch (t: Throwable) {
                    throw AssertionError("${t}\n--- 日志 ---\n" + logs.joinToString("\n"), t)
                }
            }
        }
    }

    @Before
    fun setUp() {
        server = FakeTunnelServer()
        c = RemoteClient(ServerTarget.of(listOf(server.base), TestCerts.fp(TestCerts.selfSigned)), TOKEN,
            options = RemoteClient.Options(logger = logger), listener = l)
        fwd = PortForwarder(c, logger)
    }

    @After
    fun tearDown() {
        fwd.close()
        server.close()
    }

    private fun converse(localPort: Int): List<String> = Socket(TestCerts.loopback, localPort).use { s ->
        s.soTimeout = 5000
        val r = BufferedReader(InputStreamReader(s.getInputStream()))
        val out = s.getOutputStream()
        val got = ArrayList<String>()
        got += r.readLine()
        out.write("ping\n".toByteArray())
        out.flush()
        got += r.readLine()
        out.write("中文\n".toByteArray())
        out.flush()
        got += r.readLine()
        got
    }

    @Test
    fun tunnelsAndReusesSamePort() {
        val want = freePort()
        val f = fwd.forward(want)
        assertEquals(want, f.localPort) // 优先用同一个端口号
        assertEquals("http://localhost:$want", f.url)
        assertEquals(f, fwd.forward(want))
        assertEquals(listOf("WELCOME", "echo:ping", "echo:中文"), converse(f.localPort))
        // 并发两条
        val a = thread { assertEquals("WELCOME", converse(f.localPort)[0]) }
        val b = thread { assertEquals("WELCOME", converse(f.localPort)[0]) }
        a.join(10_000)
        b.join(10_000)
        val req = server.requests.first().lines()
        assertEquals("GET /api/v1/tunnel?port=$want HTTP/1.1", req[0])
        assertTrue(req.contains("Connection: Upgrade"))
        assertEquals(listOf(f), fwd.forwards)
        fwd.unforward(want)
        assertTrue(fwd.forwards.isEmpty())
        assertTrue(runCatching { Socket(TestCerts.loopback, want).close() }.isFailure)
    }

    @Test
    fun portInUseFallsBackToRandom() {
        java.net.ServerSocket(0, 1, TestCerts.loopback).use { busy ->
            val f = fwd.forward(busy.localPort)
            assertTrue(f.localPort != busy.localPort && f.localPort > 0)
        }
    }

    @Test
    fun rejectedKeyClosesConnectionAndReports() {
        val bad = PortForwarder(RemoteClient(ServerTarget.of(listOf(server.base), TestCerts.fp(TestCerts.selfSigned)),
            "ppk_00000000_" + "x".repeat(43), options = RemoteClient.Options(logger = logger), listener = l), logger)
        bad.use {
            val f = it.forward(freePort())
            Socket(TestCerts.loopback, f.localPort).use { s ->
                s.soTimeout = 5000
                assertEquals(-1, s.getInputStream().read())
            }
        }
        assertEquals(ApiException.UNAUTHORIZED, l.problems.poll(3, TimeUnit.SECONDS)?.code)
    }

    @Test
    fun wrongFingerprintNeverSendsKey() {
        val other = PortForwarder(RemoteClient(ServerTarget.of(listOf(server.base), TestCerts.fp(TestCerts.other)), TOKEN, listener = l))
        other.use {
            val f = it.forward(freePort())
            Socket(TestCerts.loopback, f.localPort).use { s ->
                s.soTimeout = 8000
                assertEquals(-1, s.getInputStream().read())
            }
        }
        assertEquals(ApiException.FINGERPRINT_MISMATCH, l.problems.poll(3, TimeUnit.SECONDS)?.code)
        assertTrue(server.requests.isEmpty())
    }
}
