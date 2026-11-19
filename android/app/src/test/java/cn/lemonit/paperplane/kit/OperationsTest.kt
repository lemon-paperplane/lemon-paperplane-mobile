package cn.lemonit.paperplane.kit

import cn.lemonit.paperplane.kit.ansi.ANSIParser
import cn.lemonit.paperplane.kit.model.ConsoleStreamMessage
import cn.lemonit.paperplane.kit.model.FSBrowseResult
import cn.lemonit.paperplane.kit.model.LogStream
import cn.lemonit.paperplane.kit.model.RemoteAuditEvent
import cn.lemonit.paperplane.kit.model.TerminalStreamMessage
import cn.lemonit.paperplane.kit.model.ToolchainOverride
import cn.lemonit.paperplane.kit.model.VCSCommitArgs
import cn.lemonit.paperplane.kit.model.VCSDiffArgs
import cn.lemonit.paperplane.kit.model.VCSDiffMode
import cn.lemonit.paperplane.kit.model.VCSLogArgs
import cn.lemonit.paperplane.kit.model.VCSOp
import cn.lemonit.paperplane.kit.model.VCSPathsArgs
import cn.lemonit.paperplane.kit.model.VCSReason
import cn.lemonit.paperplane.kit.model.VCSRefArgs
import cn.lemonit.paperplane.kit.model.VCSStashArgs
import cn.lemonit.paperplane.kit.model.VCSSubmoduleUpdateArgs
import cn.lemonit.paperplane.kit.model.get
import cn.lemonit.paperplane.kit.model.stringValue
import cn.lemonit.paperplane.kit.util.ServerPath
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 强类型操作：op 名、参数 JSON、超时与结果解码（假 OpCaller + 内核结构手写的夹具 fixtures/kernel） */
class OperationsTest {
    private fun obj(vararg pairs: Pair<String, Any?>) = jsonObjectOf(*pairs)

    @Test
    fun opNamesAndInputs() {
        // 数组类结果给 null（= 空数组），其他给 {}（全取默认值）
        val c = FakeOpCaller { op, _ -> if (op == "project.list" || op == "store.list") JsonNull else JsonObject(emptyMap()) }
        blocking {
            c.projects()
            assertEquals(FakeOpCaller.Call("project.list", obj(), null), c.last)
            c.projects("ws1")
            assertEquals(obj("workspaceId" to "ws1"), c.last.input)
            c.startRun("p1")
            assertEquals("run.start", c.last.op)
            assertEquals(obj("projectId" to "p1"), c.last.input)
            c.restartRun("p1", "c1")
            assertEquals(obj("projectId" to "p1", "configId" to "c1"), c.last.input)
            c.importProject("/srv/a", "ws1")
            assertEquals("project.import", c.last.op)
            assertEquals(OpTimeout.MINUTES_10, c.last.timeoutMs)
            c.installDependencies("p1")
            assertEquals(obj("projectId" to "p1"), c.last.input) // clean=false 不传
            c.installDependencies("p1", clean = true)
            assertEquals(JsonPrimitive(true), c.last.input["clean"])
            c.setPluginEnabled("git", false)
            assertEquals(FakeOpCaller.Call("plugin.disable", obj("pluginId" to "git"), null), c.last)
            c.applyFix("f1", runId = "r1")
            assertEquals(FakeOpCaller.Call("fix.apply", obj("runId" to "r1", "fixId" to "f1"), OpTimeout.MINUTES_10), c.last)
            c.getTask("t1", waitSeconds = 5)
            assertEquals(obj("taskId" to "t1", "wait" to 5), c.last.input)
            assertEquals(35_000L, c.last.timeoutMs)
            c.respondPrompt("pr1", false, mapOf("password" to JsonPrimitive("x")))
            assertEquals(obj("promptId" to "pr1", "cancelled" to false, "values" to mapOf("password" to "x")), c.last.input)
            c.terminalCreate(cols = 80, rows = 24, projectId = "p1")
            assertEquals(obj("projectId" to "p1", "cols" to 80, "rows" to 24), c.last.input)
            c.terminalInput("t1", "ls\r")
            assertEquals(FakeOpCaller.Call("terminal.input", obj("id" to "t1", "data" to "ls\r"), null), c.last)
            c.launchRun(workspaceId = "ws1")
            assertEquals(FakeOpCaller.Call("launch.run", obj("workspaceId" to "ws1"), OpTimeout.MINUTES_2), c.last)
            c.remoteKeyCreate("Pixel", address = "")
            assertEquals(FakeOpCaller.Call("remote.keyCreate", obj("name" to "Pixel"), null), c.last)
            c.remoteConfigure(enabled = true, allowCIDRs = emptyList())
            assertEquals(obj("enabled" to true, "allowCIDRs" to JsonArray(emptyList())), c.last.input)
            c.fsBrowse("")
            assertEquals(FakeOpCaller.Call("fs.browse", obj(), OpTimeout.SECONDS_30), c.last)
            c.setRemoteLinks("p1", "c1", forward = true, direct = false)
            assertEquals("runconfig.setRemoteLinks", c.last.op)
            c.storeList()
            assertEquals(FakeOpCaller.Call("store.list", obj(), OpTimeout.MINUTE), c.last)
            c.runStats("r1")
            assertEquals(FakeOpCaller.Call("run.stats", obj("runId" to "r1"), null), c.last)
        }
    }

