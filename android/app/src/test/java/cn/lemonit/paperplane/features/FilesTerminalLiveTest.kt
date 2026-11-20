package cn.lemonit.paperplane.features

import cn.lemonit.paperplane.features.editor.EditorWebBridge
import cn.lemonit.paperplane.features.terminal.HistoryLoader
import cn.lemonit.paperplane.features.terminal.TERMINAL_HOST_JS
import cn.lemonit.paperplane.features.terminal.TermKey
import cn.lemonit.paperplane.features.terminal.TermKeys
import cn.lemonit.paperplane.features.terminal.terminalPageUrl
import cn.lemonit.paperplane.kit.fsCreate
import cn.lemonit.paperplane.kit.fsDelete
import cn.lemonit.paperplane.kit.fsGrep
import cn.lemonit.paperplane.kit.fsList
import cn.lemonit.paperplane.kit.fsRename
import cn.lemonit.paperplane.kit.fsSearch
import cn.lemonit.paperplane.kit.fsWrite
import cn.lemonit.paperplane.kit.importProject
import cn.lemonit.paperplane.kit.model.EditorURL
import cn.lemonit.paperplane.kit.terminalClose
import cn.lemonit.paperplane.kit.terminalCreate
import cn.lemonit.paperplane.kit.terminalHistory
import cn.lemonit.paperplane.kit.terminalInput
import cn.lemonit.paperplane.kit.workspaces
import cn.lemonit.paperplane.net.ConnectionCode
import cn.lemonit.paperplane.net.DirectoryAssetSource
import cn.lemonit.paperplane.net.LocalGateway
import cn.lemonit.paperplane.net.RemoteAuth
import cn.lemonit.paperplane.net.RemoteClient
import cn.lemonit.paperplane.net.ServerTarget
import cn.lemonit.paperplane.net.blocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.Proxy
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * 联调：真的内核（临时数据目录）上走一遍文件页、终端页、完整输出用到的接口，以及编辑器网页经本地网关的加载。
 * 只在设置了 PAPERPLANE_TEST_BIN 时运行（同 net.LiveServerTest）。
 */
class FilesTerminalLiveTest {
    companion object {
        private var proc: Process? = null
        private lateinit var dataDir: File
        private lateinit var client: RemoteClient
        private lateinit var projectDir: File
        private lateinit var projectId: String
        private lateinit var workspaceId: String

        @BeforeClass
        @JvmStatic
        fun start() {
            val bin = System.getenv("PAPERPLANE_TEST_BIN")
            Assume.assumeTrue("PAPERPLANE_TEST_BIN not set: live tests skipped", !bin.isNullOrBlank())
            dataDir = Files.createTempDirectory("pp-files-live").toFile()
            val port = ServerSocket(0).use { it.localPort }
            val env = mapOf(
                "PAPERPLANE_DATA_DIR" to dataDir.path,
                "PAPERPLANE_NO_SHELL_ENV" to "1",
                "PAPERPLANE_NO_UI" to "1",
                "SHELL" to "/bin/sh",
            )
            proc = ProcessBuilder(bin, "serve", "--listen", "127.0.0.1:$port").apply {
                environment().putAll(env)
                redirectErrorStream(true)
                redirectOutput(File(dataDir, "serve.log"))
            }.start()
            val deadline = System.currentTimeMillis() + 30_000
            while (runCatching { blocking(5_000) { RemoteAuth().probe("127.0.0.1:$port") } }.isFailure) {
                if (System.currentTimeMillis() > deadline) throw AssertionError(File(dataDir, "serve.log").readText())
                Thread.sleep(200)
            }
            val p = ProcessBuilder(bin, "server", "key", "add", "files-test").apply {
                environment().putAll(env)
                redirectErrorStream(true)
            }.start()
            val out = p.inputStream.readBytes().decodeToString()
            p.waitFor(30, TimeUnit.SECONDS)
            val code = ConnectionCode.decode(out.lines().first { it.startsWith(ConnectionCode.PREFIX) })
            client = RemoteClient(ServerTarget.of(code.urls, code.fingerprint), code.token)
            blocking { client.connect() }

            projectDir = File(dataDir, "proj").apply { mkdirs() }
            File(projectDir, "src").mkdirs()
            File(projectDir, "src/hello.txt").writeText("第一行\n你好，纸飞机\nlast\n")
            File(projectDir, "run.sh").apply {
                writeText("#!/bin/sh\necho run\n")
                setExecutable(true)
            }
            File(projectDir, ".hidden").writeText("x")
            workspaceId = blocking { client.workspaces() }.first().id
            projectId = blocking { client.importProject(projectDir.path, workspaceId) }.id
        }

        @AfterClass
        @JvmStatic
        fun stop() {
            if (::client.isInitialized) client.close()
            proc?.let { p ->
                p.destroy()
                if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
            }
            if (::dataDir.isInitialized) dataDir.deleteRecursively()
        }
    }

    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(15, TimeUnit.SECONDS).build()

