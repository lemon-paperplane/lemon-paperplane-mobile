package cn.lemonit.paperplane.kit

import cn.lemonit.paperplane.kit.model.AppSettings
import cn.lemonit.paperplane.kit.model.Contributions
import cn.lemonit.paperplane.kit.model.EditorSettings
import cn.lemonit.paperplane.kit.model.EditorURL
import cn.lemonit.paperplane.kit.model.FSEntry
import cn.lemonit.paperplane.kit.model.FSGrepResult
import cn.lemonit.paperplane.kit.model.FSListResult
import cn.lemonit.paperplane.kit.model.FSReadResult
import cn.lemonit.paperplane.kit.model.FSReason
import cn.lemonit.paperplane.kit.model.FSSearchHit
import cn.lemonit.paperplane.kit.model.FSStat
import cn.lemonit.paperplane.kit.model.FileIcon
import cn.lemonit.paperplane.kit.model.LanguageResolver
import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 对照 Mac FileModelTests.swift（含 RealFileFixtureTests） */
class FileModelTest {
    @Test
    fun fsDecoding() {
        val list = Fixtures.decode("fs.list.json", FSListResult.serializer())
        assertEquals(listOf(".github", "node_modules", "src", "package.json", "link"), list.entries.map { it.name })
        assertTrue(list.entries[0].hidden)
        assertTrue(list.entries[1].ignored && list.entries[1].dir)
        assertEquals(1234L, list.entries[3].size)
        assertEquals("json", list.entries[3].ext)
        assertTrue(list.entries[4].symlink)
        assertEquals("2026-11-18T09:00:00.123456789+08:00", list.entries[0].mtime) // 原样保留，回传给 expectedMtime
        assertEquals("", FSEntry(name = ".gitignore").ext)
        val read = Fixtures.decode("fs.read.json", FSReadResult.serializer())
        assertEquals("crlf", read.lineEnding)
        assertEquals("gb18030", read.encoding)
        assertFalse(read.tooLarge)
        val hits = Fixtures.decode("fs.search.json", ListSerializer(FSSearchHit.serializer()))
        assertEquals("src/main", hits.first().directory)
        assertEquals("", hits.last().directory)
        val grep = Fixtures.decode("fs.grep.json", FSGrepResult.serializer())
        assertEquals(7, grep.matches.first().column)
        assertTrue(grep.truncated)
    }

    @Test
    fun writeConflictErrorCarriesData() {
        for (name in listOf("fs.write.conflict.json", "real/fs.write.conflict.json")) {
            try {
                Fixtures.result(name, 400)
                throw AssertionError("应当报错")
            } catch (e: ApiException) {
                assertEquals("user_error", e.code)
                assertEquals(FSReason.MODIFIED_EXTERNALLY, e.reason)
            }
        }
    }

    @Test
    fun editorContributionsAndSettings() {
        val c = Fixtures.decode("editor.contributions.json", Contributions.serializer())
        assertEquals(5, c.languages.size)
        assertEquals(listOf("/*", "*/"), c.languages[0].blockComment)
        assertEquals(listOf("macos", "windows", "any"), c.keymaps.map { it.platform })
        assertEquals("eclipse-macos", c.keymaps[2].base)
        assertEquals("dark", c.editorThemes.first().kind)
        // 老内核没有这些字段时为空数组
        assertEquals(emptyList<Any>(), PaperplaneJson.decodeFromString(Contributions.serializer(), "{}").languages)

        val s = Fixtures.decode("settings.editor.json", AppSettings.serializer())
        assertTrue(s.autoSyncOnImport)
        assertEquals(14, s.editor?.fontSize)
        assertEquals("onFocusChange", s.editor?.autoSave)
        // 手机上没设置快捷键方案时用 Ctrl 系的 eclipse-windows（Mac 版是 eclipse-macos）
        assertEquals(EditorSettings.PLATFORM_DEFAULT_KEYMAP, s.editor?.effectiveKeymap())
        assertEquals("eclipse-macos", s.editor?.effectiveKeymap("eclipse-macos"))
        assertEquals(4, EditorSettings().tabSize)
        // 缺字段时取内核默认值（Mac 版缺字段时为 0）
        assertEquals(13, PaperplaneJson.decodeFromString(EditorSettings.serializer(), "{}").fontSize)
    }

