package cn.lemonit.paperplane.kit

import cn.lemonit.paperplane.kit.model.FSBrowseResult
import cn.lemonit.paperplane.kit.model.GatewayOpen
import cn.lemonit.paperplane.kit.model.GatewayProbe
import cn.lemonit.paperplane.kit.model.ProjectSummary
import cn.lemonit.paperplane.kit.model.RemoteKey
import cn.lemonit.paperplane.kit.model.RemoteLinks
import cn.lemonit.paperplane.kit.model.RemoteStatus
import cn.lemonit.paperplane.kit.model.SavedServer
import cn.lemonit.paperplane.kit.model.SavedServerList
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.kit.util.ServerPath
import cn.lemonit.paperplane.kit.util.ServerURL
import cn.lemonit.paperplane.kit.util.WebviewURL
import java.net.URI
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对照 Mac RemoteTests.swift 的纯模型部分（ServerPathTests、ServerURLTests、RemoteModelTests） */
class ServerPathTest {
    @Test
    fun posix() {
        val p = ServerPath.POSIX
        assertEquals("/Users/me/Code", p.join("/Users/me", "Code"))
        assertEquals("/Users/me/Code/x", p.join("/Users/me/", "Code/x"))
        assertEquals("/tmp", p.join("/", "tmp"))
        assertEquals("Code", p.lastComponent("/Users/me/Code/"))
        assertEquals("/", p.lastComponent("/"))
        assertEquals("/Users", p.parent("/Users/me"))
        assertEquals("/", p.parent("/Users"))
        assertNull(p.parent("/"))
        assertEquals("/a/b", p.normalize("/a/b//"))
        assertTrue(p.isAbsolute("/a"))
        assertFalse(p.isAbsolute("a/b"))
        assertEquals("src/x", p.relative("/p/src/x", "/p"))
        assertEquals("", p.relative("/p", "/p/"))
        assertNull(p.relative("/px/a", "/p"))
        assertEquals("~/Code", p.abbreviate("/Users/me/Code", "/Users/me"))
        assertEquals("/Users/meme", p.abbreviate("/Users/meme", "/Users/me"))
        assertEquals(ServerPath.POSIX, ServerPath(""))
    }

    @Test
    fun windows() {
        val p = ServerPath.WINDOWS
        assertEquals("C:\\Users\\me\\Code", p.join("C:\\Users\\me", "Code"))
        assertEquals("C:\\Code", p.join("C:\\", "Code"))
        assertEquals("C:\\Users\\me\\a\\b", p.join("C:\\Users\\me", "a/b"))
        assertEquals("me", p.lastComponent("C:\\Users\\me\\"))
        assertEquals("C:\\", p.lastComponent("C:\\"))
        assertEquals("C:\\", p.parent("C:\\Users"))
        assertNull(p.parent("C:\\"))
        assertEquals("D:\\", p.normalize("D:"))
        assertTrue(p.isAbsolute("C:\\x"))
        assertTrue(p.isAbsolute("\\\\nas\\share"))
        assertFalse(p.isAbsolute("x\\y"))
        assertTrue(p.same("c:\\Code\\A", "C:\\code\\a\\"))
        assertEquals("src/x", p.relative("C:\\p\\src\\x", "c:\\P"))
        assertEquals("C:\\p\\src\\main.go", p.absolute("src/main.go", "C:\\p"))
        assertEquals("~\\Code", p.abbreviate("C:\\Users\\me\\Code", "C:\\Users\\me"))
        assertTrue(ServerPath("\\").isWindows)
    }

    @Test
    fun formatUsesServerHome() {
        val saved = Format.serverHome to Format.serverPath
        try {
            Format.serverHome = "C:\\Users\\me"
            Format.serverPath = ServerPath.WINDOWS
            assertEquals("~\\x", Format.abbreviatePath("C:\\Users\\me\\x"))
            Format.serverHome = "/home/u"
            Format.serverPath = ServerPath.POSIX
            assertEquals("~/x", Format.abbreviatePath("/home/u/x"))
            assertEquals("~/x", Format.abbreviatePath("/Users/me/x", home = "/Users/me"))
        } finally {
            Format.serverHome = saved.first
            Format.serverPath = saved.second
        }
    }
}

class ServerURLTest {
    @Test
    fun parse() {
        val p = ServerURL.parse("http://localhost:8080/app?x=1")
        assertEquals("localhost", p?.host)
        assertEquals(8080, p?.port)
        assertEquals("/app?x=1", p?.rest)
        assertEquals("[fe80::1]", ServerURL.parse("https://[fe80::1]:47290")?.host)
        assertEquals(80, ServerURL.parse("http://127.0.0.1")?.effectivePort)
        assertNull(ServerURL.parse("localhost:8080"))
        assertEquals("u:p@", ServerURL.parse("http://u:p@h:1/")?.userinfo)
    }

