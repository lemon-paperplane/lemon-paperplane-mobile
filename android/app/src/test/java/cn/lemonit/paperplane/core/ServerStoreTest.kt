package cn.lemonit.paperplane.core

import cn.lemonit.paperplane.net.ConnectionCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ServerStoreTest {
    private lateinit var dir: File
    private lateinit var file: File
    private val box = InMemorySecretBox()
    private var now = 1_000L
    private val token = "ppk_0123abcd_AAcOFRwjKjE4P0ZNVFtiaXB3foWMk5qhqK-2vcTL0tk"
    private val fp = "sha256:" + "ab".repeat(32)

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("pp-store").toFile()
        file = File(dir, "servers.json")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun store(secrets: SecretBox = box) = ServerStore(file, secrets) { now }

    private fun entry(name: String = "NAS") = ServerEntry(
        name = name, urls = listOf("192.168.1.5", "https://NAS.local:47290/"), fingerprint = fp, addedVia = AddMethod.SCAN,
    )

    @Test
    fun saveLoadAndEncryptToken() {
        val s = store()
        val e = s.save(entry(), token)
        assertEquals(listOf("https://192.168.1.5:47290", "https://nas.local:47290"), e.urls)
        assertEquals("0123abcd", e.keyId) // 从密钥里取编号
        assertEquals(1_000L, e.addedAt)
        assertEquals(token, s.token(e.id))
        assertEquals(listOf(e), s.servers.value)
        // 文件里没有明文密钥
        val text = file.readText()
        assertFalse(text.contains(token))
        assertFalse(text.contains("AAcOFRwjKjE4P0ZNVFtiaXB3"))
        assertTrue(text.contains("\"scan\""))

        s.setCurrent(e.id)
        now = 2_000L
        s.markUsed(e.id, "https://nas.local:47290")
        s.updateInfo(e.id, "linux", "nas")
        s.rename(e.id, "  Home NAS ")

        val again = store()
        val r = again.server(e.id)!!
        assertEquals("Home NAS", r.name)
        assertEquals("https://nas.local:47290", r.lastUrl)
        assertEquals("https://nas.local:47290", r.url)
        assertEquals(2_000L, r.lastUsedAt)
        assertEquals("linux", r.os)
        assertEquals("nas", r.hostname)
        assertEquals(e.id, again.currentId.value)
        assertEquals(r, again.current)
        assertEquals(token, again.token(e.id))
        assertEquals("ABABABAB", r.shortFingerprint)
        assertEquals("nas.local:47290", r.displayAddress)
        // 上次能用的地址排在最前
        assertEquals("https://nas.local:47290", r.target().urls.first())
        assertEquals("https://nas.local:47290", r.target().preferredUrl)
        assertEquals(fp, r.target().fingerprint)
    }

    @Test
    fun updateRemoveAndCurrent() {
        val s = store()
        val a = s.save(entry("A"), token)
        val b = s.save(entry("B").copy(urls = listOf("10.0.0.2")), null)
        assertNull(s.token(b.id))
        s.setCurrent(a.id)
        s.setCurrent("missing")
        assertNull(s.currentId.value)
        s.setCurrent(a.id)
        // 重新登录：换密钥
        val newToken = "ppk_99999999_" + "y".repeat(43)
        s.save(s.server(a.id)!!.copy(keyId = ""), newToken)
        assertEquals(newToken, s.token(a.id))
        assertEquals("99999999", s.server(a.id)!!.keyId)
        s.remove(a.id)
        assertNull(s.currentId.value)
        assertNull(s.token(a.id))
        assertEquals(listOf(b.id), runBlocking { s.servers.first() }.map { it.id })
        assertEquals(listOf(b.id), store().servers.value.map { it.id })
        assertNull(s.update("missing") { it })
    }

    @Test
    fun duplicateDetection() {
        val s = store()
        val a = s.save(entry(), token)
        assertEquals(a.id, s.existing(listOf("https://192.168.1.5:47290"), fp.uppercase())?.id)
        assertEquals(a.id, s.existing(listOf("nas.local"), "")?.id)
        assertNull(s.existing(listOf("nas.local"), "sha256:" + "cd".repeat(32)))
        assertNull(s.existing(listOf("other.local"), fp))
        assertNull(s.existing(listOf("nas.local"), fp, excluding = a.id))
    }

    @Test
    fun lostKeyAndTamperedData() {
        val s = store()
        val a = s.save(entry(), token)
        // Keystore 密钥丢了（重装、恢复备份）：解不开 → null，需要重新登录
        assertNull(store(InMemorySecretBox()).token(a.id))
        // 密文换到别的条目上也解不开（绑定了服务器 id）
        val b = s.save(entry("B"), null)
        val text = file.readText().replace("\"${a.id}\":", "\"${b.id}\":")
        file.writeText(text)
        assertNull(store().token(b.id))
    }

    @Test
    fun corruptFileIsBackedUp() {
        file.writeText("{not json")
        val s = store()
        assertTrue(s.servers.value.isEmpty())
        assertTrue(File(dir, "servers.json.bad").isFile)
        s.save(entry(), token)
        assertNotNull(store().servers.value.firstOrNull())
    }

    @Test
    fun secretBoxRoundTrip() {
        val sealed = box.seal("hello".toByteArray(), "id1".toByteArray())
        assertEquals("hello", box.open(sealed, "id1".toByteArray()).decodeToString())
        assertTrue(runCatching { box.open(sealed, "id2".toByteArray()) }.isFailure)
        // 每次加密 IV 不同
        assertFalse(sealed.contentEquals(box.seal("hello".toByteArray(), "id1".toByteArray())))
        assertEquals("0123abcd", ConnectionCode.keyIdOf(token))
    }
}
