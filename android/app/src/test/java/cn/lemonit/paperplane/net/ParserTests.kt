package cn.lemonit.paperplane.net

import cn.lemonit.paperplane.kit.ApiException
import cn.lemonit.paperplane.kit.PaperplaneJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers.Companion.headersOf
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLHandshakeException

/** 移植 Mac ParserTests 的 SSE 部分 + events.sse.txt */
class SseParserTest {
    @Test
    fun parserFraming() {
        val p = SseParser()
        assertNull(p.feed("event: end"))
        assertNull(p.feed("data: {}"))
        assertEquals(SseMessage(event = "end", data = "{}"), p.feed(""))
        assertNull(p.feed(": ping"))
        assertNull(p.feed(""))
        assertNull(p.feed("data:a"))
        assertNull(p.feed("data: b"))
        assertEquals(SseMessage(data = "a\nb"), p.feed(""))
    }

    @Test
    fun idAndUnknownFields() {
        val p = SseParser()
        assertNull(p.feed("id: 7"))
        assertNull(p.feed("retry: 100"))
        assertNull(p.feed("data"))
        assertEquals(SseMessage(data = "", id = "7"), p.feed(""))
    }

    @Test
    fun lineSplitterAcrossChunks() {
        val s = LineSplitter()
        val all = "data: 中".toByteArray() + "文\r\n\r\ndata: x\n".toByteArray()
        // 在「中」的 UTF-8 中间切开
        val cut = "data: 中".toByteArray().size - 1
        assertEquals(emptyList<String>(), s.append(all.copyOfRange(0, cut)))
        assertEquals(listOf("data: 中文", "", "data: x"), s.append(all.copyOfRange(cut, all.size)))
    }

