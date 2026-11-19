package cn.lemonit.paperplane.kit

import cn.lemonit.paperplane.kit.model.VCSBranches
import cn.lemonit.paperplane.kit.model.VCSCommitArgs
import cn.lemonit.paperplane.kit.model.VCSCommitDetail
import cn.lemonit.paperplane.kit.model.VCSCompare
import cn.lemonit.paperplane.kit.model.VCSConfig
import cn.lemonit.paperplane.kit.model.VCSContainingBranches
import cn.lemonit.paperplane.kit.model.VCSDiff
import cn.lemonit.paperplane.kit.model.VCSDiffMode
import cn.lemonit.paperplane.kit.model.VCSDiscardArgs
import cn.lemonit.paperplane.kit.model.VCSFileContent
import cn.lemonit.paperplane.kit.model.VCSHunksArgs
import cn.lemonit.paperplane.kit.model.VCSLog
import cn.lemonit.paperplane.kit.model.VCSMergeTools
import cn.lemonit.paperplane.kit.model.VCSOp
import cn.lemonit.paperplane.kit.model.VCSOpResult
import cn.lemonit.paperplane.kit.model.VCSPushPreview
import cn.lemonit.paperplane.kit.model.VCSReason
import cn.lemonit.paperplane.kit.model.VCSRepoOverview
import cn.lemonit.paperplane.kit.model.VCSRoot
import cn.lemonit.paperplane.kit.model.VCSStashList
import cn.lemonit.paperplane.kit.model.VCSStashShow
import cn.lemonit.paperplane.kit.model.VCSStatus
import cn.lemonit.paperplane.kit.model.get
import cn.lemonit.paperplane.kit.model.jsonOf
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 真实内核 + git 插件返回的版本控制响应（Fixtures/real/vcs.*），对照 Mac VCSFixtureTests.swift */
class VCSFixtureTest {
    private fun <T> real(name: String, d: kotlinx.serialization.DeserializationStrategy<T>): T = Fixtures.decode("real/$name", d)

    @Test
    fun status() {
        val s = real("vcs.status.json", VCSStatus.serializer())
        assertEquals("main", s.branch)
        assertEquals("origin/main", s.upstream)
        assertEquals(2, s.ahead)
        assertEquals(2, s.untrackedCount)
        assertEquals(40, s.headHash?.length)
        assertFalse(s.unborn)
        assertNull(s.operationDetail)
        val staged = s.files.first { it.path == "src/staged.txt" }
        assertEquals("A", staged.indexStatus)
        assertTrue(staged.staged)
        assertNull(staged.worktreeStatus)
        assertTrue(s.files.any { it.path == "docs/中文 名.md" && it.isUntracked })
        assertEquals(true, s.files.firstOrNull { it.path == "logo.png" }?.binary)
    }

    @Test
    fun conflictStatus() {
        val s = real("vcs.status.conflict.json", VCSStatus.serializer())
        assertEquals("merge", s.operation)
        assertEquals(3, s.conflicts)
        assertEquals("merge", s.operationDetail?.op)
        assertEquals("Merge branch 'other'", s.operationDetail?.message)
        assertEquals(listOf("AA", "DU", "UU"), s.files.filter { it.conflicted }.mapNotNull { it.conflictType }.sorted())
        // SDK 里 operationDetail 是字符串：两种形态都要能解
        val str = PaperplaneJson.decodeFromString(VCSStatus.serializer(), """{"branch":"x","operationDetail":"rebase 2/5"}""")
        assertEquals("rebase 2/5", str.operationDetail?.message)
        val none = PaperplaneJson.decodeFromString(VCSStatus.serializer(), """{"branch":"x","operationDetail":null}""")
        assertNull(none.operationDetail)
    }

    @Test
    fun diff() {
        val d = real("vcs.diff.json", VCSDiff.serializer())
        assertEquals("head", d.mode)
        assertEquals(3, d.context)
        assertEquals("M", d.status)
        assertEquals(2, d.additions)
        assertEquals(8, d.hunks.first().lines.size)
        assertEquals("-", d.hunks.first().lines[1].kind)
        assertEquals(0, d.hunks.first().lines[1].new)
        val b = real("vcs.diff.binary.json", VCSDiff.serializer())
        assertTrue(b.binary)
        assertTrue(b.hunks.isEmpty())
    }

    @Test
    fun logShowBranches() {
        val log = real("vcs.log.json", VCSLog.serializer())
        assertEquals(5, log.commits.size)
        assertTrue(log.hasMore)
        assertEquals(true, log.graph)
        val merge = log.commits.first { it.parents.size == 2 }
        assertEquals(2, merge.graph?.edges?.size)
        assertTrue(merge.refs.any { it.kind == "tag" })
        assertEquals("head", log.commits[0].refs.first().kind)
        assertNotNull(log.commits[0].parsedDate)

        val show = real("vcs.show.json", VCSCommitDetail.serializer())
        assertEquals(2, show.commit.parents.size)
        assertEquals("Merge branch 'feature/login'", show.message)
        assertEquals(1, show.parent)
        assertEquals("A", show.files.first().status)

        val br = real("vcs.branches.json", VCSBranches.serializer())
        assertEquals("main", br.current)
        assertEquals(listOf("main", "feature/login"), br.recent)
        assertEquals(2, br.local.first { it.current }.ahead)
        assertEquals("origin", br.remote.first().remote)

        val hist = real("vcs.fileHistory.json", VCSLog.serializer())
        assertEquals("src/a.txt", hist.commits.first().path)
        assertNotNull(hist.commits.first().pathStatus)

        val cmp = real("vcs.compare.json", VCSCompare.serializer())
        assertFalse(cmp.ahead.isEmpty())
        assertNotNull(cmp.mergeBase)
    }

