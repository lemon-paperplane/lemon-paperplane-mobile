package cn.lemonit.paperplane.features.vcs

import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.EditorRoute
import cn.lemonit.paperplane.features.forms.FormSheets
import cn.lemonit.paperplane.kit.invokeAction
import cn.lemonit.paperplane.kit.model.ActionLocation
import cn.lemonit.paperplane.kit.model.VCSBranch
import cn.lemonit.paperplane.kit.model.VCSBranchCreateArgs
import cn.lemonit.paperplane.kit.model.VCSBranchDeleteArgs
import cn.lemonit.paperplane.kit.model.VCSCheckoutArgs
import cn.lemonit.paperplane.kit.model.VCSCleanArgs
import cn.lemonit.paperplane.kit.model.VCSCommit
import cn.lemonit.paperplane.kit.model.VCSDiscardArgs
import cn.lemonit.paperplane.kit.model.VCSFetchArgs
import cn.lemonit.paperplane.kit.model.VCSFileChange
import cn.lemonit.paperplane.kit.model.VCSIgnoreArgs
import cn.lemonit.paperplane.kit.model.VCSOp
import cn.lemonit.paperplane.kit.model.VCSPullArgs
import cn.lemonit.paperplane.kit.model.VCSPushArgs
import cn.lemonit.paperplane.kit.model.VCSReason
import cn.lemonit.paperplane.kit.model.VCSRefArgs
import cn.lemonit.paperplane.kit.model.VCSResolveArgs
import cn.lemonit.paperplane.kit.model.VCSStash
import cn.lemonit.paperplane.kit.model.VCSStashArgs
import cn.lemonit.paperplane.kit.model.VCSStatus
import cn.lemonit.paperplane.kit.model.VCSTag
import cn.lemonit.paperplane.kit.model.VCSTagPushArgs
import cn.lemonit.paperplane.kit.util.IconSource
import cn.lemonit.paperplane.kit.vcsAbort
import cn.lemonit.paperplane.kit.vcsBranchCreate
import cn.lemonit.paperplane.kit.vcsBranchDelete
import cn.lemonit.paperplane.kit.vcsBranchRename
import cn.lemonit.paperplane.kit.vcsCheckout
import cn.lemonit.paperplane.kit.vcsCheckoutFile
import cn.lemonit.paperplane.kit.vcsCherryPick
import cn.lemonit.paperplane.kit.vcsClean
import cn.lemonit.paperplane.kit.vcsContinue
import cn.lemonit.paperplane.kit.vcsFetch
import cn.lemonit.paperplane.kit.vcsIgnore
import cn.lemonit.paperplane.kit.vcsMerge
import cn.lemonit.paperplane.kit.vcsPull
import cn.lemonit.paperplane.kit.vcsPush
import cn.lemonit.paperplane.kit.vcsRebase
import cn.lemonit.paperplane.kit.vcsResolve
import cn.lemonit.paperplane.kit.vcsRevert
import cn.lemonit.paperplane.kit.vcsSkip
import cn.lemonit.paperplane.kit.vcsStage
import cn.lemonit.paperplane.kit.vcsStashApply
import cn.lemonit.paperplane.kit.vcsStashDrop
import cn.lemonit.paperplane.kit.vcsTagDelete
import cn.lemonit.paperplane.kit.vcsTagPush
import cn.lemonit.paperplane.kit.vcsUndoCommit
import cn.lemonit.paperplane.kit.vcsUnstage
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.ToastKind
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.tr
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// 版本控制的用户操作（按钮、长按菜单都走这里）。对照 Mac VCSBranches / VCSRemoteDialogs / VCSStashView /
// VCSConflictsView / VCSHistoryActions / VCSChangesView（文件菜单）里的 *Actions。

object VcsActions {
    private val nav get() = AppModel.nav

    // ---------------------------------------------------------------- 远程

    fun fetch(repo: VcsRepo) = repo.perform(tr("vcs.fetch"), VCSOp.FETCH, long = true) { api ->
        api.vcsFetch(repo.projectId, repo.root, VCSFetchArgs())
    }

