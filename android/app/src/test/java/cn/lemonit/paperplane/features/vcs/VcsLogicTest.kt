package cn.lemonit.paperplane.features.vcs

import cn.lemonit.paperplane.kit.Fixtures
import cn.lemonit.paperplane.kit.model.VCSCommit
import cn.lemonit.paperplane.kit.model.VCSDiff
import cn.lemonit.paperplane.kit.model.VCSDiffMode
import cn.lemonit.paperplane.kit.model.VCSStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 版本控制页面里与界面无关的逻辑：差异行布局、变更分组、变基校验、克隆文件夹名、差异参数 */
class VcsLogicTest {
    private val diff = Fixtures.decode("real/vcs.diff.json", VCSDiff.serializer())
    private val status = Fixtures.decode("real/vcs.status.json", VCSStatus.serializer())

    @Test
    fun diffLayoutWithoutFullText() {
        val l = VcsDiffLayout.build(diff, null, emptySet())
        // 块头、8 行（删除在新增之前）、文件末尾未知长度的折叠
        assertEquals(10, l.rows.size)
        assertEquals(VcsDiffRow.Hunk(0, "@@ -1,6 +1,7 @@"), l.rows[0])
        assertEquals('-', (l.rows[2] as VcsDiffRow.Line).kind)
        assertEquals('+', (l.rows[3] as VcsDiffRow.Line).kind)
        assertEquals(VcsDiffRow.Gap(1, -1), l.rows.last())
        assertEquals(listOf(2, 8), l.changeStarts)
        assertEquals(7, l.maxLine)
        assertEquals("line2 modified".length, l.maxChars)
    }

    @Test
    fun diffLayoutExpandsTail() {
        val text = (1..10).map { "n$it" }
        val folded = VcsDiffLayout.build(diff, text, emptySet())
        assertEquals(VcsDiffRow.Gap(1, 3), folded.rows.last())
        val open = VcsDiffLayout.build(diff, text, setOf(1))
        val tail = open.rows.takeLast(3).map { it as VcsDiffRow.Line }
        assertEquals(listOf(8, 9, 10), tail.map { it.new })
        assertTrue(tail.all { it.kind == '~' })
        // 旧侧行号按块末尾的偏移推出（旧文件少一行）
        assertEquals(listOf(7, 8, 9), tail.map { it.old })
    }

    @Test
    fun tabsAreExpandedForDisplay() {
        assertEquals("    a", VcsDiffLayout.display("\ta"))
    }

    @Test
    fun changeGroupsFollowStagingSides() {
        val g = VcsChangeGroups.build(status)
        assertEquals(listOf(VcsSide.STAGED, VcsSide.UNSTAGED, VcsSide.UNTRACKED), g.map { it.side })
        assertEquals(listOf("docs/guide.md", "src/staged.txt"), g[0].files.map { it.path })
        assertEquals(2, g[2].files.size)
        val targets = VcsChangeGroups.targets(status)
        assertEquals(VCSDiffMode.STAGED, targets.first().mode)
        assertEquals(VCSDiffMode.HEAD, targets.last().mode)
    }

    @Test
    fun diffTargetRefs() {
        val staged = VcsDiffTarget("a.txt", kind = VcsDiffKind.Working(VcsSide.STAGED))
        assertEquals("HEAD", staged.oldRef)
        assertEquals(":", staged.newRef)
        val unstaged = VcsDiffTarget("a.txt", kind = VcsDiffKind.Working(VcsSide.UNSTAGED))
        assertEquals(":", unstaged.oldRef)
        assertEquals("", unstaged.newRef)
        val commit = VcsDiffTarget("a.txt", kind = VcsDiffKind.Commit("abc", 2))
        assertEquals("abc^2", commit.oldRef)
        val args = commit.args(3, ignoreWhitespace = false, noLimit = false)
        assertEquals(VCSDiffMode.COMMIT, args.mode)
        assertEquals("abc", args.commit)
        assertEquals(2, args.parent)
        assertNull(args.ignoreWhitespace)
        assertEquals(VcsHunkMode.STAGE, VcsHunkMode.of(unstaged))
        assertEquals(VcsHunkMode.UNSTAGE, VcsHunkMode.of(staged))
        assertEquals(VcsHunkMode.NONE, VcsHunkMode.of(commit))
    }

    @Test
    fun rebaseValidation() {
        val a = VCSCommit(hash = "a", short = "a", subject = "first")
        val b = VCSCommit(hash = "b", short = "b", subject = "second")
        assertNull(VcsRebaseRules.validate(listOf(VcsRebaseItem(a), VcsRebaseItem(b, "squash"))))
        assertNotNull(VcsRebaseRules.validate(listOf(VcsRebaseItem(a, "fixup"), VcsRebaseItem(b))))
        // 第一个被丢弃时，第一个保留的提交是 squash 也不行
        assertNotNull(VcsRebaseRules.validate(listOf(VcsRebaseItem(a, "drop"), VcsRebaseItem(b, "squash"))))
        assertNotNull(VcsRebaseRules.validate(listOf(VcsRebaseItem(a, "reword", message = "  "))))
        val plan = VcsRebaseRules.plan("base", listOf(VcsRebaseItem(a, "reword", message = " new "), VcsRebaseItem(b, "pick", message = "ignored")))
        assertEquals("new", plan.steps[0].message)
        assertNull(plan.steps[1].message)
    }

    @Test
    fun cloneFolderName() {
        assertEquals("repo", VcsCloneFlow.folderName("https://github.com/org/repo.git"))
        assertEquals("repo", VcsCloneFlow.folderName("git@github.com:org/repo.git/"))
        assertEquals("x", VcsCloneFlow.folderName("ssh://host/x"))
        assertEquals("", VcsCloneFlow.folderName(""))
    }

    @Test
    fun paths() {
        assertEquals("b.txt", VcsPath.name("a/b.txt"))
        assertEquals("a", VcsPath.dir("a/b.txt"))
        assertEquals("", VcsPath.dir("b.txt"))
        assertTrue(VcsPath.isImage("x/logo.PNG"))
        assertEquals("message", "message…".plain())
    }
}
