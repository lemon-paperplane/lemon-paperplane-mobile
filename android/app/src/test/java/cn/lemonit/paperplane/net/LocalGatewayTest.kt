package cn.lemonit.paperplane.net

import cn.lemonit.paperplane.kit.ApiException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Proxy
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.TimeUnit

class LocalGatewayTest {
    private lateinit var up: MockWebServer
    private lateinit var c: RemoteClient
    private lateinit var gw: LocalGateway
    private lateinit var root: File
    private lateinit var editorDir: File
    private val l = RecordingListener()
    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(10, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        up = tlsServer()
        c = client(up, listener = l)
        root = Files.createTempDirectory("pp-gw").toFile()
        editorDir = File(root, "editor")
        File(editorDir, "assets").mkdirs()
        File(editorDir, "index.html").writeText("<!doctype html>fake editor")
        File(editorDir, "assets/main.js").writeText("console.log(1)")
        File(root, "secret.txt").writeText("secret")
        gw = LocalGateway(c, DirectoryAssetSource(editorDir)).start()
    }

    @After
    fun tearDown() {
        gw.close()
        c.close()
        up.shutdown()
        root.deleteRecursively()
    }

    private fun get(path: String, vararg headers: Pair<String, String>) =
        http.newCall(Request.Builder().url(gw.baseUrl + path).apply { headers.forEach { header(it.first, it.second) } }.build()).execute()

    @Test
    fun editorAndHealth() {
        get("/healthz").use { assertEquals("ok", it.body!!.string()) }
        get("/editor/index.html").use {
            assertEquals(200, it.code)
            assertEquals("<!doctype html>fake editor", it.body!!.string())
            assertTrue(it.header("Content-Type")!!.startsWith("text/html"))
            assertEquals("no-cache", it.header("Cache-Control"))
        }
        get("/editor/").use { assertEquals("<!doctype html>fake editor", it.body!!.string()) }
        get("/editor/assets/main.js").use {
            assertTrue(it.header("Content-Type")!!.startsWith("text/javascript"))
            assertEquals("max-age=86400", it.header("Cache-Control"))
        }
        // 跳出编辑器目录（用原始请求行，OkHttp 自己会先把 .. 化简掉）
        for (path in listOf("/editor/../secret.txt", "/editor/%2e%2e/secret.txt", "/editor/assets/../../secret.txt", "/editor/..%2fsecret.txt")) {
            val resp = raw("GET $path HTTP/1.1\r\nHost: 127.0.0.1:${gw.port}\r\nConnection: close\r\n\r\n")
            assertTrue("$path → $resp", resp.startsWith("HTTP/1.1 404") && !resp.contains("secret"))
        }
        get("/editor/missing.js").use { assertEquals(404, it.code) }
        assertEquals(0, up.requestCount) // 编辑器网页不经过服务器
    }

    @Test
    fun tokenHostAndOriginRules() {
        up.route(helloRoute { MockResponse().setBody("upstream") })
        val tok = gw.token
        get("/api/v1/call/x").use { assertEquals(401, it.code); assertTrue(it.body!!.string().contains("\"unauthorized\"")) }
        get("/api/v1/events?token=wrong").use { assertEquals(401, it.code) }
        get("/api/v1/events?token=$tok").use { assertEquals(200, it.code) }
        get("/api/v1/x", "Authorization" to "Bearer $tok").use { assertEquals(200, it.code) }
        get("/api/v1/x", "Authorization" to "Bearer $tok", "Host" to "evil.example:${gw.port}").use { assertEquals(403, it.code) }
        get("/api/v1/x", "Authorization" to "Bearer $tok", "Origin" to "https://evil.example").use { assertEquals(403, it.code) }
        get("/api/v1/x", "Authorization" to "Bearer $tok", "Origin" to "http://127.0.0.1:${gw.port}").use { assertEquals(200, it.code) }
        get("/api/v1/x", "Authorization" to "Bearer $tok", "Host" to "localhost:${gw.port}").use { assertEquals(200, it.code) }
        // POST 只认 Authorization 头；插件静态文件任何方法都可以用查询参数
        val post = { path: String -> http.newCall(Request.Builder().url(gw.baseUrl + path).post("{}".toRequestBody()).build()).execute() }
        post("/api/v1/call/x?token=$tok").use { assertEquals(401, it.code) }
        post("/plugins/p/web/x?token=$tok").use { assertEquals(200, it.code) }
    }

