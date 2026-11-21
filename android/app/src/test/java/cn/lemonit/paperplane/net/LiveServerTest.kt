package cn.lemonit.paperplane.net

import cn.lemonit.paperplane.kit.ApiException
import cn.lemonit.paperplane.kit.PaperplaneJson
import cn.lemonit.paperplane.kit.jsonObjectOf
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 联调测试：起一个真的内核（go build 出来的 paperplane serve，临时数据目录），用网络层连上去走一遍。
 * 只在设置了 PAPERPLANE_TEST_BIN 时运行：
 *
 *     cd lemon-paperplane-main-core && go build -o <scratchpad>/pp ./cmd/paperplane
 *     PAPERPLANE_TEST_BIN=<scratchpad>/pp ./gradlew testDebugUnitTest
 *
 * 认证失败 5 次会封 IP：各用例里失败之后都跟一次成功调用（清零），封禁用例放最后。
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LiveServerTest {
    companion object {
        private var proc: Process? = null
        private lateinit var bin: String
        private lateinit var dataDir: File
        private var port = 0
        private lateinit var code: ConnectionCode
        private lateinit var admin: RemoteClient
        private const val PASSWORD = "correct horse battery"

        private val url get() = "https://127.0.0.1:$port"

        @BeforeClass
        @JvmStatic
        fun startServer() {
            val b = System.getenv("PAPERPLANE_TEST_BIN")
            Assume.assumeTrue("PAPERPLANE_TEST_BIN not set: live tests skipped", !b.isNullOrBlank())
            bin = b!!
            dataDir = Files.createTempDirectory("pp-live").toFile()
            port = freePort()
            proc = ProcessBuilder(bin, "serve", "--listen", "127.0.0.1:$port").apply {
                environment().putAll(env())
                redirectErrorStream(true)
                redirectOutput(File(dataDir, "serve.log"))
            }.start()
            val deadline = System.currentTimeMillis() + 30_000
            while (true) {
                val ok = runCatching { blocking(5_000) { RemoteAuth().probe("127.0.0.1:$port") } }.isSuccess
                if (ok) break
                if (System.currentTimeMillis() > deadline || proc?.isAlive != true) {
                    throw AssertionError("server did not start:\n" + File(dataDir, "serve.log").readText())
                }
                Thread.sleep(200)
            }
            code = ConnectionCode.decode(cli("server", "key", "add", "android-test").lines().first { it.startsWith(ConnectionCode.PREFIX) })
            cli("server", "user", "set", "alice", "--stdin", input = "$PASSWORD\n")
            cli("server", "password-login", "on")
            admin = RemoteClient(ServerTarget.of(code.urls, code.fingerprint), code.token)
        }

        @AfterClass
        @JvmStatic
        fun stopServer() {
            if (::admin.isInitialized) admin.close()
            proc?.let { p ->
                p.destroy()
                if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            }
            if (::dataDir.isInitialized) dataDir.deleteRecursively()
        }

        /** 数据目录一律临时目录（本机装着正式版在跑，绝不能碰默认数据目录） */
        private fun env() = mapOf(
            "PAPERPLANE_DATA_DIR" to dataDir.path,
            "PAPERPLANE_NO_SHELL_ENV" to "1",
            "PAPERPLANE_NO_UI" to "1",
            "SHELL" to "/bin/sh",
        )

        private fun cli(vararg args: String, input: String? = null): String {
            val p = ProcessBuilder(bin, *args).apply {
                environment().putAll(env())
                redirectErrorStream(true)
            }.start()
            p.outputStream.use { o -> input?.let { o.write(it.toByteArray()) } }
            val out = p.inputStream.readBytes().decodeToString()
            assertTrue("cli ${args.toList()} timed out", p.waitFor(30, TimeUnit.SECONDS))
            assertEquals("cli ${args.toList()}: $out", 0, p.exitValue())
            return out
        }
    }

    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(15, TimeUnit.SECONDS).build()

    private fun workspaceId(c: RemoteClient): String =
        blocking { c.callRaw("workspace.list") }.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

    @Test
    fun t01_helloAndProbe() {
        assertTrue(code.urls.contains(url))
        assertEquals(code.keyId, code.token.substring(4, 12))
        val p = blocking { RemoteAuth().probe("127.0.0.1:$port") }
        assertEquals(url, p.url)
        assertFalse(p.trusted)
        assertTrue(Fingerprints.same(p.fingerprint, code.fingerprint))
        assertEquals(REMOTE_API_VERSION, p.hello.apiVersion)
        assertTrue(p.hello.passwordLogin)
        assertTrue(p.hello.os.isNotEmpty() && p.hello.hostname.isNotEmpty())
        assertEquals(p.hello, blocking { RemoteAuth().hello(url, code.fingerprint) })
    }

    @Test
    fun t02_connectWithCodeAndCall() {
        val conn = blocking { RemoteAuth().connectWithCode(code, firstAddress = deadUrl()) }
        assertEquals(url, conn.url) // 先试的地址连不上，换到连接码里的
        assertEquals(1, conn.info.jsonObject["apiVersion"]!!.jsonPrimitive.int)
        assertEquals("/", conn.info.jsonObject["pathSeparator"]!!.jsonPrimitive.content)
        val l = RecordingListener()
        val c = RemoteClient(conn.target, conn.token, listener = l)
        val info = blocking { c.connect() }
        assertTrue(info.jsonObject["hostname"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals(url, l.urls.poll(1, TimeUnit.SECONDS))
        val ws = blocking { c.callRaw("workspace.list") } as JsonArray
        assertTrue(ws.isNotEmpty())
        val ops = blocking { c.ops() } as JsonArray
        assertTrue(ops.any { it.jsonObject["name"]?.jsonPrimitive?.content == "system.info" })
        // 远程不能调 gateway.*
        assertNotEquals(null, runCatching { blocking { c.callRaw("gateway.decode", jsonObjectOf("code" to "x")) } }.exceptionOrNull())
        // 服务器的错误信封原样带回
        val e = expectApi { blocking { c.callRaw("project.get", jsonObjectOf("projectId" to "nope")) } }
        assertEquals(ApiException.NOT_FOUND, e.code)
        c.close()
    }

    @Test
    fun t03_eventStream() {
        val c = RemoteClient(ServerTarget.of(code.urls, code.fingerprint), code.token)
        val es = EventStream(c)
        val col = Collector<EventStream.Update>()
        es.start(col.add)
        col.nextOf<EventStream.Update.Connected>()
        val hello = col.nextOf<EventStream.Update.Event>()
        assertEquals("hello", hello.type)
        assertEquals(1, hello.data.jsonObject["apiVersion"]!!.jsonPrimitive.int)
        blocking { c.callRaw("workspace.create", jsonObjectOf("name" to "live-${System.nanoTime()}")) }
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            val e = col.nextOf<EventStream.Update.Event>(deadline - System.currentTimeMillis())
            if (e.type == "workspaces.changed") break
        }
        // 断开后重连：重新收到 hello
        es.stop()
        es.start(col.add)
        col.nextOf<EventStream.Update.Connected>()
        assertEquals("hello", col.nextOf<EventStream.Update.Event>().type)
        es.stop()
        c.close()
    }

    /** 本机 TCP 中继：可以随时切断经过它的全部连接（模拟断网），监听保留 */
    private class Relay(private val target: Int) : AutoCloseable {
        private val ss = ServerSocket(0, 16, TestCerts.loopback)
        private val conns = ConcurrentHashMap.newKeySet<Socket>()
        val port get() = ss.localPort

        init {
            thread(isDaemon = true) {
                while (true) {
                    val a = try {
                        ss.accept()
                    } catch (e: IOException) {
                        break
                    }
                    val b = Socket(TestCerts.loopback, target)
                    conns += a
                    conns += b
                    for ((x, y) in listOf(a to b, b to a)) thread(isDaemon = true) {
                        try {
                            x.getInputStream().copyTo(y.getOutputStream())
                        } catch (e: IOException) {
                        }
                        runCatching { x.close() }
                        runCatching { y.close() }
                    }
                }
            }
        }

        fun dropAll() {
            conns.forEach { runCatching { it.close() } }
            conns.clear()
        }

        override fun close() {
            ss.close()
            dropAll()
        }
    }

    /** 终端流的接收端：输出全部按顺序记下（不会因为等别的更新而丢掉），其余更新进队列 */
    private class TermSink {
        private val sb = StringBuilder()
        private val offs = ArrayList<Long>()
        val events = Collector<TerminalStream.Update>()
        val listener: (TerminalStream.Update) -> Unit = { u ->
            if (u is TerminalStream.Update.Output) {
                synchronized(this) {
                    offs += u.offset
                    sb.append(String(u.data))
                }
            }
            if (u is TerminalStream.Update.Disconnected && !u.retrying) throw AssertionError("stream stopped: ${u.error}")
            events.add(u)
        }

        fun text() = synchronized(this) { sb.toString() }
        fun offsets() = synchronized(this) { offs.toList() }

        fun waitFor(what: String, pred: (String) -> Boolean) {
            val deadline = System.currentTimeMillis() + 15_000
            while (!pred(text())) {
                if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what, got: ${text()}")
                Thread.sleep(30)
            }
        }
    }

    @Test
    fun t04_terminalResumesAfterDisconnect() {
        val work = File(dataDir, "work").apply { mkdirs() }
        Relay(port).use { relay ->
            val c = RemoteClient(ServerTarget.of(listOf("https://127.0.0.1:${relay.port}"), code.fingerprint), code.token)
            val t = blocking {
                c.callRaw("terminal.create", jsonObjectOf("workspaceId" to workspaceId(c), "cwd" to work.path, "cols" to 80, "rows" to 24))
            }.jsonObject
            val id = t["id"]!!.jsonPrimitive.content
            val input = { s: String -> blocking { c.callRaw("terminal.input", jsonObjectOf("id" to id, "data" to s)) } }
            val ts = TerminalStream(c, id)
            val sink = TermSink()
            ts.start(sink.listener)
            assertEquals(id, sink.events.nextOf<TerminalStream.Update.State>().terminal["id"]!!.jsonPrimitive.content)
            input("echo mark\$((40+2))\r")
            sink.waitFor("mark42") { it.contains("mark42") }
            // 切断连接（不是 stop）：流自己重连，带着末尾偏移续传
            relay.dropAll()
            assertTrue(sink.events.nextOf<TerminalStream.Update.Disconnected>().retrying)
            input("echo two\$((1+1))\r")
            sink.events.nextOf<TerminalStream.Update.Connected>()
            sink.waitFor("two2") { it.contains("two2") }
            Thread.sleep(300) // 等提示符等剩余输出到齐
            val text = sink.text()
            assertEquals("no duplicate output: $text", 1, Regex("mark42").findAll(text).count())
            // 偏移连续：从 0 开始拼起来的字节数 = 末尾偏移，且每段起点 = 上一段终点
            assertEquals(0L, sink.offsets().first())
            assertEquals(ts.offset, text.toByteArray().size.toLong())
            ts.stop()

            // stop 后再 start：从记住的位置续上
            val before = ts.offset
            input("echo three\$((1+2))\r")
            val sink2 = TermSink()
            ts.start(sink2.listener)
            sink2.waitFor("three3") { it.contains("three3") }
            assertFalse(sink2.text().contains("two2"))
            assertEquals(before, sink2.offsets().first())
            ts.stop()

            // full=1 从头完整回放
            val all = TerminalStream(c, id, since = 0, full = true)
            val sink3 = TermSink()
            all.start(sink3.listener)
            sink3.waitFor("full replay") { it.contains("mark42") && it.contains("two2") && it.contains("three3") }
            assertEquals(0L, sink3.offsets().first())
            all.stop()

            blocking { c.callRaw("terminal.close", jsonObjectOf("id" to id)) }
            c.close()
        }
    }

    @Test
    fun t05_consoleStream() {
        val c = RemoteClient(ServerTarget.of(code.urls, code.fingerprint), code.token)
        val proj = File(dataDir, "proj").apply { mkdirs() }
        val p = blocking { c.callRaw("project.import", jsonObjectOf("path" to proj.path, "workspaceId" to workspaceId(c))) }.jsonObject
        val task = blocking { c.callRaw("project.reimport", jsonObjectOf("projectId" to p["id"]!!.jsonPrimitive.content)) }.jsonObject
        val taskId = task["id"]!!.jsonPrimitive.content
        val cs = ConsoleStream(c, taskId)
        val updates = blocking { cs.updates().toList() }
        assertTrue(updates.any { it is ConsoleStream.Update.Lines && it.lines.isNotEmpty() })
        assertTrue(updates.last() is ConsoleStream.Update.Ended)
        assertTrue(cs.lastSeq >= 1)
        // 从最后的 seq 续传：只剩 end（新内核连上后的第一条消息可能只带实时尾部，没有定型行）
        val again = ConsoleStream(c, taskId, since = cs.lastSeq)
        val rest = blocking { again.updates().toList() }
        assertFalse(rest.any { it is ConsoleStream.Update.Lines && it.lines.isNotEmpty() })
        assertTrue(rest.last() is ConsoleStream.Update.Ended)
        c.close()
    }

    @Test
    fun t06_passwordLogin() {
        val auth = RemoteAuth()
        val p = blocking { auth.probe(url) }
        val r = blocking { auth.login(p.url, p.fingerprint, p.trusted, "alice", PASSWORD, "android-test-device") }
        assertTrue(r.keyId.isNotEmpty())
        assertEquals(r.keyId, ConnectionCode.keyIdOf(r.token))
        val c = RemoteClient(ServerTarget.of(listOf(p.url), p.fingerprint, p.trusted), r.token)
        assertEquals(1, blocking { c.connect() }.jsonObject["apiVersion"]!!.jsonPrimitive.int)
        val keys = blocking { c.callRaw("remote.keys") }.jsonArray.map { it.jsonObject }
        val mine = keys.first { it["id"]!!.jsonPrimitive.content == r.keyId }
        assertEquals("device", mine["kind"]!!.jsonPrimitive.content)
        assertEquals("alice", mine["user"]!!.jsonPrimitive.content)
        assertEquals("true", mine["current"]!!.jsonPrimitive.content)
        // 错密码
        val e = expectApi { blocking { auth.login(p.url, p.fingerprint, false, "alice", "wrong password!!", "x") } }
        assertEquals(ApiException.UNAUTHORIZED, e.code)
        assertEquals(NetErrors.REASON_BAD_LOGIN, e.reason)
        blocking { c.callRaw("system.info") } // 成功一次，失败计数清零
        c.close()
    }

    @Test
    fun t07_revokedKey() {
        val created = blocking { admin.callRaw("remote.keyCreate", jsonObjectOf("name" to "temp-phone")) }.jsonObject
        val token = created["token"]!!.jsonPrimitive.content
        val keyId = created["key"]!!.jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(keyId, ConnectionCode.decode(created["code"]!!.jsonPrimitive.content).keyId)
        val l = RecordingListener()
        val c = RemoteClient(ServerTarget.of(code.urls, code.fingerprint), token, listener = l)
        blocking { c.connect() }
        val es = EventStream(c)
        val col = Collector<EventStream.Update>()
        es.start(col.add)
        col.nextOf<EventStream.Update.Event>()
        LocalGateway(c).start().use { gw ->
            val call = {
                http.newCall(Request.Builder().url(gw.baseUrl + "/api/v1/call/system.info")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .header("Authorization", "Bearer ${gw.token}").build()).execute()
            }
            call().use { assertEquals(200, it.code) }

            blocking { admin.callRaw("remote.keyRevoke", jsonObjectOf("id" to keyId)) }

            // 长连接被服务器切断，重连拿到 401 后不再重连
            val deadline = System.currentTimeMillis() + 10_000
            var fatal: EventStream.Update.Disconnected
            do {
                fatal = col.nextOf<EventStream.Update.Disconnected>(deadline - System.currentTimeMillis())
            } while (fatal.retrying)
            assertEquals(ApiException.UNAUTHORIZED, fatal.error.code)
            assertEquals(ApiException.UNAUTHORIZED, expectApi { blocking { c.callRaw("system.info") } }.code)
            assertEquals(ApiException.UNAUTHORIZED, l.problems.poll(2, TimeUnit.SECONDS)?.code)
            call().use {
                assertEquals(403, it.code)
                assertTrue(it.body!!.string().contains("remote_unauthorized"))
            }
        }
        es.stop()
        c.close()
        blocking { admin.callRaw("system.info") } // 失败计数清零
    }

    @Test
    fun t08_wrongFingerprint() {
        val bad = "sha256:" + "0".repeat(64)
        val c = RemoteClient(ServerTarget.of(code.urls, bad), code.token)
        val e = expectApi { blocking { c.connect() } }
        assertEquals(ApiException.FINGERPRINT_MISMATCH, e.code)
        assertTrue(Fingerprints.same(e.actualFingerprint!!, code.fingerprint))
        val e2 = expectApi { blocking { RemoteAuth().login(url, bad, false, "alice", PASSWORD, "x") } }
        assertEquals(ApiException.FINGERPRINT_MISMATCH, e2.code)
        c.close()
    }

    @Test
    fun t09_localGateway() {
        val editor = Files.createTempDirectory("pp-live-editor").toFile()
        File(editor, "index.html").writeText("fake editor page")
        val c = RemoteClient(ServerTarget.of(code.urls, code.fingerprint), code.token)
        LocalGateway(c, DirectoryAssetSource(editor)).start().use { gw ->
            http.newCall(Request.Builder().url(gw.baseUrl + "/editor/index.html").build()).execute().use {
                assertEquals(200, it.code)
                assertEquals("fake editor page", it.body!!.string())
            }
            http.newCall(Request.Builder().url(gw.baseUrl + "/api/v1/call/system.info")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer ${gw.token}").build()).execute().use {
                assertEquals(200, it.code)
                val res = PaperplaneJson.parseToJsonElement(it.body!!.string()).jsonObject["result"]!!.jsonObject
                assertEquals(1, res["apiVersion"]!!.jsonPrimitive.int)
            }
            // 事件流经网关立刻送达（连接一直开着）
            val start = System.nanoTime()
            http.newCall(Request.Builder().url(gw.baseUrl + "/api/v1/events?token=${gw.token}").build()).execute().use {
                assertEquals(200, it.code)
                val line = it.body!!.source().readUtf8LineStrict()
                assertTrue(line, line.startsWith("data: ") && line.contains("\"hello\""))
                assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
            }
            // 插件静态文件（插件不存在 → 服务器 404，原样带回）
            http.newCall(Request.Builder().url(gw.url("/api/v1/plugins/none/files/icons/x.svg")).build()).execute().use {
                assertEquals(404, it.code)
            }
        }
        editor.deleteRecursively()
        c.close()
    }

    @Test
    fun t10_portForwarder() {
        // 服务器本机上的一个 HTTP 服务（测试服务器就在本机）
        val web = okhttp3.mockwebserver.MockWebServer()
        web.route { r -> okhttp3.mockwebserver.MockResponse().setBody("hello from ${r.path}") }
        web.start(TestCerts.loopback, 0)
        val c = RemoteClient(ServerTarget.of(code.urls, code.fingerprint), code.token)
        PortForwarder(c).use { pf ->
            val f = pf.forward(web.port)
            assertNotEquals(web.port, f.localPort) // 同号端口被占用（测试服务器自己就在本机）→ 随机端口
            repeat(3) { i ->
                http.newCall(Request.Builder().url("http://127.0.0.1:${f.localPort}/page$i").build()).execute().use {
                    assertEquals(200, it.code)
                    assertEquals("hello from /page$i", it.body!!.string())
                }
            }
            val threads = (0 until 4).map { i ->
                thread {
                    OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
                        .newCall(Request.Builder().url("http://127.0.0.1:${f.localPort}/p$i").build()).execute()
                        .use { assertEquals("hello from /p$i", it.body!!.string()) }
                }
            }
            threads.forEach { it.join(15_000) }
            pf.unforward(web.port)
            assertTrue(pf.forwards.isEmpty())
        }
        web.shutdown()
        c.close()
    }

    @Test
    fun t99_bannedAfterFailures() {
        val auth = RemoteAuth()
        repeat(5) {
            assertEquals(ApiException.UNAUTHORIZED, expectApi { blocking { auth.login(url, code.fingerprint, false, "alice", "wrong password $it", "x") } }.code)
        }
        val e = expectApi { blocking { auth.login(url, code.fingerprint, false, "alice", PASSWORD, "x") } }
        assertEquals(ApiException.BANNED, e.code)
        assertTrue((e.retryAfterSeconds ?: 0) > 0)
        assertEquals(ApiException.BANNED, expectApi { blocking { auth.probe(url) } }.code)
        assertEquals(ApiException.BANNED, expectApi { blocking { RemoteClient(ServerTarget.of(code.urls, code.fingerprint), code.token).connect() } }.code)
    }
}