    @Test
    fun projectSettingsClearsOverrideWithNull() {
        val c = FakeOpCaller { _, _ -> JsonObject(mapOf("id" to JsonPrimitive("p1"))) }
        val p = blocking { c.updateProjectSettings("p1", toolchains = mapOf("jdk" to ToolchainOverride(constraint = "17"), "node" to null)) }
        assertEquals("p1", p.id)
        assertEquals(
            obj("projectId" to "p1", "toolchains" to JsonObject(mapOf("jdk" to obj("constraint" to "17"), "node" to JsonNull))),
            c.last.input,
        )
    }

    @Test
    fun viewOps() {
        val c = FakeOpCaller { _, _ -> JsonObject(mapOf("webviewUrl" to JsonPrimitive("/plugins/p/index.html?projectId=a"))) }
        val ctx = ViewContext("spring-boot", "beans", projectId = "a")
        val url = blocking { c.webviewUrl(ctx) }
        assertEquals("/plugins/p/index.html?projectId=a", url)
        assertEquals(FakeOpCaller.Call("view.render", obj("plugin" to "spring-boot", "view" to "beans", "projectId" to "a"), null), c.last)
        blocking { c.viewEvent(ctx, "click", mapOf("row" to JsonPrimitive(1)), state = mapOf("page" to JsonPrimitive(2))) }
        assertEquals(
            obj("plugin" to "spring-boot", "view" to "beans", "projectId" to "a", "event" to "click", "values" to mapOf("row" to 1), "state" to mapOf("page" to 2)),
            c.last.input,
        )
    }