    @Test
    fun forwardsWithKeyAndStripsLocalCredentials() {
        up.route(helloRoute { r ->
            MockResponse().setResponseCode(201).setHeader("X-Echo-Len", r.bodySize.toString())
                .setHeader("Content-Type", "application/octet-stream").setBody(r.body)
        })
        val payload = ByteArray(300_000).also { Random(1).nextBytes(it) }
        val req = Request.Builder().url(gw.baseUrl + "/api/v1/call/fs.write?a=1&token=${gw.token}&b=%E4%B8%AD")
            .post(payload.toRequestBody("application/octet-stream".toMediaType()))
            .header("Authorization", "Bearer ${gw.token}")
            .header("Cookie", "x=1").header("Referer", "http://127.0.0.1:${gw.port}/editor/")
            .header("X-Custom", "keep")
            .build()
        http.newCall(req).execute().use {
            assertEquals(201, it.code)
            assertArrayEquals(payload, it.body!!.bytes())
        }
        up.takeRequest() // hello
        val r = up.takeRequest()
        assertEquals("/api/v1/call/fs.write?a=1&b=%E4%B8%AD", r.path)
        assertEquals("Bearer $TOKEN", r.getHeader("Authorization"))
        assertNull(r.getHeader("Cookie"))
        assertNull(r.getHeader("Referer"))
        assertNull(r.getHeader("Origin"))
        assertEquals("keep", r.getHeader("X-Custom"))
        assertEquals("application/octet-stream", r.getHeader("Content-Type"))

        // 分块编码的请求体也原样转发
        val chunked = object : RequestBody() {
            override fun contentType() = "text/plain".toMediaType()
            override fun contentLength() = -1L
            override fun writeTo(sink: BufferedSink) {
                repeat(100) { sink.writeUtf8("line $it\n") }
            }
        }
        http.newCall(Request.Builder().url(gw.baseUrl + "/api/v1/call/y").post(chunked).header("Authorization", "Bearer ${gw.token}").build())
            .execute().use { assertEquals((0 until 100).joinToString("") { "line $it\n" }, it.body!!.string()) }
        // 同一条连接上连续请求（keep-alive）
        repeat(3) {
            get("/api/v1/z?token=${gw.token}").use { assertEquals(201, it.code) }
        }
    }

    @Test
    fun eventStreamIsNotBuffered() {
        up.route(helloRoute {
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: 1\n\ndata: 2\n\n").throttleBody(9, 3, TimeUnit.SECONDS)
        })
        val start = System.nanoTime()
        get("/api/v1/events?token=${gw.token}").use {
            val src = it.body!!.source()
            assertEquals("data: 1", src.readUtf8LineStrict())
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue("first event took $ms ms", ms < 2500)
            assertEquals("close", it.header("Connection"))
        }
    }

    @Test
    fun upstreamErrors() {
        up.route(helloRoute { json("""{"error":{"code":"unauthorized","message":"invalid or revoked access key"}}""", 401) })
        get("/api/v1/call/x?token=${gw.token}").use {
            assertEquals(403, it.code)
            assertTrue(it.body!!.string().contains("remote_unauthorized"))
        }
        assertEquals(ApiException.UNAUTHORIZED, l.problems.poll(2, TimeUnit.SECONDS)?.code)

        val deadClient = RemoteClient(ServerTarget.of(listOf(deadUrl()), TestCerts.fp(TestCerts.selfSigned)), TOKEN)
        LocalGateway(deadClient).start().use { g ->
            http.newCall(Request.Builder().url(g.baseUrl + "/api/v1/events?token=${g.token}").build()).execute().use {
                assertEquals(502, it.code)
                assertTrue(it.body!!.string().contains("\"unreachable\""))
            }
        }
    }

    private fun raw(request: String): String = java.net.Socket(TestCerts.loopback, gw.port).use { sock ->
        sock.soTimeout = 5000
        sock.getOutputStream().write(request.toByteArray())
        sock.getInputStream().readBytes().decodeToString()
    }

    @Test
    fun malformedRequests() {
        assertTrue(raw("NONSENSE\r\n\r\n").startsWith("HTTP/1.1 400"))
        assertTrue(raw("GET /healthz HTTP/1.1\r\nHost: 127.0.0.1:${gw.port}\r\nTransfer-Encoding: gzip\r\n\r\n").startsWith("HTTP/1.1 400"))
        // HTTP/1.0：响应后关闭连接
        assertTrue(raw("GET /healthz HTTP/1.0\r\nHost: 127.0.0.1:${gw.port}\r\n\r\n").endsWith("ok"))
    }

    @Test
    fun urls() {
        val t = gw.token
        assertEquals("${gw.baseUrl}/plugins/p/index.html?projectId=a&moduleId=&token=$t",
            gw.url("/plugins/p/index.html?projectId=a&moduleId=&token=remote"))
        assertEquals("${gw.baseUrl}/plugins/p/index.html?projectId=a&token=$t#x",
            gw.url("http://127.0.0.1:47123/plugins/p/index.html?token=remote&projectId=a#x"))
        assertEquals("${gw.baseUrl}/plugins/p/?token=$t", gw.url("plugins/p/"))
        val e = gw.editorUrl("terminal.html", mapOf("id" to "a b"), "theme=dark")
        assertEquals("${gw.baseUrl}/editor/terminal.html?id=a+b&token=$t#theme=dark", e)
        assertEquals("a b", LocalGateway.queryParam("id=a+b&token=x", "id"))
        assertEquals("a=1&b=2", LocalGateway.stripToken("a=1&token=x&b=2&token=y"))
        assertEquals("assets/a.js", LocalGateway.cleanRelative("./x/../assets//a.js"))
        assertEquals("index.html", LocalGateway.cleanRelative(""))
    }
}
