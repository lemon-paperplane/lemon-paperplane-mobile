package cn.lemonit.paperplane.net

import cn.lemonit.paperplane.kit.ApiException
import cn.lemonit.paperplane.kit.PaperplaneJson
import cn.lemonit.paperplane.kit.jsonObjectOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class RemoteClientTest {
    private val servers = ArrayList<okhttp3.mockwebserver.MockWebServer>()

    private fun server(held: okhttp3.tls.HeldCertificate = TestCerts.selfSigned, withRoot: Boolean = false) =
        tlsServer(held, withRoot).also { servers += it }

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.shutdown() } }
    }

    @Test
    fun callSendsKeyAndDecodesEnvelope() {
        val s = server()
        s.route(helloRoute { r ->
            when (r.path) {
                "/api/v1/call/system.info" -> json("""{"result":{"apiVersion":1,"os":"linux"}}""")
                "/api/v1/call/workspace.delete" -> json("""{"result":null}""")
                else -> json("""{"error":{"code":"not_found","message":"no such op"}}""", 404)
            }
        })
        val c = client(s)
        val info = blocking { c.callRaw("system.info", jsonObjectOf("x" to 1)) }
        assertEquals(1, info.jsonObject["apiVersion"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, blocking { c.callRaw("workspace.delete") })
        val e = expectApi { blocking { c.callRaw("nope") } }
        assertEquals(ApiException.NOT_FOUND, e.code)
        assertEquals(404, e.status)
        // 探测用 hello，调用带密钥、不带 Origin
        assertEquals("/api/v1/auth/hello", s.takeRequest().path)
        val r = s.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/v1/call/system.info", r.path)
        assertEquals("Bearer $TOKEN", r.getHeader("Authorization"))
        assertNull(r.getHeader("Origin"))
        assertEquals("""{"x":1}""", r.body.readUtf8())
        c.close()
    }

    @Test
    fun fingerprintMismatch() {
        val s = server(TestCerts.other)
        s.route(helloRoute { json("""{"result":{}}""") })
        val l = RecordingListener()
        val c = client(s, listener = l)
        val e = expectApi { blocking { c.connect() } }
        assertEquals(ApiException.FINGERPRINT_MISMATCH, e.code)
        assertEquals(TestCerts.fp(TestCerts.other), e.actualFingerprint)
        assertEquals(ApiException.FINGERPRINT_MISMATCH, l.problems.poll(2, TimeUnit.SECONDS)?.code)
        assertEquals(0, s.requestCount) // 握手就断了，什么都没发
    }

    @Test
    fun failoverAcrossAddresses() {
        val s = server()
        s.route(helloRoute { json("""{"result":{"hostname":"h"}}""") })
        val l = RecordingListener()
        val dead = deadUrl()
        val c = client(s, listener = l, urls = listOf(dead, "https://127.0.0.1:1", s.base()))
        blocking { c.connect() }
        assertEquals(s.base(), c.activeUrl)
        assertEquals(s.base(), l.urls.poll(1, TimeUnit.SECONDS))
        // 上次能用的地址先试：只有它被探测
        val c2 = RemoteClient(ServerTarget.of(listOf(dead, s.base()), TestCerts.fp(TestCerts.selfSigned), preferredUrl = s.base()), TOKEN)
        blocking { c2.connect() }
        assertEquals(s.base(), c2.activeUrl)
    }

    @Test
    fun switchesAddressWhenActiveDies() {
        val a = server()
        val b = server()
        for (x in listOf(a, b)) x.route(helloRoute { json("""{"result":"${x.port}"}""") })
        val c = client(a, urls = listOf(a.base(), b.base()))
        assertEquals("${a.port}", blocking { c.connect() }.jsonPrimitive.content)
        a.shutdown()
        c.http.connectionPool.evictAll()
        // 请求还没发出去就失败（连接被拒）：重新探测，换到 b 上重试
        assertEquals("${b.port}", blocking { c.callRaw("x") }.jsonPrimitive.content)
        assertEquals(b.base(), c.activeUrl)
    }

    @Test
    fun allAddressesDown() {
        val c = RemoteClient(ServerTarget.of(listOf(deadUrl(), deadUrl()), TestCerts.fp(TestCerts.selfSigned)), TOKEN)
        val e = expectApi { blocking { c.connect() } }
        assertEquals(ApiException.NETWORK, e.code)
        assertEquals(NetErrors.REASON_REFUSED, e.reason)
    }

    @Test
    fun unauthorizedAndBannedAndIncompatible() {
        val s = server()
        s.route { r ->
            when (r.path) {
                "/api/v1/auth/hello" -> json(helloJson())
                "/api/v1/call/a" -> json("""{"error":{"code":"unauthorized","message":"invalid or revoked access key"}}""", 401)
                else -> json("""{"error":{"code":"rate_limited","message":"too many"}}""", 429).setHeader("Retry-After", "900")
            }
        }
        val l = RecordingListener()
        val c = client(s, listener = l)
        assertEquals(ApiException.UNAUTHORIZED, expectApi { blocking { c.callRaw("a") } }.code)
        assertEquals(ApiException.UNAUTHORIZED, l.problems.poll(1, TimeUnit.SECONDS)?.code)
        val ban = expectApi { blocking { c.callRaw("b") } }
        assertEquals(ApiException.BANNED, ban.code)
        assertEquals(900, ban.retryAfterSeconds)

        val old = server()
        old.route { json(helloJson(api = 2)) }
        val e = expectApi { blocking { client(old).connect() } }
        assertEquals(ApiException.INCOMPATIBLE, e.code)

        val web = server()
        web.route { MockResponse().setResponseCode(404).setBody("not found") }
        assertEquals(NetErrors.REASON_NOT_PAPERPLANE, expectApi { blocking { client(web).connect() } }.reason)
    }

    @Test
    fun cancellationPropagates() {
        val s = server()
        s.route(helloRoute { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) })
        val c = client(s)
        runBlocking {
            val job = async(Dispatchers.Default) { c.callRaw("slow") }
            delay(500)
            job.cancel()
            val err = runCatching { job.await() }.exceptionOrNull()
            assertTrue("got $err", err is CancellationException)
        }
        // 超时 → network/timeout
        val e = expectApi { blocking { c.callRaw("slow", timeoutMs = 500) } }
        assertEquals(ApiException.NETWORK, e.code)
        assertEquals(NetErrors.REASON_TIMEOUT, e.reason)
    }

    @Test
    fun trustedCertificate() {
        val s = server(TestCerts.caLeaf, withRoot = true)
        s.route(helloRoute { json("""{"result":1}""") })
        val opts = RemoteClient.Options(systemTrust = TestCerts.trustRoot)
        // CA 证书 + 主机名相符：不需要指纹
        val ok = RemoteClient(ServerTarget.of(listOf(s.base("localhost")), "", trusted = true), TOKEN, opts)
        assertEquals(1, blocking { ok.callRaw("x") }.jsonPrimitive.int)
        // IP 地址不在证书里：主机名校验失败
        val byIp = RemoteClient(ServerTarget.of(listOf(s.base()), "", trusted = true), TOKEN, opts)
        assertEquals(NetErrors.REASON_CERT_UNTRUSTED, expectApi { blocking { byIp.callRaw("x") } }.reason)
        // 同时保存了指纹：指纹对得上就不看主机名（局域网 IP 也能连）
        val pinned = RemoteClient(ServerTarget.of(listOf(s.base()), TestCerts.fp(TestCerts.caLeaf), trusted = true), TOKEN, opts)
        assertEquals(1, blocking { pinned.callRaw("x") }.jsonPrimitive.int)
        // 不受信任的 CA：系统信任库里没有 → 只有指纹能救
        val untrusted = RemoteClient(ServerTarget.of(listOf(s.base("localhost")), "", trusted = true), TOKEN)
        assertEquals(ApiException.NETWORK, expectApi { blocking { untrusted.callRaw("x") } }.code)
    }

    @Test
    fun pluginFileAndOps() {
        val s = server()
        s.route(helloRoute { r ->
            when {
                r.path == "/api/v1/ops" -> json("""[{"name":"system.info"}]""")
                r.path!!.startsWith("/api/v1/plugins/") -> MockResponse().setBody("<svg/>").setHeader("X-Path", r.path!!)
                else -> json("{}", 404)
            }
        })
        val c = client(s)
        assertEquals(1, (blocking { c.ops() } as JsonArray).size)
        assertEquals("<svg/>", blocking { c.pluginFile("my plugin", "icons/a b.svg") }.decodeToString())
        s.takeRequest()
        s.takeRequest()
        assertEquals("/api/v1/plugins/my%20plugin/files/icons/a%20b.svg", s.takeRequest().path)
        val req = c.authorizedRequest("/plugins/p/icons/x.svg")
        assertEquals("Bearer $TOKEN", req.header("Authorization"))
        assertEquals(s.base() + "/plugins/p/icons/x.svg", req.url.toString())
    }

    @Test
    fun streamsUseKeyAndReportProblems() {
        val s = server()
        s.route(helloRoute { json("""{"error":{"code":"unauthorized","message":"x"}}""", 401) })
        val l = RecordingListener()
        val c = client(s, listener = l)
        val e = expectApi { blocking { c.openStream("/api/v1/events") } }
        assertEquals(ApiException.UNAUTHORIZED, e.code)
        assertEquals(ApiException.UNAUTHORIZED, l.problems.poll(1, TimeUnit.SECONDS)?.code)
    }
}