    @Test
    fun directAndForward() {
        assertEquals("http://192.168.1.5:8080/app", ServerURL.direct("http://localhost:8080/app", "192.168.1.5"))
        assertEquals("http://nas.local:3000", ServerURL.direct("http://0.0.0.0:3000", "nas.local"))
        assertEquals("http://[fe80::1]:3000/", ServerURL.direct("http://127.0.0.1:3000/", "fe80::1"))
        assertEquals("http://example.com:3000/", ServerURL.direct("http://example.com:3000/", "nas"))
        assertEquals(5173, ServerURL.loopbackPort("http://localhost:5173/"))
        assertNull(ServerURL.loopbackPort("http://example.com:5173/"))
        assertEquals(3000, ServerURL.loopbackPort("http://app.localhost:3000"))
        assertEquals("http://localhost:18080/a", ServerURL.forwarded("http://127.0.0.1:8080/a", 18080))
        assertEquals("nas.local", ServerURL.host("https://nas.local:47290"))
        assertEquals("nas.local", ServerURL.host("nas.local:47290"))
        assertEquals("https://nas.local:47290", ServerURL.normalize("HTTPS://Nas.Local:47290/"))
        assertEquals("https://192.168.1.10:47290", ServerURL.normalizeAddress("192.168.1.10"))
        assertEquals("https://nas.local:5000", ServerURL.normalizeAddress(" nas.local:5000 "))
        assertEquals("https://example.com:443", ServerURL.normalizeAddress("https://example.com/"))
        assertEquals("https://example.com:47290", ServerURL.normalizeAddress("https://example.com:47290/x"))
        assertEquals("https://[fe80::1]:47290", ServerURL.normalizeAddress("fe80::1"))
        assertEquals("https://[fe80::1]:9", ServerURL.normalizeAddress("[fe80::1]:9"))
        assertNull(ServerURL.normalizeAddress("http://x"))
        assertNull(ServerURL.normalizeAddress("host:99999"))
        assertNull(ServerURL.normalizeAddress("host:abc"))
        assertNull(ServerURL.normalizeAddress(""))
        assertEquals("localhost:8080", ServerURL.display("http://localhost:8080/"))
    }

    private fun query(u: URI): Map<String, List<String>> =
        u.rawQuery.split('&').map { it.substringBefore('=') to it.substringAfter('=', "") }.groupBy({ it.first }, { it.second })

    @Test
    fun rebasedRelativeWebviewUrl() {
        // 远程服务器只给相对路径（不带它自己的令牌）
        val u = URI(WebviewURL.rebase("/plugins/p/index.html?projectId=a&moduleId=&runId=", "http://127.0.0.1:55001", "gw"))
        assertEquals("http", u.scheme)
        assertEquals("127.0.0.1", u.host)
        assertEquals(55001, u.port)
        assertEquals("/plugins/p/index.html", u.path)
        assertEquals(listOf("gw"), query(u)["token"])
        assertEquals(listOf("a"), query(u)["projectId"])
    }

    @Test
    fun rebasedWebviewUrl() {
        val raw = "http://127.0.0.1:47123/plugins/p/index.html?token=remote&projectId=a&moduleId=&runId=#view"
        val u = URI(WebviewURL.rebase(raw, "http://127.0.0.1:55001/", "gw"))
        assertEquals(55001, u.port)
        assertEquals("/plugins/p/index.html", u.path)
        assertEquals(listOf("gw"), query(u)["token"])
        assertEquals(listOf("a"), query(u)["projectId"])
        assertEquals("view", u.fragment)
    }
}

class RemoteModelTest {
    @Test
    fun statusAndKeys() {
        val st = PaperplaneJson.decodeFromString(
            RemoteStatus.serializer(),
            """
            {"enabled":true,"running":true,"listen":"0.0.0.0:47290","port":47290,"addresses":["https://192.168.1.5:47290"],
             "fingerprint":"sha256:ab12","fingerprintDisplay":"AB12","customCert":false,"passwordLogin":true,"autostart":false,
             "autostartSupported":true,"allowCIDRs":null,"keys":2,"users":1,"hostname":"mbp"}
            """,
        )
        assertTrue(st.running)
        assertEquals(47290, st.port)
        assertEquals(emptyList<String>(), st.allowCIDRs)
        val keys = PaperplaneJson.decodeFromString(
            ListSerializer(RemoteKey.serializer()),
            """
            [{"id":"1a2b3c4d","name":"MacBook","kind":"device","user":"me","createdAt":"2026-11-19T10:00:00.123456789+08:00",
              "lastUsedAt":"2026-11-19T11:00:00Z","lastIP":"10.0.0.2","current":true},
             {"id":"9","name":"k","kind":"key","createdAt":"2026-11-19T10:00:00Z","current":false}]
            """,
        )
        assertTrue(keys[0].isDevice)
        assertTrue(keys[0].current)
        assertNotNull(keys[0].lastUsedAt)
        assertNull(keys[1].lastUsedAt)
    }

