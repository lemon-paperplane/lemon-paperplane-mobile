package cn.lemonit.paperplane.net

import cn.lemonit.paperplane.kit.ApiException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.fail
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager

/** 测试用证书 */
object TestCerts {
    val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    /** 自签证书（同内核：localhost + 127.0.0.1） */
    val selfSigned: HeldCertificate by lazy {
        HeldCertificate.Builder().commonName("Lemon Paperplane test")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
    }

    /** 另一张自签证书（证书变了） */
    val other: HeldCertificate by lazy {
        HeldCertificate.Builder().commonName("impostor").addSubjectAlternativeName("127.0.0.1").build()
    }

    val root: HeldCertificate by lazy { HeldCertificate.Builder().certificateAuthority(0).commonName("Test Root CA").build() }

    /** 测试根证书签发、只有 localhost 的证书（模拟 CA 证书） */
    val caLeaf: HeldCertificate by lazy {
        HeldCertificate.Builder().signedBy(root).commonName("localhost").addSubjectAlternativeName("localhost").build()
    }

    /** 只信任测试根证书的「系统信任库」 */
    val trustRoot: X509TrustManager by lazy {
        HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build().trustManager
    }

    fun fp(h: HeldCertificate) = Fingerprints.of(h.certificate)
}

/** 起一个 HTTPS 的 MockWebServer（监听 127.0.0.1） */
fun tlsServer(held: HeldCertificate = TestCerts.selfSigned, withRoot: Boolean = false): MockWebServer {
    val certs = HandshakeCertificates.Builder().apply {
        if (withRoot) heldCertificate(held, TestCerts.root.certificate) else heldCertificate(held)
    }.build()
    val s = MockWebServer()
    s.useHttps(certs.sslSocketFactory(), false)
    s.start(TestCerts.loopback, 0)
    return s
}

fun MockWebServer.base(host: String = "127.0.0.1") = "https://$host:$port"

fun MockWebServer.route(handler: (RecordedRequest) -> MockResponse) {
    dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = handler(request)
    }
}

/** 一个没人监听的地址（连接被拒） */
fun deadUrl(): String {
    val ss = ServerSocket(0, 1, TestCerts.loopback)
    val p = ss.localPort
    ss.close()
    return "https://127.0.0.1:$p"
}

fun freePort(): Int = ServerSocket(0, 1, TestCerts.loopback).use { it.localPort }

fun helloJson(api: Int = 1, passwordLogin: Boolean = false) =
    """{"name":"srv","hostname":"srv-host","version":"0.1.0","apiVersion":$api,"os":"linux","arch":"arm64","passwordLogin":$passwordLogin}"""

fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

/** 回 hello、其余交给 [rest] 的路由 */
fun helloRoute(rest: (RecordedRequest) -> MockResponse): (RecordedRequest) -> MockResponse = { r ->
    if (r.path == "/api/v1/auth/hello") json(helloJson()) else rest(r)
}

fun client(server: MockWebServer, fp: String = TestCerts.fp(TestCerts.selfSigned), token: String = TOKEN,
           listener: RemoteClient.Listener? = null, urls: List<String> = listOf(server.base()),
           options: RemoteClient.Options = RemoteClient.Options(probeStaggerMs = 100)): RemoteClient =
    RemoteClient(ServerTarget.of(urls, fp), token, options, listener)

const val TOKEN = "ppk_0123abcd_AAcOFRwjKjE4P0ZNVFtiaXB3foWMk5qhqK-2vcTL0tk"

inline fun expectApi(block: () -> Unit): ApiException {
    try {
        block()
    } catch (e: ApiException) {
        return e
    }
    fail("expected ApiException")
    throw AssertionError()
}

fun <T> blocking(timeoutMs: Long = 20_000, block: suspend () -> T): T = runBlocking { withTimeout(timeoutMs) { block() } }

/** 收集回调到队列，按需等待 */
class Collector<T> {
    val queue = LinkedBlockingQueue<T>()
    val add: (T) -> Unit = { queue.put(it) }

    fun next(timeoutMs: Long = 10_000): T = queue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw AssertionError("timed out waiting for an update")

    inline fun <reified R : T> nextOf(timeoutMs: Long = 10_000): R {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw AssertionError("timed out waiting for ${R::class.simpleName}")
            val v = queue.poll(left, TimeUnit.MILLISECONDS) ?: continue
            if (v is R) return v
        }
    }
}

class RecordingListener : RemoteClient.Listener {
    val urls = LinkedBlockingQueue<String>()
    val problems = LinkedBlockingQueue<ApiException>()
    override fun onActiveUrlChanged(url: String) {
        urls.put(url)
    }

    override fun onAuthProblem(error: ApiException) {
        problems.put(error)
    }
}

fun resource(name: String): ByteArray =
    TestCerts::class.java.classLoader!!.getResourceAsStream("fixtures/$name")!!.use { it.readBytes() }