    fun pull(repo: VcsRepo, args: VCSPullArgs) {
        repo.perform(tr("vcs.pull"), VCSOp.PULL, long = true, extra = { r ->
            when (r.reason) {
                VCSReason.DIVERGENT -> listOf(
                    VcsNotice.Action(tr("vcs.pull.rebase"), primary = true) {
                        repo.notice = null
                        pull(repo, args.copy(mode = "rebase"))
                    },
                    VcsNotice.Action(tr("vcs.pull.merge")) {
                        repo.notice = null
                        pull(repo, args.copy(mode = "merge"))
                    },
                )
                VCSReason.LOCAL_CHANGES -> listOf(
                    VcsNotice.Action(tr("vcs.pull.stashAndRetry"), primary = true) {
                        repo.notice = null
                        pull(repo, args.copy(autostash = true))
                    },
                )
                VCSReason.NO_UPSTREAM -> listOf(
                    VcsNotice.Action(tr("vcs.pull.chooseBranch"), primary = true) {
                        repo.notice = null
                        VcsSheets.pull(repo)
                    },
                )
                else -> emptyList()
            }
        }) { api -> api.vcsPull(repo.projectId, repo.root, args) }
    }

    fun push(repo: VcsRepo, args: VCSPushArgs) {
        repo.perform(tr("vcs.push"), VCSOp.PUSH, long = true, extra = { r ->
            if (r.reason != VCSReason.REJECTED && !r.rejected) emptyList() else listOf(
                VcsNotice.Action(tr("vcs.push.forceEllipsis").plain(), danger = true) {
                    repo.notice = null
                    confirmForce(repo, args.copy(force = true))
                },
            )
        }) { api -> api.vcsPush(repo.projectId, repo.root, args) }
    }

    fun confirmForce(repo: VcsRepo, args: VCSPushArgs) {
        Dialogs.confirm(tr("vcs.push.forceTitle"), tr("vcs.push.forceMessage"), confirmText = tr("vcs.push.force"), danger = true) {
            push(repo, args)
        }
    }

    /** 有上游时直接按默认方式拉取，否则弹面板（总览用） */
    fun quickPull(repo: VcsRepo) {
        val s = repo.status
        if (s?.upstream == null || s.upstreamGone) return VcsSheets.pull(repo)
        pull(repo, VCSPullArgs(mode = VcsPrefs.pullMode, autostash = VcsPrefs.pullAutostash.takeIf { it }))
    }

    fun quickPush(repo: VcsRepo) {
        val s = repo.status
        if (s?.upstream == null || s.upstreamGone || s.ahead == 0) return VcsSheets.push(repo)
        push(repo, VCSPushArgs())
    }

    // ---------------------------------------------------------------- 分支

    /** 签出（本地修改会被覆盖时给「智能签出」「强制签出」） */
    fun checkout(repo: VcsRepo, ref: String, title: String? = null, then: (() -> Unit)? = null) {
        val t = title ?: tr("vcs.checkout.title", ref)
        repo.perform(t, VCSOp.CHECKOUT, extra = { r ->
            if (r.reason != VCSReason.LOCAL_CHANGES) emptyList() else listOf(
                VcsNotice.Action(tr("vcs.checkout.smart"), primary = true) {
                    repo.notice = null
                    repo.perform(t, then = { if (it.ok) then?.invoke() }) { api -> api.vcsCheckout(repo.projectId, repo.root, VCSCheckoutArgs(ref, smart = true)) }
                },
                VcsNotice.Action(tr("vcs.checkout.force"), danger = true) {
                    Dialogs.confirm(tr("vcs.checkout.forceTitle"), tr("vcs.checkout.forceMessage"), confirmText = tr("vcs.checkout.force"), danger = true) {
                        repo.notice = null
                        repo.runThrowing(t) { api -> api.vcsCheckout(repo.projectId, repo.root, VCSCheckoutArgs(ref, force = true)) }
                    }
                },
            )
        }, then = { if (it.ok) then?.invoke() }) { api -> api.vcsCheckout(repo.projectId, repo.root, VCSCheckoutArgs(ref)) }
    }

    fun checkoutRef(repo: VcsRepo) {
        Dialogs.input(tr("vcs.checkoutRef").plain(), label = tr("vcs.checkoutRef.label"), confirmText = tr("vcs.checkout")) { ref -> checkout(repo, ref) }
    }