    @Test
    fun languageResolver() {
        val langs = Fixtures.decode("editor.contributions.json", Contributions.serializer()).languages
        assertEquals("java", LanguageResolver.language("App.java", langs)?.id)
        assertEquals("typescript", LanguageResolver.language("index.ts", langs)?.id) // priority 高的
        assertEquals("tsdecl", LanguageResolver.language("types.d.ts", langs)?.id) // 最长扩展名
        assertEquals("dockerfile", LanguageResolver.language("Dockerfile", langs)?.id)
        assertEquals("dockerfile", LanguageResolver.language("Dockerfile.dev", langs)?.id)
        assertEquals("dockerfile", LanguageResolver.language("app.dockerfile", langs)?.id)
        assertNull(LanguageResolver.language("notes.txt", langs))
        assertNull(LanguageResolver.language(".ts", langs)) // 只有扩展名不算
        assertTrue(LanguageResolver.glob("*.config.*", "vite.config.ts"))
        assertFalse(LanguageResolver.glob("a?c", "abbc"))
    }

    /** 文件图标名都在 shared/icons 里（需要旁边有 lemon-paperplane-main，找不到时跳过） */
    @Test
    fun fileIconsExistInSharedIcons() {
        val dir = sharedIconsDir()
        assumeTrue("找不到 shared/icons", dir != null)
        val names = listOf(
            "App.java", "package.json", "README.md", "logo.png", "run.sh", "docker-compose.yml", "data.csv",
            "server.pem", "font.woff2", "x.sqlite", "a.zip", "Dockerfile", ".gitignore", ".env.local", "LICENSE",
            "clip.mp4", "song.mp3", "noext", "a.patch",
        )
        for (n in names) {
            val icon = FileIcon.builtinName(n)
            assertTrue("$n → $icon", File(dir, "$icon.svg").isFile)
        }
        for (e in listOf(FSEntry(name = "src", path = "src", dir = true), FSEntry(name = ".git", path = ".git", dir = true))) {
            for (expanded in listOf(false, true)) {
                val icon = FileIcon.builtinName(e, expanded)
                assertTrue(icon, File(dir, "$icon.svg").isFile)
            }
        }
    }

    @Test
    fun editorUrl() {
        val u = EditorURL.make("http://127.0.0.1:47213", "ab12", "p1", "src/my file#1.ts", 12, "zh-Hans", "dark")
        assertEquals(
            "http://127.0.0.1:47213/editor/index.html#token=ab12&projectId=p1&file=src/my%20file%231.ts&line=12&locale=zh-Hans&theme=dark",
            u,
        )
        val v = EditorURL.make("http://127.0.0.1:1/", "t", "p", null, null, "en", "light")
        assertEquals("token=t&projectId=p&locale=en&theme=light", v.substringAfter('#'))
        assertTrue(EditorURL.make("http://h:1", "t", "p", "中.kt", 0, "en", "light").contains("file=%E4%B8%AD.kt&locale"))
    }

    @Test
    fun realFS() {
        val list = Fixtures.decode("real/fs.list.json", FSListResult.serializer())
        val venv = list.entries.first { it.name == ".venv" }
        assertTrue(venv.dir && venv.hidden && venv.ignored)
        assertEquals("py", list.entries.firstOrNull { it.name == "main.py" }?.ext)
        assertTrue(Fixtures.decode("real/fs.read.json", FSReadResult.serializer()).content.startsWith("import time"))
        assertEquals(listOf(true, false), Fixtures.decode("real/fs.stat.json", ListSerializer(FSStat.serializer())).map { it.exists })
        assertEquals("main.py", Fixtures.decode("real/fs.search.json", ListSerializer(FSSearchHit.serializer())).first().path)
        assertEquals(10, Fixtures.decode("real/fs.grep.json", FSGrepResult.serializer()).matches.first().line)
    }

    @Test
    fun realEditorContributionsAndSettings() {
        val c = Fixtures.decode("real/ui.contributions.json", Contributions.serializer())
        assertEquals("eclipse-macos", c.defaultKeymap)
        assertEquals(emptyList<Any>(), c.keymaps) // 快捷键插件尚未提供时为空
        val s = Fixtures.decode("real/settings.get.json", AppSettings.serializer())
        assertEquals(4, s.editor?.tabSize)
        assertEquals("", s.editor?.fontFamily)
        assertEquals("eclipse-windows", s.editor?.effectiveKeymap())
    }

    companion object {
        /** 主仓库的 shared/icons：local.properties 的 paperplane.mainRepo，或旁边的 lemon-paperplane-main */
        fun sharedIconsDir(): File? {
            val candidates = mutableListOf<File>()
            System.getenv("PAPERPLANE_MAIN_REPO")?.let { candidates.add(File(it)) }
            var d: File? = File("").absoluteFile
            while (d != null) {
                val props = File(d, "local.properties")
                if (props.isFile) {
                    props.readLines().firstOrNull { it.startsWith("paperplane.mainRepo=") }
                        ?.substringAfter('=')?.trim()?.let { candidates.add(File(it)) }
                }
                candidates.add(File(d, "lemon-paperplane-main"))
                d = d.parentFile
            }
            return candidates.map { File(it, "shared/icons") }.firstOrNull { it.isDirectory }
        }
    }
}