    @Test
    fun remoteAndResults() {
        val push = real("vcs.push.json", VCSOpResult.serializer())
        assertTrue(push.ok)
        assertNotNull(push.taskId)
        assertEquals(VCSReason.UP_TO_DATE, real("vcs.fetch.json", VCSOpResult.serializer()).reason)
        assertEquals(VCSReason.UP_TO_DATE, real("vcs.pull.json", VCSOpResult.serializer()).reason)
        val pv = real("vcs.pushPreview.json", VCSPushPreview.serializer())
        assertEquals("origin", pv.remote)
        assertEquals(2, pv.commits.size)
        assertEquals(0, pv.behind)
        val markers = real("vcs.resolve.markers.json", VCSOpResult.serializer())
        assertFalse(markers.ok)
        assertEquals(VCSReason.MARKERS_REMAIN, markers.reason)
        assertEquals(listOf("both.txt"), markers.files)
        val cont = real("vcs.continue.unresolved.json", VCSOpResult.serializer())
        assertEquals(VCSReason.UNRESOLVED_CONFLICTS, cont.reason)
        assertEquals(3, cont.conflicts.size)
        assertTrue(real("vcs.stashSave.json", VCSOpResult.serializer()).ok)
    }

    @Test
    fun extensions() {
        val stashes = real("vcs.stashList.json", VCSStashList.serializer())
        assertEquals("wip test", stashes.stashes.first().message)
        assertNotNull(stashes.stashes.first().hash)
        assertNotNull(stashes.stashes.first().parsedDate)
        val show = real("vcs.stashShow.json", VCSStashShow.serializer())
        assertEquals(4, show.files.size)
        assertEquals(2, show.untracked.size)
        val fc = real("vcs.fileContent.json", VCSFileContent.serializer())
        assertTrue(fc.binary)
        assertTrue(fc.exists)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), fc.bytes()!!.copyOfRange(0, 4))
        val cb = real("vcs.containingBranches.json", VCSContainingBranches.serializer())
        assertTrue(cb.refs.any { it.kind == "tag" })
        assertFalse(real("vcs.mergeTools.json", VCSMergeTools.serializer()).tools.isEmpty())
        assertEquals("local", real("vcs.configGet.json", VCSConfig.serializer()).scopes["user.name"])
        val roots = real("vcs.roots.json", ListSerializer(VCSRoot.serializer()))
        assertEquals(".", roots.first().relPath)
    }

    @Test
    fun overview() {
        val list = real("vcs.overview.json", ListSerializer(VCSRepoOverview.serializer()))
        assertEquals(4, list.size)
        assertEquals(list.size, list.map { it.root }.toSet().size)
        val demo = list.first { it.name == "demo" }
        assertEquals("demo", demo.projectName)
        assertEquals("main", demo.status?.branch)
        assertFalse(demo.status?.files.isNullOrEmpty())
        assertNull(demo.error)
        val conflict = list.first { it.name == "conflict" }
        assertEquals("merge", conflict.status?.operation)
        assertEquals("merge", conflict.status?.operationDetail?.op)
        assertTrue((conflict.status?.conflicts ?: 0) > 0)
        val nested = list.first { it.name == "nested" }
        assertEquals("nested", nested.relPath)
        assertEquals(conflict.projectId, nested.projectId)
        // 取状态失败的仓库：没有 status，有 error
        val failed = PaperplaneJson.decodeFromString(
            VCSRepoOverview.serializer(),
            """{"projectId":"p","projectName":"P","root":"/r","relPath":"","name":"r","vcs":"git","error":"boom"}""",
        )
        assertNull(failed.status)
        assertEquals("boom", failed.error)
        assertEquals(list.first().root, list.first().id)
    }

    @Test
    fun argsEncoding() {
        val h = jsonOf(VCSHunksArgs.serializer(), VCSHunksArgs("a.txt", listOf(0, 2), context = 3, mode = VCSDiffMode.HEAD, headers = listOf("@@ -1 +1 @@")))
        assertEquals(JsonPrimitive("head"), h["mode"])
        assertEquals(JsonArray(listOf(JsonPrimitive("@@ -1 +1 @@"))), h["headers"])
        assertNull(h["oldPath"])
        val c = jsonOf(VCSCommitArgs.serializer(), VCSCommitArgs("m", paths = listOf("a"), hunks = mapOf("b" to listOf(1)), signOff = true))
        assertEquals(JsonObject(mapOf("b" to JsonArray(listOf(JsonPrimitive(1))))), c["hunks"])
        assertNull(c["amend"])
        val d = jsonOf(VCSDiscardArgs.serializer(), VCSDiscardArgs(listOf("x"), keepNew = true))
        assertEquals(JsonPrimitive(true), d["keepNew"])
        assertEquals(OpTimeout.HOUR, vcsTimeoutMs(VCSOp.PUSH))
        assertEquals(OpTimeout.MINUTES_15, vcsTimeoutMs(VCSOp.COMMIT))
        assertNull(vcsTimeoutMs(VCSOp.STATUS))
    }
}