class RemoteAuthTest {
    private val servers = ArrayList<okhttp3.mockwebserver.MockWebServer>()

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.shutdown() } }
    }

    private fun server(held: okhttp3.tls.HeldCertificate = TestCerts.selfSigned, withRoot: Boolean = false) =
        tlsServer(held, withRoot).also { servers += it }

    @Test
    fun probeSelfSigned() {
        val s = server()
        s.route { json(helloJson(passwordLogin = true)) }
        val p = blocking { RemoteAuth().probe("127.0.0.1:${s.port}") }
        assertEquals(s.base(), p.url)
        assertEquals(false, p.trusted)
        assertEquals(TestCerts.fp(TestCerts.selfSigned), p.fingerprint)
        assertEquals(Fingerprints.display(p.fingerprint), p.fingerprintDisplay)
        assertEquals("srv-host", p.hello.hostname)
        assertEquals(true, p.hello.passwordLogin)
        assertEquals("linux", p.hello.os)
    }

    @Test
    fun probeTrusted() {
        val s = server(TestCerts.caLeaf, withRoot = true)
        s.route { json(helloJson()) }
        val auth = RemoteAuth(RemoteAuth.Options(systemTrust = TestCerts.trustRoot))
        val p = blocking { auth.probe("https://localhost:${s.port}") }
        assertEquals(true, p.trusted)
        assertEquals(TestCerts.fp(TestCerts.caLeaf), p.fingerprint)
        // 用 IP 访问：主机名不符 → 当成自签，交给用户核对
        assertEquals(false, blocking { auth.probe("127.0.0.1:${s.port}") }.trusted)
    }

    @Test
    fun probeErrors() {
        val web = server()
        web.route { MockResponse().setResponseCode(404) }
        assertEquals(NetErrors.REASON_NOT_PAPERPLANE, expectApi { blocking { RemoteAuth().probe(web.base()) } }.reason)
        assertEquals(NetErrors.REASON_REFUSED, expectApi { blocking { RemoteAuth().probe(deadUrl()) } }.reason)
        assertEquals(ApiException.USER_ERROR, expectApi { blocking { RemoteAuth().probe("http://x") } }.code)
        val banned = server()
        banned.route { json("""{"error":{"code":"rate_limited","message":"x"}}""", 429) }
        assertEquals(ApiException.BANNED, expectApi { blocking { RemoteAuth().probe(banned.base()) } }.code)
    }

    @Test
    fun login() {
        val s = server()
        s.route { r ->
            if (r.path == "/api/v1/auth/hello") return@route json(helloJson(passwordLogin = true))
            val body = PaperplaneJson.parseToJsonElement(r.body.clone().readUtf8()).jsonObject
            when (body["password"]!!.jsonPrimitive.content) {
                "correct horse battery" -> json("""{"token":"$TOKEN","keyId":"0123abcd"}""")
                "disabled!!" -> json("""{"error":{"code":"password_disabled","message":"x"}}""", 403)
                "banned!!!!" -> json("""{"error":{"code":"rate_limited","message":"x"}}""", 429)
                else -> json("""{"error":{"code":"unauthorized","message":"wrong account or password"}}""", 401)
            }
        }
        val fp = TestCerts.fp(TestCerts.selfSigned)
        val auth = RemoteAuth()
        val ok = blocking { auth.login(s.base(), fp, false, "alice", "correct horse battery", "Pixel") }
        assertEquals(TOKEN, ok.token)
        assertEquals("0123abcd", ok.keyId)
        s.takeRequest()
        val req = s.takeRequest()
        assertEquals("/api/v1/auth/login", req.path)
        assertEquals("Pixel", PaperplaneJson.parseToJsonElement(req.body.readUtf8()).jsonObject["device"]!!.jsonPrimitive.content)
        val wrong = expectApi { blocking { auth.login(s.base(), fp, false, "alice", "nope", "Pixel") } }
        assertEquals(ApiException.UNAUTHORIZED, wrong.code)
        assertEquals(NetErrors.REASON_BAD_LOGIN, wrong.reason)
        assertEquals(ApiException.PASSWORD_DISABLED, expectApi { blocking { auth.login(s.base(), fp, false, "a", "disabled!!", "d") } }.code)
        assertEquals(ApiException.BANNED, expectApi { blocking { auth.login(s.base(), fp, false, "a", "banned!!!!", "d") } }.code)
        // 证书变了
        val e = expectApi { blocking { auth.login(s.base(), TestCerts.fp(TestCerts.other), false, "a", "b", "d") } }
        assertEquals(ApiException.FINGERPRINT_MISMATCH, e.code)
        assertEquals(fp, e.actualFingerprint)
    }

    @Test
    fun connectWithCode() {
        val s = server()
        s.route(helloRoute { json("""{"result":{"hostname":"srv-host","os":"linux"}}""") })
        val code = ConnectionCode(listOf(deadUrl(), "127.0.0.1:${s.port}"), TestCerts.fp(TestCerts.selfSigned), TOKEN, "srv")
        val r = blocking { RemoteAuth().connectWithCode(ConnectionCode.decode(ConnectionCode.encode(code))) }
        assertEquals(s.base(), r.url)
        assertEquals("0123abcd", r.keyId)
        assertEquals("srv-host", r.info.jsonObject["hostname"]!!.jsonPrimitive.content)
        assertEquals(2, r.target.urls.size)
        // 改过的地址放最前
        val r2 = blocking { RemoteAuth().connectWithCode(code, firstAddress = s.base()) }
        assertEquals(s.base(), r2.target.urls.first())
        // 密钥不对：不再试别的地址，直接报 unauthorized
        s.route(helloRoute { json("""{"error":{"code":"unauthorized","message":"x"}}""", 401) })
        assertEquals(ApiException.UNAUTHORIZED, expectApi { blocking { RemoteAuth().connectWithCode(code) } }.code)
    }
}