    @Test
    fun vcsOpsFlattenArgs() {
        val c = FakeOpCaller.fixtures(
            mapOf(
                "vcs.status" to "real/vcs.status.json",
                "vcs.diff" to "real/vcs.diff.json",
                "vcs.log" to "real/vcs.log.json",
                "vcs.commit" to "kernel/vcs.commit.json",
                "vcs.stage" to "real/vcs.stashSave.json",
                "vcs.roots" to "real/vcs.roots.json",
                "vcs.overview" to "real/vcs.overview.json",
                "vcs.push" to "real/vcs.push.json",
            ),
        )
        blocking {
            assertEquals("main", c.vcsStatus("p1").branch)
            assertEquals(FakeOpCaller.Call("vcs.status", obj("projectId" to "p1"), null), c.last)

            val d = c.vcsDiff("p1", "/repo", VCSDiffArgs("a.txt", mode = VCSDiffMode.HEAD, context = 3))
            assertEquals("head", d.mode)
            assertEquals(obj("path" to "a.txt", "context" to 3, "mode" to "head", "projectId" to "p1", "root" to "/repo"), c.last.input)

            c.vcsLog("p1", args = VCSLogArgs(ref = "--all", limit = 200))
            assertEquals(obj("ref" to "--all", "limit" to 200, "projectId" to "p1"), c.last.input)

            val r = c.vcsCommit("p1", args = VCSCommitArgs("msg", paths = listOf("a"), push = true))
            assertEquals("c0ffee0", r.short)
            assertEquals(VCSReason.REJECTED, r.push?.reason)
            assertEquals("task9", r.push?.taskId)
            assertEquals(OpTimeout.MINUTES_15, c.last.timeoutMs)

            assertTrue(c.vcsStage("p1", paths = listOf("x", "y")).ok)
            assertEquals(FakeOpCaller.Call("vcs.stage", obj("paths" to listOf("x", "y"), "projectId" to "p1"), null), c.last)

            c.vcsRun(VCSOp.STAGE, "p1", "/r", VCSPathsArgs(listOf("z")))
            assertEquals(obj("paths" to listOf("z"), "projectId" to "p1", "root" to "/r"), c.last.input)

            c.vcsAbort("p1")
            assertEquals(FakeOpCaller.Call("vcs.abort", obj("projectId" to "p1"), null), c.last)
            c.vcsContinue("p1")
            assertEquals(OpTimeout.MINUTES_15, c.last.timeoutMs)

            c.vcsSubmoduleUpdate("p1", args = VCSSubmoduleUpdateArgs(initialize = true, recursive = true))
            assertEquals(obj("init" to true, "recursive" to true, "projectId" to "p1"), c.last.input)
            assertEquals(OpTimeout.HOUR, c.last.timeoutMs)

            c.vcsStashApply("p1", args = VCSStashArgs(0, pop = true))
            assertEquals(obj("index" to 0, "pop" to true, "projectId" to "p1"), c.last.input)
            c.vcsMerge("p1", args = VCSRefArgs("feature", noFF = true))
            assertEquals(obj("ref" to "feature", "noFF" to true, "projectId" to "p1"), c.last.input)
            c.vcsBranchRename("p1", old = "a", new = "b")
            assertEquals(FakeOpCaller.Call("vcs.branchRename", obj("old" to "a", "new" to "b", "projectId" to "p1"), null), c.last)

            assertEquals(".", c.vcsRoots("p1").first().relPath)
            assertEquals(4, c.vcsOverview(null).size)
            assertEquals(FakeOpCaller.Call("vcs.overview", obj(), OpTimeout.MINUTES_5), c.last)
            assertTrue(c.vcsPush("p1", args = cn.lemonit.paperplane.kit.model.VCSPushArgs(setUpstream = true)).ok)
            assertEquals(OpTimeout.HOUR, c.last.timeoutMs)
        }
    }

    @Test
    fun vcsExtraResults() {
        val c = FakeOpCaller.fixtures(
            mapOf(
                "vcs.tags" to "kernel/vcs.tags.json",
                "vcs.remotes" to "kernel/vcs.remotes.json",
                "vcs.blame" to "kernel/vcs.blame.json",
                "vcs.worktrees" to "kernel/vcs.worktrees.json",
                "vcs.submodules" to "kernel/vcs.submodules.json",
            ),
        )
        blocking {
            val tags = c.vcsTags("p1").tags
            assertTrue(tags[0].annotated)
            assertEquals("第一个版本", tags[0].message)
            assertFalse(tags[1].annotated)
            assertNull(tags[1].subject)
            val remotes = c.vcsRemotes("p1").remotes
            assertEquals("https://github.com/lemon-paperplane/demo", remotes[0].webUrl)
            assertNull(remotes[0].pushUrl)
            assertEquals("https://example.com/push.git", remotes[1].pushUrl)
            val blame = c.vcsBlame("p1", args = cn.lemonit.paperplane.kit.model.VCSBlameArgs("a.txt")).lines
            assertEquals("init", blame[0].summary)
            assertNull(blame[1].summary)
            val wt = c.vcsWorktrees("p1").worktrees
            assertTrue(wt[0].main && !wt[0].locked)
            assertTrue(wt[1].locked && !wt[1].main)
            val subs = c.vcsSubmodules("p1").submodules
            assertEquals("uninitialized", subs[1].status)
            assertNull(subs[1].url)
        }
        // 插件给的结构化错误
        try {
            Fixtures.result("kernel/vcs.identity.error.json", 400)
            throw AssertionError("应当报错")
        } catch (e: ApiException) {
            assertEquals(VCSReason.IDENTITY_MISSING, e.reason)
            assertEquals("user.email", e.data?.get("missing")?.get(0)?.stringValue)
        }
    }