    @Test
    fun gatewayAndBrowse() {
        val o = PaperplaneJson.decodeFromString(
            GatewayOpen.serializer(),
            """
            {"id":"abc","port":53001,"token":"t","info":{"version":"0.1","os":"windows","arch":"amd64","hostname":"WIN",
             "home":"C:\\Users\\me","pathSeparator":"\\","apiVersion":1}}
            """,
        )
        assertEquals("http://127.0.0.1:53001", o.baseUrl)
        assertEquals("\\", o.info?.pathSeparator)
        assertEquals("C:\\Users\\me", o.info?.home)
        val b = PaperplaneJson.decodeFromString(
            FSBrowseResult.serializer(),
            """
            {"path":"/Users/me","parent":"/Users","home":"/Users/me","sep":"/","roots":["/"],
             "entries":[{"name":"Code","path":"/Users/me/Code","dir":true,"hidden":false,"size":96,"modTime":"2026-11-19T10:00:00Z"}]}
            """,
        )
        assertEquals(true, b.entries.first().dir)
        val probe = PaperplaneJson.decodeFromString(
            GatewayProbe.serializer(),
            """
            {"url":"https://h:47290","fingerprint":"sha256:aa","fingerprintDisplay":"AA","trusted":false,
             "hello":{"name":"h","hostname":"h","version":"1","apiVersion":1,"os":"linux","arch":"arm64","passwordLogin":true}}
            """,
        )
        assertEquals("linux", probe.hello?.os)
    }

    @Test
    fun remoteLinksDefaults() {
        val p = PaperplaneJson.decodeFromString(ProjectSummary.serializer(), """{"id":"p","name":"x","remoteLinks":{"c1":{"forward":true,"direct":false},"c3":{"forward":false,"direct":false},"c4":{"direct":false}}}""")
        assertEquals(RemoteLinks(forward = true, direct = false), p.remoteLinks("c1"))
        assertEquals(RemoteLinks.BOTH, p.remoteLinks("c2"))
        assertEquals(RemoteLinks.BOTH, p.remoteLinks("c3")) // 两种都关的脏数据
        assertEquals(RemoteLinks(forward = true, direct = false), p.remoteLinks("c4")) // 缺字段按 true
        val q = PaperplaneJson.decodeFromString(ProjectSummary.serializer(), """{"id":"p","name":"x"}""")
        assertTrue(q.remoteLinks.isEmpty())
    }

    @Test
    fun savedServers() {
        val s = SavedServer(id = "1", name = "NAS", url = "https://nas.local:47290", fingerprint = "sha256:abcdef0123456789", trusted = false, os = "linux")
        val json = SavedServerList.encode(listOf(s))
        assertEquals(listOf(s), SavedServerList.decode(json))
        assertEquals(emptyList<SavedServer>(), SavedServerList.decode(null))
        assertEquals(emptyList<SavedServer>(), SavedServerList.decode("not json"))
        assertEquals("ABCDEF01", s.shortFingerprint)
        assertEquals("nas.local", s.host)
        assertEquals("nas.local:47290", s.displayAddress)
        assertTrue(s.isSameServer("https://NAS.local:47290/", "SHA256:ABCDEF0123456789"))
        assertFalse(s.isSameServer("https://nas.local:47290", "sha256:ffff"))
        assertTrue(s.copy(trusted = true).isSameServer("https://nas.local:47290", "sha256:ffff"))
        assertEquals(36, SavedServer(name = "x", url = "https://h:1").id.length)
    }

    @Test
    fun remoteErrorData() {
        try {
            Fixtures.envelope("""{"error":{"code":"fingerprint_mismatch","message":"x","data":{"fingerprint":"sha256:zz"}}}""", 409)
            throw AssertionError("应当报错")
        } catch (e: ApiException) {
            assertEquals(ApiException.FINGERPRINT_MISMATCH, e.code)
            assertEquals(409, e.status)
            assertNull(e.reason)
        }
    }
}