    @Test
    fun filesOperations() = blocking {
        val root = client.fsList(projectId, "")
        val names = root.entries.map { it.name }
        assertTrue(names.toString(), names.containsAll(listOf("src", "run.sh", ".hidden")))
        assertTrue(root.entries.first { it.name == ".hidden" }.hidden)
        assertTrue(root.entries.first { it.name == "src" }.dir)
        assertTrue("run.sh is launchable", root.entries.first { it.name == "run.sh" }.isLaunchable)

        // 新建（名字带子目录）、写入、重命名
        val created = client.fsCreate(projectId, "src/new/a.txt", dir = false)
        assertEquals("src/new/a.txt", created.path)
        client.fsWrite(projectId, "src/new/a.txt", "需要找到的内容\n")
        client.fsRename(projectId, "src/new/a.txt", "src/new/b.txt")
        assertEquals(listOf("b.txt"), client.fsList(projectId, "src/new").entries.map { it.name })

        // 按文件名、按内容
        assertTrue(client.fsSearch(projectId, "hello").any { it.path == "src/hello.txt" })
        val g = client.fsGrep(projectId, "纸飞机")
        assertEquals(listOf("src/hello.txt" to 2), g.matches.map { it.path to it.line })
        val g2 = client.fsGrep(projectId, "需要.到", regex = true)
        assertEquals("src/new/b.txt", g2.matches.single().path)

        // 删除（移到废纸篓）
        client.fsDelete(projectId, listOf("src/new"))
        assertFalse(client.fsList(projectId, "src").entries.any { it.name == "new" })
        // 加到启动要靠插件提供的运行配置类型（测试内核没装插件），这里不测
    }

    @Test
    fun terminalHistoryPagingKeepsEveryCharacter() = blocking(60_000) {
        val t = client.terminalCreate(80, 24, projectId = projectId, cwd = "src")
        try {
            // 多字节字符很多的输出；小页面让分页边界落在字符中间
            client.terminalInput(t.id, "printf '中文输出%s\\n' 一 二 三 四 五; echo mark\$((40+2))\r")
            var text = ""
            val deadline = System.currentTimeMillis() + 15_000
            while (!text.contains("mark42") && System.currentTimeMillis() < deadline) {
                Thread.sleep(200)
                text = client.terminalHistory(t.id, 0, 1 shl 20, strip = true).data
            }
            assertTrue(text, text.contains("mark42"))
            assertTrue(text, text.contains("src")) // 起始目录是工程里的 src（提示符或 pwd 里）

            for (page in listOf(7, 13, 64)) {
                val loader = HistoryLoader(t.id, page) { client }
                loader.latest()
                var guard = 0
                while (loader.buf.hasOlder && guard++ < 10_000) loader.older()
                val got = loader.buf.text().first
                assertFalse("page=$page: replacement char in $got", got.contains('�'))
                for (w in listOf("中文输出一", "中文输出三", "中文输出五", "mark42")) assertTrue("page=$page missing $w in:\n$got", got.contains(w))
                // 注意：去控制符是按段做的，控制序列恰好被切开时边界附近的文字会和一次读出的略有不同（256 KB 一段时很少见）
            }

            // 新输出接在后面
            val loader = HistoryLoader(t.id, 64) { client }
            loader.latest()
            client.terminalInput(t.id, "echo after\$((1+1))\r")
            val deadline2 = System.currentTimeMillis() + 15_000
            while (!loader.buf.text().first.contains("after2") && System.currentTimeMillis() < deadline2) {
                Thread.sleep(200)
                loader.newer()
            }
            assertTrue(loader.buf.text().first.contains("after2"))

            // 辅助键：Ctrl+C 中断正在跑的命令
            client.terminalInput(t.id, "sleep 30\r")
            Thread.sleep(500)
            client.terminalInput(t.id, TermKeys.text("c", ctrl = true, alt = false))
            client.terminalInput(t.id, "echo back\$((2+3))\r")
            val deadline3 = System.currentTimeMillis() + 10_000
            while (!loader.buf.text().first.contains("back5") && System.currentTimeMillis() < deadline3) {
                Thread.sleep(200)
                loader.newer()
            }
            assertTrue("Ctrl+C did not interrupt sleep", loader.buf.text().first.contains("back5"))
            assertEquals("\u001b[A", TermKeys.sequence(TermKey.UP))
        } finally {
            client.terminalClose(t.id)
        }
    }