    fun checkoutAndRebase(repo: VcsRepo, branch: String) {
        val cur = repo.currentBranch ?: return
        checkout(repo, branch) {
            repo.perform(tr("vcs.branch.rebaseOnto", branch, cur), VCSOp.REBASE, long = true) { api -> api.vcsRebase(repo.projectId, repo.root, VCSRefArgs(cur)) }
        }
    }

    fun merge(repo: VcsRepo, ref: String) {
        val title = tr("vcs.branch.mergeInto", ref, repo.headTitle)
        repo.perform(title, VCSOp.MERGE, long = true, extra = { r ->
            if (r.reason != VCSReason.UNRELATED_HISTORIES) emptyList() else listOf(
                VcsNotice.Action(tr("vcs.merge.allowUnrelated"), danger = true) {
                    repo.notice = null
                    repo.perform(title, VCSOp.MERGE, long = true) { api -> api.vcsMerge(repo.projectId, repo.root, VCSRefArgs(ref, allowUnrelated = true)) }
                },
            )
        }) { api -> api.vcsMerge(repo.projectId, repo.root, VCSRefArgs(ref)) }
    }

    fun rebase(repo: VcsRepo, onto: String) {
        repo.perform(tr("vcs.branch.rebaseOnto", repo.headTitle, onto), VCSOp.REBASE, long = true) { api ->
            api.vcsRebase(repo.projectId, repo.root, VCSRefArgs(onto))
        }
    }

    fun renameBranch(repo: VcsRepo, branch: String) {
        Dialogs.input(tr("vcs.branch.rename").plain(), label = tr("vcs.branch.name"), initial = branch, confirmText = tr("common.save")) { name ->
            repo.runThrowing(tr("vcs.branch.rename").plain()) { api -> api.vcsBranchRename(repo.projectId, repo.root, branch, name) }
            repo.loadBranches()
        }
    }

    fun deleteBranch(repo: VcsRepo, branch: String) {
        Dialogs.confirm(tr("vcs.branch.deleteTitle", branch), confirmText = tr("common.delete"), danger = true) {
            val r = repo.runThrowing(tr("vcs.branch.delete"), quiet = true, extra = { r ->
                if (r.reason != VCSReason.NOT_FULLY_MERGED) emptyList() else listOf(
                    VcsNotice.Action(tr("vcs.branch.forceDelete"), danger = true) {
                        repo.notice = null
                        repo.perform(tr("vcs.branch.delete"), quiet = true, then = { if (it.ok) deleted(repo, branch, it.hash) }) { api ->
                            api.vcsBranchDelete(repo.projectId, repo.root, VCSBranchDeleteArgs(branch, force = true))
                        }
                    },
                )
            }) { api -> api.vcsBranchDelete(repo.projectId, repo.root, VCSBranchDeleteArgs(branch)) }
            if (r.ok) deleted(repo, branch, r.hash)
            repo.loadBranches()
        }
    }

    /** 删除成功：提示里给「恢复」 */
    private fun deleted(repo: VcsRepo, branch: String, hash: String?) {
        if (hash.isNullOrEmpty()) {
            Toasts.show(tr("vcs.branch.deleted", branch), ToastKind.Success)
            return
        }
        Toasts.show(tr("vcs.branch.deleted", branch), ToastKind.Success, actionText = tr("vcs.branch.restore")) {
            repo.perform(tr("vcs.branch.restore")) { api -> api.vcsBranchCreate(repo.projectId, repo.root, VCSBranchCreateArgs(branch, start = hash)) }
        }
    }

    fun deleteRemoteBranch(repo: VcsRepo, branch: String) {
        Dialogs.confirm(tr("vcs.branch.deleteRemoteTitle", branch), tr("vcs.branch.deleteRemoteMessage"), confirmText = tr("common.delete"), danger = true) {
            repo.runThrowing(tr("vcs.branch.deleteRemote"), VCSOp.BRANCH_DELETE, long = true) { api ->
                api.vcsBranchDelete(repo.projectId, repo.root, VCSBranchDeleteArgs(branch, remote = true))
            }
            repo.loadBranches()
        }
    }