    @Test
    fun runLogsPaging() {
        val c = FakeOpCaller { _, input ->
            Fixtures.result(if (input["from"] == JsonPrimitive(1)) "kernel/run.logs.page.json" else "kernel/run.logs.page.end.json")
        }
        blocking {
            val first = c.runLogsPage("r1", from = 0, limit = 3, raw = true)
            assertEquals(FakeOpCaller.Call("run.logs", obj("consoleId" to "r1", "from" to 1, "limit" to 3, "raw" to true), null), c.last)
            assertEquals(1L, first.firstSeq)
            assertEquals(12000L, first.lastSeq)
            assertEquals(listOf(1L, 2L, 3L), first.lines.map { it.seq })
            // 日志文件里的老行只有文本
            assertEquals(0L, first.lines[0].t)
            assertEquals("", first.lines[0].s)
            assertEquals(LogStream.STDERR, first.lines[2].s)
            assertEquals("Starting app", ANSIParser.strip(first.lines[0].text))
            assertEquals(4L, first.nextFrom)
            val last = c.runLogsPage("r1", from = 11999)
            assertNull(last.nextFrom)

            c.runLogs("r1", tail = 500, since = 17, grep = "error", raw = true)
            assertEquals(obj("consoleId" to "r1", "tail" to 500, "since" to 17, "grep" to "error", "raw" to true), c.last.input)
        }
        // 老夹具没有 firstSeq 时为 0
        assertEquals(0L, Fixtures.decode("real/run.logs.json", cn.lemonit.paperplane.kit.model.LogsResult.serializer()).firstSeq)
    }

    @Test
    fun terminalHistoryAndList() {
        val c = FakeOpCaller { op, input ->
            when (op) {
                "terminal.history" -> Fixtures.result(if (input["strip"] == null) "kernel/terminal.history.json" else "kernel/terminal.history.strip.json")
                "terminal.list" -> Fixtures.result("kernel/terminal.list.json")
                "terminal.read" -> JsonObject(mapOf("text" to JsonPrimitive("$ ls")))
                else -> JsonNull
            }
        }
        blocking {
            val h = c.terminalHistory("t1")
            assertEquals(FakeOpCaller.Call("terminal.history", obj("id" to "t1", "offset" to 0), null), c.last)
            assertEquals(262140L, h.end)
            assertFalse(h.atEnd)
            assertTrue(h.data.contains("中文.txt"))
            assertTrue(ANSIParser.strip(h.data.lines().first()).startsWith("me@mbp ~/Code/demo % ls"))
            val s = c.terminalHistory("t1", offset = h.end, limit = 1 shl 20, strip = true)
            assertEquals(obj("id" to "t1", "offset" to 262140, "limit" to 1048576, "strip" to true), c.last.input)
            assertTrue(s.atEnd)
            assertEquals(1048576L, s.first)

            val list = c.terminalList("ws1")
            assertEquals(5_300_000L, list[0].output)
            assertEquals("", list[1].workspaceId)
            assertTrue(list[1].exited)
            assertEquals(130, list[1].exitCode)
            assertEquals("$ ls", c.terminalRead("t1", maxBytes = 100))
        }
    }

    @Test
    fun launchList() {
        val c = FakeOpCaller.fixtures(mapOf("launch.list" to "kernel/launch.list.json"))
        val groups = blocking { c.launchList(workspaceId = "ws1") }
        assertEquals(obj("workspaceId" to "ws1"), c.last.input)
        val items = groups.single().items
        assertEquals("后端", items[0].displayName)
        assertEquals("running", items[0].run?.state)
        assertEquals("build.sh", items[1].displayName)
        assertNull(items[1].run)
    }