    @Test
    fun editorAndTerminalPagesThroughGateway() {
        val dist = File(System.getenv("PAPERPLANE_MAIN_REPO") ?: "../../../lemon-paperplane-main", "lemon-paperplane-main-editor/dist")
            .takeIf { File(it, "terminal.html").isFile }
            ?: File("/Users/liuri/Code/lemon-paperplane/lemon-paperplane-main/lemon-paperplane-main-editor/dist")
        Assume.assumeTrue("editor dist not found", File(dist, "terminal.html").isFile)
        LocalGateway(client, DirectoryAssetSource(dist)).start().use { gw ->
            // 终端页：地址片段带令牌；HTML 能注入宿主脚本
            val url = terminalPageUrl(gw, dark = true, fontSize = 13)
            assertTrue(url, url.startsWith(gw.baseUrl + "/editor/terminal.html#token=" + gw.token + "&theme=dark&fontSize=13"))
            val html = http.newCall(Request.Builder().url(url.substringBefore('#')).build()).execute().use {
                assertEquals(200, it.code)
                it.body!!.string()
            }
            val injected = EditorWebBridge.inject(html, EditorWebBridge.hostScript(TERMINAL_HOST_JS))
            assertTrue(injected.indexOf("PaperplaneAndroid") in 0 until injected.indexOf("<script type=\"module\""))

            // 编辑器页
            val editorUrl = EditorURL.make(gw.baseUrl, gw.token, projectId, "src/hello.txt", 2, "zh-Hans", "light")
            http.newCall(Request.Builder().url(editorUrl.substringBefore('#')).build()).execute().use { assertEquals(200, it.code) }

            // 网页里的调用：同源 fetch（带 Origin 与 Bearer 本地令牌）
            val t = blocking { client.terminalCreate(80, 24, projectId = projectId) }
            try {
                http.newCall(
                    Request.Builder().url(gw.baseUrl + "/api/v1/call/terminal.input")
                        .header("Authorization", "Bearer ${gw.token}")
                        .header("Origin", gw.baseUrl)
                        .post("{\"id\":\"${t.id}\",\"data\":\"echo gw\$((3*3))\\r\"}".toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute().use { assertEquals(200, it.code) }
                // 输出流（EventSource，令牌在查询参数里）
                http.newCall(Request.Builder().url(gw.baseUrl + "/api/v1/terminals/${t.id}/stream?since=0&token=${gw.token}").build()).execute().use { r ->
                    assertEquals(200, r.code)
                    val src = r.body!!.source()
                    val deadline = System.currentTimeMillis() + 10_000
                    var all = ""
                    while (!all.contains("gw9") && System.currentTimeMillis() < deadline) {
                        val line = src.readUtf8LineStrict()
                        if (!line.startsWith("data: ")) continue
                        val d = Regex("\"d\":\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: continue
                        all += String(java.util.Base64.getDecoder().decode(d))
                    }
                    assertTrue(all, all.contains("gw9"))
                }
            } finally {
                blocking { client.terminalClose(t.id) }
            }
        }
    }
}