    /** 分支长按 / 点按菜单 */
    fun branchEntries(repo: VcsRepo, b: VCSBranch, remote: Boolean): List<MenuEntry> {
        val cur = repo.currentBranch
        val isCurrent = !remote && b.name == cur
        val head = repo.headTitle
        return buildList {
            if (!isCurrent) add(MenuEntry.Item(tr("vcs.checkout"), "check", enabled = b.worktree == null) { checkout(repo, b.name) })
            add(MenuEntry.Item(tr("vcs.branch.newFromNamed", b.name).plain(), "git-branch") { VcsSheets.newBranch(repo, b.name, b.name) })
            if (!isCurrent && !remote && cur != null) {
                add(MenuEntry.Item(tr("vcs.branch.checkoutAndRebase", cur), "git-pull-request-arrow", enabled = b.worktree == null) { checkoutAndRebase(repo, b.name) })
            }
            if (!isCurrent) {
                add(MenuEntry.Divider)
                add(MenuEntry.Item(tr("vcs.branch.mergeInto", b.name, head), "git-merge") { merge(repo, b.name) })
                add(MenuEntry.Item(tr("vcs.branch.rebaseCurrentOnto", head, b.name), "git-pull-request-arrow") { rebase(repo, b.name) })
            }
            add(MenuEntry.Divider)
            if (!remote) {
                add(MenuEntry.Item(tr("vcs.push.ellipsis"), "arrow-up") { VcsSheets.push(repo, b.name) })
                add(MenuEntry.Item(tr("vcs.branch.setUpstream"), "link") { VcsSheets.setUpstream(repo, b.name) })
                add(MenuEntry.Item(tr("vcs.branch.rename"), "pencil") { renameBranch(repo, b.name) })
            }
            add(MenuEntry.Item(tr("vcs.copyName"), "copy") { Actions.copy(b.name) })
            if (remote) {
                add(MenuEntry.Divider)
                add(MenuEntry.Item(tr("vcs.branch.deleteRemote"), "trash-2", destructive = true) { deleteRemoteBranch(repo, b.name) })
            } else if (!isCurrent) {
                add(MenuEntry.Divider)
                add(MenuEntry.Item(tr("vcs.branch.delete"), "trash-2", destructive = true) { deleteBranch(repo, b.name) })
            }
        }
    }

    fun tagEntries(repo: VcsRepo, t: VCSTag): List<MenuEntry> = listOf(
        MenuEntry.Item(tr("vcs.checkout"), "check") { checkout(repo, t.name) },
        MenuEntry.Item(tr("vcs.branch.newFromNamed", t.name).plain(), "git-branch") { VcsSheets.newBranch(repo, t.name, t.name) },
        MenuEntry.Item(tr("vcs.showInLog"), "git-graph") { openCommit(repo, t.hash) },
        MenuEntry.Divider,
        MenuEntry.Item(tr("vcs.tag.pushOne"), "arrow-up") {
            repo.perform(tr("vcs.tag.pushOne"), VCSOp.TAG_PUSH, long = true) { api -> api.vcsTagPush(repo.projectId, repo.root, VCSTagPushArgs(t.name)) }
        },
        MenuEntry.Item(tr("vcs.copyName"), "copy") { Actions.copy(t.name) },
        MenuEntry.Divider,
        MenuEntry.Item(tr("vcs.tag.delete"), "trash-2", destructive = true) {
            Dialogs.confirm(tr("vcs.tag.deleteTitle", t.name), confirmText = tr("common.delete"), danger = true) {
                repo.runThrowing(tr("vcs.tag.delete")) { api -> api.vcsTagDelete(repo.projectId, repo.root, VCSRefArgs(t.name)) }
                repo.loadTags()
            }
        },
        MenuEntry.Item(tr("vcs.tag.deleteRemote"), "trash-2", destructive = true) {
            Dialogs.confirm(tr("vcs.tag.deleteRemoteTitle", t.name), confirmText = tr("common.delete"), danger = true) {
                repo.runThrowing(tr("vcs.tag.deleteRemote"), VCSOp.TAG_PUSH, long = true) { api ->
                    api.vcsTagPush(repo.projectId, repo.root, VCSTagPushArgs(t.name, delete = true))
                }
            }
        },
    )

    // ---------------------------------------------------------------- 储藏