    @Test
    fun eventsFixture() {
        val s = LineSplitter()
        val p = SseParser()
        val events = ArrayList<JsonObject>()
        for (line in s.append(resource("events.sse.txt"))) {
            val m = p.feed(line) ?: continue
            events += PaperplaneJson.parseToJsonElement(m.data).jsonObject
        }
        assertEquals(listOf("hello", "stats", "prompt", "project.removed", "toolchains.changed"),
            events.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("en", events[0]["data"]!!.jsonObject["locale"]!!.jsonPrimitive.content)
        assertEquals(2.5, events[1]["data"]!!.jsonArray[0].jsonObject["cpu"]!!.jsonPrimitive.double, 0.0)
        assertEquals(2, events[2]["data"]!!.jsonObject["fields"]!!.jsonArray.size)
        assertEquals("p9", events[3]["data"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, events[4]["data"])
    }
}

/** 指纹算法与 Go remote.Fingerprint 交叉验证（证书和指纹由 Go 生成，模板同内核 loadOrCreateCert） */
class FingerprintTest {
    private fun pem(name: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(resource(name))) as X509Certificate

    @Test
    fun matchesGo() {
        assertEquals("sha256:deed710a899cb7950bbfcac5b7a1ddce6166dd47c0961a61641221a504e15f81", Fingerprints.of(pem("go-ec-cert.pem")))
        assertEquals("sha256:63367bd409ac2f20b5b6e03ccbde4b29f9454ba4751265bb7f0d813c358af06c", Fingerprints.of(pem("go-rsa-cert.pem")))
    }

    @Test
    fun rawSpkiEqualsEncodedKey() {
        for (h in listOf(HeldCertificate.Builder().ecdsa256().build(), HeldCertificate.Builder().rsa2048().build(), TestCerts.root)) {
            assertTrue(Fingerprints.spki(h.certificate).contentEquals(h.certificate.publicKey.encoded))
        }
        for (name in listOf("go-ec-cert.pem", "go-rsa-cert.pem")) {
            val c = pem(name)
            assertTrue(Fingerprints.spki(c).contentEquals(c.publicKey.encoded))
        }
    }

    @Test
    fun displayAndCompare() {
        val fp = "sha256:deed710a899cb7950bbfcac5b7a1ddce6166dd47c0961a61641221a504e15f81"
        val d = Fingerprints.display(fp)
        assertEquals(16, d.split(" ").size)
        assertTrue(d.startsWith("DEED 710A 899C"))
        assertEquals("DEED710A", Fingerprints.short(fp))
        // 同 Go TestTokenAndCode 的比较规则
        assertTrue(Fingerprints.same("sha256:ABCD" + "0".repeat(60), "abcd" + "0".repeat(60)))
        assertFalse(Fingerprints.same("sha256:ab", "sha256:ab"))
        assertTrue(Fingerprints.same(fp, d))
        assertFalse(Fingerprints.same(fp, "sha256:" + "0".repeat(64)))
    }
}

/** 移植 Go TestTokenAndCode 的连接码部分，并用 Go 生成的连接码交叉验证 */
class ConnectionCodeTest {
    private val goCode = "ppc1_eyJ1IjpbImh0dHBzOi8vMTkyLjE2OC4xLjIwOjQ3MjkwIiwiaHR0cHM6Ly9bMjQwODo4MDAwOjoxXTo0NzI5MCIsImh0dHBzOi8vdGVzdC1zZXJ2ZXIubG9jYWw6NDcyOTAiXSwiZiI6InNoYTI1NjpkZWVkNzEwYTg5OWNiNzk1MGJiZmNhYzViN2ExZGRjZTYxNjZkZDQ3YzA5NjFhNjE2NDEyMjFhNTA0ZTE1ZjgxIiwiayI6InBwa18wMTIzYWJjZF9BQWNPRlJ3aktqRTRQMFpOVkZ0aWFYQjNmb1dNazVxaHFLLTJ2Y1RMMHRrIiwibiI6InRlc3Qtc2VydmVyLmxvY2FsIn0"

    private fun newToken(): String {
        val id = ByteArray(4).also { SecureRandom().nextBytes(it) }.let { Fingerprints.hex(it) }
        val sec = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return "ppk_${id}_" + Base64.getUrlEncoder().withoutPadding().encodeToString(sec)
    }

    @Test
    fun decodesGoCode() {
        val c = ConnectionCode.decode(goCode)
        assertEquals(listOf("https://192.168.1.20:47290", "https://[2408:8000::1]:47290", "https://test-server.local:47290"), c.urls)
        assertEquals("sha256:deed710a899cb7950bbfcac5b7a1ddce6166dd47c0961a61641221a504e15f81", c.fingerprint)
        assertEquals(TOKEN, c.token)
        assertEquals("0123abcd", c.keyId)
        assertEquals("test-server.local", c.name)
        // 编码结果和 Go 逐字节相同
        assertEquals(goCode, ConnectionCode.encode(c))
        assertEquals(c.urls, c.normalizedUrls)
        assertFalse(c.toString().contains(TOKEN))
    }

    @Test
    fun tokenAndCode() {
        val tok = newToken()
        val code = ConnectionCode.encode(ConnectionCode(listOf("https://a:1"), "sha256:" + "0".repeat(64), tok, "srv"))
        val p = ConnectionCode.decode("  " + code.substring(0, 20) + "\n" + code.substring(20) + " ")
        assertEquals(tok, p.token)
        assertEquals("srv", p.name)
        // 二维码扫出来可能带换行、空格
        assertEquals(tok, ConnectionCode.decode(code.chunked(30).joinToString("\r\n\t ")).token)
        val bad = listOf("", "ppc1_", "ppc1_!!!", "xyz",
            ConnectionCode.encode(ConnectionCode(listOf("https://a:1"), "f", "nope")),
            ConnectionCode.encode(ConnectionCode(emptyList(), "f", tok)),
            ConnectionCode.encode(ConnectionCode(listOf("https://a:1"), "", tok)),
            code.substring(0, code.length - 10))
        for (b in bad) {
            assertNull("accepted: $b", ConnectionCode.decodeOrNull(b))
        }
        val e = try {
            ConnectionCode.decode("hello")
            null
        } catch (x: ConnectionCode.InvalidException) {
            x
        }
        assertEquals(ConnectionCode.InvalidException.Kind.PREFIX, e?.kind)
        val k = try {
            ConnectionCode.decode(ConnectionCode.encode(ConnectionCode(listOf("https://a:1"), "f", "ppk_short_x")))
            null
        } catch (x: ConnectionCode.InvalidException) {
            x
        }
        assertEquals(ConnectionCode.InvalidException.Kind.KEY, k?.kind)
    }

    @Test
    fun tokenRules() {
        assertNotNull(ConnectionCode.parseToken(TOKEN))
        assertNull(ConnectionCode.parseToken(TOKEN.replace("0123abcd", "0123abc")))
        assertNull(ConnectionCode.parseToken("ppk_0123abcd_" + "x".repeat(39)))
        assertNotNull(ConnectionCode.parseToken("ppk_0123abcd_" + "x".repeat(64)))
        assertNull(ConnectionCode.parseToken("ppk_0123abcd_" + "x".repeat(65)))
        assertNull(ConnectionCode.parseToken("ppx_0123abcd_" + "x".repeat(43)))
        assertEquals("", ConnectionCode.keyIdOf("nope"))
    }
}

/** 移植 Go TestNormalizeAddress 与 Mac ServerURLTests.normalizeAddress */
class ServerAddressTest {
    @Test
    fun normalize() {
        val cases = mapOf(
            "192.168.1.10" to "https://192.168.1.10:47290",
            "host:8443" to "https://host:8443",
            "https://example.com" to "https://example.com:443",
            "https://example.com:9000/" to "https://example.com:9000",
            "[::1]:5000" to "https://[::1]:5000",
            "fe80::1" to "https://[fe80::1]:47290",
            " nas.local:5000 " to "https://nas.local:5000",
            "https://example.com:47290/x" to "https://example.com:47290",
            "[fe80::1]:9" to "https://[fe80::1]:9",
            "HTTPS://Nas.Local:47290/" to "https://nas.local:47290",
        )
        for ((input, want) in cases) assertEquals(input, want, ServerAddress.normalize(input))
        for (bad in listOf("http://x", "host:99999", "", "   ", "host:abc", "https://:5")) {
            assertNull("accepted $bad", ServerAddress.normalize(bad))
        }
        assertEquals(listOf("https://a:47290", "https://b:1"), ServerAddress.normalizeAll(listOf("a", "A:47290", "https://b:1/", "http://c")))
        assertEquals("fe80::1", ServerAddress.host("https://[fe80::1]:47290"))
        assertEquals("nas.local", ServerAddress.host("nas.local:47290"))
        assertTrue(ServerAddress.same("NAS.local", "https://nas.local:47290/"))
    }
}

class NetErrorsTest {
    @Test
    fun envelopeErrors() {
        val e1 = NetErrors.http(400, resource("error.json"))
        assertEquals("user_error", e1.code)
        assertEquals("工程不存在", e1.message)
        assertEquals(400, e1.status)

        val withData = """{"error":{"code":"plugin_error","message":"x","data":{"reason":"identityMissing","missing":["user.email"]}}}"""
        val e2 = NetErrors.http(502, withData.toByteArray())
        assertEquals("plugin_error", e2.code)
        assertEquals("identityMissing", e2.reason)

        assertEquals(ApiException.UNAUTHORIZED, NetErrors.http(401, "unauthorized".toByteArray()).code)
        val ban = NetErrors.http(429, """{"error":{"code":"rate_limited","message":"too many"}}""".toByteArray(), headersOf("Retry-After", "120"))
        assertEquals(ApiException.BANNED, ban.code)
        assertEquals(120, ban.retryAfterSeconds)
        val ip = NetErrors.http(403, """{"error":{"code":"forbidden","message":"ip not allowed"}}""".toByteArray())
        assertEquals(ApiException.NETWORK, ip.code)
        assertEquals(NetErrors.REASON_IP_DENIED, ip.reason)
        assertEquals("http_500", NetErrors.http(500, "boom".toByteArray()).code)
        assertEquals(ApiException.NOT_FOUND, NetErrors.http(404, """{"error":{"code":"not_found","message":"x"}}""".toByteArray()).code)
        assertEquals(ApiException.NOT_FOUND, NetErrors.http(404, "404 page not found".toByteArray()).code)
    }

    @Test
    fun ioErrors() {
        assertEquals(NetErrors.REASON_REFUSED, NetErrors.map(ConnectException("Failed to connect").apply { initCause(ConnectException("Connection refused")) }).reason)
        assertEquals(NetErrors.REASON_TIMEOUT, NetErrors.map(SocketTimeoutException("connect timed out")).reason)
        assertEquals(NetErrors.REASON_TIMEOUT, NetErrors.map(java.io.InterruptedIOException("timeout")).reason)
        assertEquals(NetErrors.REASON_NO_HOST, NetErrors.map(UnknownHostException("x")).reason)
        assertEquals(NetErrors.REASON_NOT_TLS, NetErrors.map(javax.net.ssl.SSLException("Unsupported or unrecognized SSL message")).reason)
        val fp = NetErrors.map(SSLHandshakeException("bad").apply { initCause(FingerprintMismatchException("sha256:" + "a".repeat(64))) })
        assertEquals(ApiException.FINGERPRINT_MISMATCH, fp.code)
        assertEquals("sha256:" + "a".repeat(64), fp.actualFingerprint)
        assertTrue(fp.needsUserAction)
        assertEquals(ApiException.NETWORK, NetErrors.map(java.io.IOException("x")).code)
    }
}