    @Test
    fun remoteManagement() {
        val c = FakeOpCaller.fixtures(
            mapOf(
                "remote.status" to "kernel/remote.status.json",
                "remote.keys" to "kernel/remote.keys.json",
                "remote.keyCreate" to "kernel/remote.keyCreate.json",
                "remote.users" to "kernel/remote.users.json",
                "remote.audit" to "kernel/remote.audit.json",
                "fs.browse" to "kernel/fs.browse.windows.json",
            ),
        )
        blocking {
            val st = c.remoteStatus()
            assertEquals(listOf("192.168.0.0/16"), st.allowCIDRs)
            assertEquals(2, st.addresses.size)
            assertEquals("", st.error)
            val keys = c.remoteKeys()
            assertTrue(keys[0].current && !keys[0].isDevice)
            assertEquals("", keys[0].user)
            assertTrue(keys[1].isDevice)
            assertEquals("", keys[1].lastIP)
            assertNull(keys[1].lastUsedAt)
            val created = c.remoteKeyCreate("Pixel 9")
            assertTrue(created.token.startsWith("ppk_"))
            assertTrue(created.code.startsWith("ppc1_"))
            assertTrue(created.qrSvg.startsWith("<svg"))
            assertEquals("9c8d7e6f", created.key.id)
            assertEquals("liu", c.remoteUsers().single().name)
            val audit = c.remoteAudit(limit = 20)
            assertEquals(obj("limit" to 20), c.last.input)
            assertEquals(RemoteAuditEvent.OP, audit[0].event)
            assertEquals("run.start", audit[0].detail)
            assertEquals("", audit[1].detail)
            assertEquals("", audit[2].ip)
            assertTrue(audit[0].time!!.isAfter(audit[1].time))
            c.remoteKeyRevoke("5e6f7a8b")
            assertEquals(FakeOpCaller.Call("remote.keyRevoke", obj("id" to "5e6f7a8b"), null), c.last)
            c.remoteUserSet("liu", "0123456789")
            assertEquals(OpTimeout.SECONDS_30, c.last.timeoutMs)

            val b: FSBrowseResult = c.fsBrowse("C:\\Users\\me")
            assertEquals(obj("path" to "C:\\Users\\me"), c.last.input)
            val sp = ServerPath(b.sep)
            assertTrue(sp.isWindows)
            assertEquals(b.parent, sp.parent(b.path))
            assertEquals(listOf("C:\\", "D:\\"), b.roots)
            assertNull(b.entries[0].size)
            assertEquals(12L, b.entries[1].size)
        }
    }

    @Test
    fun consoleStream() {
        val msgs = sseMessages(Fixtures.text("kernel/console.stream.txt")).mapNotNull { (e, d) -> ConsoleStreamMessage.parse(e, d) }
        assertEquals(3, msgs.size) // 空批次跳过
        val first = msgs[0] as ConsoleStreamMessage.Lines
        assertEquals(listOf(18L, 19L), first.lines.map { it.seq })
        assertEquals("tick 7", ANSIParser.strip(first.lines[0].text))
        assertEquals(20L, (msgs[1] as ConsoleStreamMessage.Lines).lines.single().seq)
        assertEquals(ConsoleStreamMessage.End, msgs[2])
        assertNull(ConsoleStreamMessage.parse(null, "garbage"))
    }

    @Test
    fun terminalStream() {
        val msgs = sseMessages(Fixtures.text("kernel/terminal.stream.txt")).mapNotNull { (e, d) -> TerminalStreamMessage.parse(e, d) }
        assertEquals(5, msgs.size)
        assertEquals("demo", (msgs[0] as TerminalStreamMessage.State).terminal.name)
        val d1 = msgs[1] as TerminalStreamMessage.Data
        assertEquals(0L, d1.offset)
        assertArrayEquals("ls\r\n".toByteArray(), d1.bytes)
        assertEquals(4L, d1.end)
        val d2 = msgs[2] as TerminalStreamMessage.Data
        assertEquals(d1.end, d2.offset)
        assertEquals("中", String(d2.bytes, Charsets.UTF_8))
        assertEquals(7L, d2.end)
        val exited = (msgs[3] as TerminalStreamMessage.State).terminal
        assertTrue(exited.exited)
        assertEquals(1, exited.exitCode)
        assertEquals(TerminalStreamMessage.Closed, msgs[4])
        assertNull(TerminalStreamMessage.parse(TerminalStreamMessage.EVENT_STATE, "bad"))
    }
}