    fun stashApply(repo: VcsRepo, s: VCSStash, pop: Boolean, restoreIndex: Boolean, then: (() -> Unit)? = null) {
        repo.perform(if (pop) tr("vcs.stash.pop") else tr("vcs.stash.apply"), then = {
            AppModel.scope.launch { repo.loadStashes() }
            if (it.ok) then?.invoke()
        }) { api -> api.vcsStashApply(repo.projectId, repo.root, VCSStashArgs(s.index, pop = pop.takeIf { it }, restoreIndex = restoreIndex.takeIf { it })) }
    }

    fun stashAsBranch(repo: VcsRepo, s: VCSStash, then: (() -> Unit)? = null) {
        Dialogs.input(tr("vcs.stash.asBranch").plain(), label = tr("vcs.branch.name"), confirmText = tr("common.create")) { name ->
            repo.runThrowing(tr("vcs.stash.asBranch").plain()) { api -> api.vcsStashApply(repo.projectId, repo.root, VCSStashArgs(s.index, branch = name)) }
            repo.loadStashes()
            then?.invoke()
        }
    }

    fun stashDrop(repo: VcsRepo, s: VCSStash, then: (() -> Unit)? = null) {
        Dialogs.confirm(tr("vcs.stash.dropTitle", "stash@{${s.index}}"), s.message.ifEmpty { null }, confirmText = tr("common.delete"), danger = true) {
            repo.runThrowing(tr("vcs.stash.drop"), quiet = true) { api -> api.vcsStashDrop(repo.projectId, repo.root, VCSStashArgs(s.index)) }
            repo.loadStashes()
            then?.invoke()
        }
    }

    fun stashClear(repo: VcsRepo) {
        Dialogs.confirm(tr("vcs.stash.clearTitle"), tr("vcs.stash.clearMessage", repo.stashes.size), confirmText = tr("vcs.stash.clear"), danger = true) {
            repo.runThrowing(tr("vcs.stash.clear"), quiet = true) { api -> api.vcsStashDrop(repo.projectId, repo.root, VCSStashArgs(0, all = true)) }
            repo.loadStashes()
        }
    }

    fun stashEntries(repo: VcsRepo, s: VCSStash): List<MenuEntry> = listOf(
        MenuEntry.Item(tr("vcs.stash.apply"), "archive-restore") { stashApply(repo, s, pop = false, restoreIndex = false) },
        MenuEntry.Item(tr("vcs.stash.pop"), "arrow-up-from-line") { stashApply(repo, s, pop = true, restoreIndex = false) },
        MenuEntry.Item(tr("vcs.stash.asBranch"), "git-branch") { stashAsBranch(repo, s) },
        MenuEntry.Divider,
        MenuEntry.Item(tr("vcs.stash.drop"), "trash-2", destructive = true) { stashDrop(repo, s) },
    )

    // ---------------------------------------------------------------- 冲突与进行中的操作

    fun resolve(repo: VcsRepo, paths: List<String>, side: String, then: (() -> Unit)? = null) {
        val first = paths.firstOrNull() ?: return
        val title = when (side) {
            "ours" -> tr("vcs.conflicts.useOurs")
            "theirs" -> tr("vcs.conflicts.useTheirs")
            else -> tr("vcs.conflicts.markResolved")
        }
        val args = VCSResolveArgs(first, side, paths.takeIf { it.size > 1 })
        repo.perform(title, quiet = true, extra = { r ->
            if (r.reason != VCSReason.MARKERS_REMAIN) emptyList() else listOf(
                VcsNotice.Action(tr("vcs.conflicts.markAnyway"), danger = true) {
                    repo.notice = null
                    repo.perform(title, quiet = true, then = { if (it.ok) then?.invoke() }) { api -> api.vcsResolve(repo.projectId, repo.root, args.copy(force = true)) }
                },
            )
        }, then = { if (it.ok && it.conflicts.isEmpty()) then?.invoke() }) { api -> api.vcsResolve(repo.projectId, repo.root, args) }
    }

    fun continueOp(repo: VcsRepo) = repo.perform(tr("vcs.op.continue"), VCSOp.CONTINUE, long = true) { api -> api.vcsContinue(repo.projectId, repo.root) }

    fun skip(repo: VcsRepo) = repo.perform(tr("vcs.op.skip"), VCSOp.SKIP, long = true) { api -> api.vcsSkip(repo.projectId, repo.root) }

    fun abort(repo: VcsRepo) {
        val op = VcsStyle.operationTitle(repo.status?.operation)
        Dialogs.confirm(tr("vcs.op.abortTitle", op), tr("vcs.op.abortMessage"), confirmText = tr("vcs.op.abort"), danger = true) {
            repo.runThrowing(tr("vcs.op.abort")) { api -> api.vcsAbort(repo.projectId, repo.root) }
        }
    }

    /** 我方 / 对方指的是谁（变基时 ours 是变基目标、theirs 是正在重放的提交） */
    fun sides(s: VCSStatus): Pair<String, String> {
        val d = s.operationDetail
        return if (s.operation == "rebase") {
            VcsStyle.shortHash(d?.onto).ifEmpty { tr("vcs.conflicts.upstream") } to VcsStyle.shortHash(d?.current).ifEmpty { d?.headName.orEmpty() }
        } else {
            s.branch.ifEmpty { VcsStyle.shortHash(s.headHash) } to VcsStyle.shortHash(d?.current).ifEmpty { tr("vcs.conflicts.incoming") }
        }
    }

    // ---------------------------------------------------------------- 历史

    fun cherryPick(repo: VcsRepo, hashes: List<String>) {
        val first = hashes.firstOrNull() ?: return
        repo.perform(tr("vcs.cherryPick")) { api -> api.vcsCherryPick(repo.projectId, repo.root, VCSRefArgs(first, refs = hashes.drop(1).ifEmpty { null })) }
    }

    fun revert(repo: VcsRepo, hashes: List<String>) {
        val first = hashes.firstOrNull() ?: return
        Dialogs.confirm(tr("vcs.revert.title", hashes.size), tr("vcs.revert.message"), confirmText = tr("vcs.revertCommit")) {
            repo.runThrowing(tr("vcs.revertCommit")) { api -> api.vcsRevert(repo.projectId, repo.root, VCSRefArgs(first, refs = hashes.drop(1).ifEmpty { null })) }
        }
    }

    /** 撤销最近一次提交（改动留在工作区）。HEAD 已在上游里时明确提示并强制 */
    fun undoLast(repo: VcsRepo) {
        val s = repo.status
        val pushed = s?.upstream != null && s.ahead == 0
        Dialogs.confirm(
            tr("vcs.undoCommit.title"),
            if (pushed) tr("vcs.undoCommit.pushedMessage") else tr("vcs.undoCommit.message"),
            confirmText = tr("vcs.undoCommit"),
            danger = pushed,
        ) {
            repo.runThrowing(tr("vcs.undoCommit")) { api -> api.vcsUndoCommit(repo.projectId, repo.root, pushed.takeIf { it }) }
        }
    }

    /** 把文件恢复到某个版本 */
    fun restoreFile(repo: VcsRepo, path: String, ref: String, short: String) {
        Dialogs.confirm(tr("vcs.checkoutFileVersion.title", VcsPath.name(path), short), tr("vcs.checkoutFileVersion.message"), confirmText = tr("vcs.checkoutFileVersion"), danger = true) {
            repo.runThrowing(tr("vcs.checkoutFileVersion")) { api -> api.vcsCheckoutFile(repo.projectId, repo.root, path, ref) }
        }
    }

    /** 提交的菜单（日志长按、提交详情右上角） */
    fun commitEntries(repo: VcsRepo, c: VCSCommit): List<MenuEntry> {
        val isHead = c.hash == repo.status?.headHash
        val merge = c.parents.size > 1
        val canReset = repo.currentBranch != null || repo.status?.detached == true
        return buildList {
            add(MenuEntry.Item(tr("vcs.copyHash"), "copy") { Actions.copy(c.hash) })
            add(MenuEntry.Item(tr("vcs.copySubject"), "copy") { Actions.copy(c.subject) })
            add(MenuEntry.Divider)
            add(MenuEntry.Item(tr("vcs.checkoutRevision"), "circle-dot") { checkout(repo, c.hash, tr("vcs.checkoutRevision")) })
            add(MenuEntry.Item(tr("vcs.branch.newFrom").plain(), "git-branch") { VcsSheets.newBranch(repo, c.hash, c.short) })
            add(MenuEntry.Item(tr("vcs.tag.new").plain(), "tag") { VcsSheets.newTag(repo, c.hash) })
            add(MenuEntry.Divider)
            add(MenuEntry.Item(tr("vcs.cherryPick"), "cherry", enabled = !isHead) { cherryPick(repo, listOf(c.hash)) })
            add(MenuEntry.Item(tr("vcs.revertCommit"), "undo-2") { revert(repo, listOf(c.hash)) })
            add(MenuEntry.Item(tr("vcs.reset.ellipsis").plain(), "rotate-ccw", enabled = canReset) { VcsSheets.reset(repo, c) })
            add(MenuEntry.Item(tr("vcs.reword.ellipsis").plain(), "pencil") { VcsSheets.reword(repo, c) })
            add(MenuEntry.Item(tr("vcs.rebaseInteractive.ellipsis").plain(), "list", enabled = !merge) { nav.push(VcsRebaseRoute(repo.projectId, repo.root, c)) })
            if (isHead) add(MenuEntry.Item(tr("vcs.undoCommit"), "undo-2") { undoLast(repo) })
            val plugin = pluginEntries(ActionLocation.VCS_COMMIT_MENU, repo, mapOf("hash" to JsonPrimitive(c.hash), "hashes" to JsonArray(listOf(JsonPrimitive(c.hash)))))
            if (plugin.isNotEmpty()) {
                add(MenuEntry.Divider)
                addAll(plugin)
            }
        }
    }

    // ---------------------------------------------------------------- 文件

    fun stage(repo: VcsRepo, paths: List<String>) {
        if (paths.isEmpty()) return
        repo.perform(tr("vcs.stage"), quiet = true) { api -> api.vcsStage(repo.projectId, repo.root, paths) }
    }

    fun unstage(repo: VcsRepo, paths: List<String>) {
        if (paths.isEmpty()) return
        repo.perform(tr("vcs.unstage"), quiet = true) { api -> api.vcsUnstage(repo.projectId, repo.root, paths) }
    }

    fun openInEditor(repo: VcsRepo, path: String) {
        nav.push(EditorRoute(VcsPath.join(repo.root, path), repo.projectId))
    }

    /** 文件长按菜单（变更列表、冲突列表） */
    fun fileEntries(repo: VcsRepo, f: VCSFileChange, side: VcsSide): List<MenuEntry> = buildList {
        add(MenuEntry.Item(tr("vcs.showDiff"), "file-diff") { openWorkingDiff(repo, f, side) })
        if (f.status != "D") add(MenuEntry.Item(tr("vcs.mobile.openInEditor"), "file-pen") { openInEditor(repo, f.path) })
        add(MenuEntry.Item(tr("vcs.fileHistory"), "history", enabled = !f.isUntracked && f.status != "A") { nav.push(VcsFileHistoryRoute(repo.projectId, repo.root, f.path)) })
        add(MenuEntry.Divider)
        if (f.conflicted) {
            add(MenuEntry.Item(tr("vcs.conflicts.useOurs"), "arrow-left") { resolve(repo, listOf(f.path), "ours") })
            add(MenuEntry.Item(tr("vcs.conflicts.useTheirs"), "arrow-right") { resolve(repo, listOf(f.path), "theirs") })
            add(MenuEntry.Item(tr("vcs.conflicts.markResolved"), "check") { resolve(repo, listOf(f.path), "resolved") })
        } else {
            val stageable = f.isUntracked || !f.worktreeStatus.isNullOrEmpty()
            val unstageable = !f.indexStatus.isNullOrEmpty()
            if (stageable) add(MenuEntry.Item(tr("vcs.stage"), "plus") { stage(repo, listOf(f.path)) })
            if (unstageable) add(MenuEntry.Item(tr("vcs.unstage"), "minus") { unstage(repo, listOf(f.path)) })
            add(MenuEntry.Item(tr("vcs.discard.ellipsis").plain(), "undo-2", destructive = true) {
                VcsSheets.discard(repo, listOf(f), if (side == VcsSide.UNSTAGED) "unstaged" else null)
            })
            if (f.isUntracked) {
                add(MenuEntry.Item(tr("vcs.ignore"), "eye-off") { VcsSheets.ignore(repo, listOf(f)) })
                add(MenuEntry.Item(tr("vcs.clean.ellipsis").plain(), "trash-2", destructive = true) { clean(repo, listOf(f.path)) })
            }
            add(MenuEntry.Item(tr("vcs.stash.selected").plain(), "archive") { VcsSheets.stashNew(repo, listOf(f.path)) })
        }
        add(MenuEntry.Divider)
        add(MenuEntry.Item(tr("vcs.copyPath"), "copy") { Actions.copy(f.path) })
        add(MenuEntry.Item(tr("vcs.copyAbsolutePath"), "copy") { Actions.copy(VcsPath.join(repo.root, f.path)) })
        val plugin = pluginEntries(ActionLocation.VCS_FILE_MENU, repo, mapOf("path" to JsonPrimitive(f.path), "paths" to JsonArray(listOf(JsonPrimitive(f.path)))))
        if (plugin.isNotEmpty()) {
            add(MenuEntry.Divider)
            addAll(plugin)
        }
    }

    fun ignore(repo: VcsRepo, patterns: List<String>, file: String?) {
        repo.perform(tr("vcs.ignore")) { api -> api.vcsIgnore(repo.projectId, repo.root, VCSIgnoreArgs(patterns, file)) }
    }

    fun clean(repo: VcsRepo, paths: List<String>) {
        Dialogs.confirm(tr("vcs.clean.title", paths.size), paths.take(10).joinToString("\n"), confirmText = tr("common.delete"), danger = true) {
            repo.runThrowing(tr("vcs.clean")) { api -> api.vcsClean(repo.projectId, repo.root, VCSCleanArgs(paths)) }
        }
    }

    fun discardArgs(paths: List<String>, mode: String?, keepNew: Boolean) = VCSDiscardArgs(paths, mode, keepNew.takeIf { it })

    // ---------------------------------------------------------------- 打开页面

    fun workingTarget(f: VCSFileChange, side: VcsSide) = VcsDiffTarget(
        f.path, f.oldPath, VcsDiffKind.Working(side),
        when (side) {
            VcsSide.STAGED -> f.indexStatus ?: f.status
            VcsSide.UNSTAGED -> f.worktreeStatus ?: f.status
            else -> f.status
        },
    )

    fun openWorkingDiff(repo: VcsRepo, f: VCSFileChange, side: VcsSide, siblings: List<VcsDiffTarget>? = null) {
        val t = workingTarget(f, side)
        val list = siblings ?: listOf(t)
        nav.push(VcsDiffRoute(repo.projectId, repo.root, list, list.indexOf(t).coerceAtLeast(0)))
    }

    fun openDiff(repo: VcsRepo, targets: List<VcsDiffTarget>, index: Int, title: String? = null) {
        if (targets.isEmpty()) return
        nav.push(VcsDiffRoute(repo.projectId, repo.root, targets, index.coerceIn(0, targets.lastIndex), title))
    }

    fun openCommit(repo: VcsRepo, hash: String) {
        nav.push(VcsCommitRoute(repo.projectId, repo.root, hash))
    }

    // ---------------------------------------------------------------- 插件动作

    /** 插件贡献的 vcs.* 动作：带上下文参数（root、hash/hashes、path/paths）调 project.action */
    fun pluginEntries(location: String, repo: VcsRepo, args: Map<String, JsonElement>): List<MenuEntry> =
        AppModel.data.contributions.actions(location).map { a ->
            val icon = (IconSource.parse(a.icon, a.plugin) as? IconSource.Builtin)?.name ?: "puzzle"
            MenuEntry.Item(a.title, icon) { invokePlugin(repo, a, args) }
        }

    private fun invokePlugin(repo: VcsRepo, a: cn.lemonit.paperplane.kit.model.ActionInfo, args: Map<String, JsonElement>) {
        val base = args + ("root" to JsonPrimitive(repo.root))
        suspend fun send(extra: Map<String, JsonElement>) {
            val api = AppModel.api()
            val t = api.invokeAction(repo.projectId, repo.firstModuleId(), a.plugin, a.id, base + extra)
            AppModel.track(t)
        }
        when {
            a.form.isNotEmpty() -> FormSheets.show(a.title, a.form, message = a.confirm, confirmText = a.title) { v -> send(v) }
            !a.confirm.isNullOrEmpty() -> Dialogs.confirm(a.title, a.confirm, confirmText = a.title) { send(emptyMap()) }
            else -> AppModel.perform(a.title) { send(emptyMap()) }
        }
    }
}
