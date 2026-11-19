package cn.lemonit.paperplane.kit

import cn.lemonit.paperplane.kit.model.VCSBlame
import cn.lemonit.paperplane.kit.model.VCSBlameArgs
import cn.lemonit.paperplane.kit.model.VCSBranchCreateArgs
import cn.lemonit.paperplane.kit.model.VCSBranchDeleteArgs
import cn.lemonit.paperplane.kit.model.VCSBranchRenameArgs
import cn.lemonit.paperplane.kit.model.VCSBranches
import cn.lemonit.paperplane.kit.model.VCSCheckoutArgs
import cn.lemonit.paperplane.kit.model.VCSCheckoutFileArgs
import cn.lemonit.paperplane.kit.model.VCSCleanArgs
import cn.lemonit.paperplane.kit.model.VCSCommitArgs
import cn.lemonit.paperplane.kit.model.VCSCommitDetail
import cn.lemonit.paperplane.kit.model.VCSCommitResult
import cn.lemonit.paperplane.kit.model.VCSCompare
import cn.lemonit.paperplane.kit.model.VCSCompareArgs
import cn.lemonit.paperplane.kit.model.VCSConfig
import cn.lemonit.paperplane.kit.model.VCSConfigGetArgs
import cn.lemonit.paperplane.kit.model.VCSConfigSetArgs
import cn.lemonit.paperplane.kit.model.VCSContainingBranches
import cn.lemonit.paperplane.kit.model.VCSDiff
import cn.lemonit.paperplane.kit.model.VCSDiffArgs
import cn.lemonit.paperplane.kit.model.VCSDiscardArgs
import cn.lemonit.paperplane.kit.model.VCSFetchArgs
import cn.lemonit.paperplane.kit.model.VCSFileContent
import cn.lemonit.paperplane.kit.model.VCSFileContentArgs
import cn.lemonit.paperplane.kit.model.VCSFileHistoryArgs
import cn.lemonit.paperplane.kit.model.VCSHunksArgs
import cn.lemonit.paperplane.kit.model.VCSIgnoreArgs
import cn.lemonit.paperplane.kit.model.VCSInitArgs
import cn.lemonit.paperplane.kit.model.VCSLog
import cn.lemonit.paperplane.kit.model.VCSLogArgs
import cn.lemonit.paperplane.kit.model.VCSMergeTools
import cn.lemonit.paperplane.kit.model.VCSOp
import cn.lemonit.paperplane.kit.model.VCSOpResult
import cn.lemonit.paperplane.kit.model.VCSPathsArgs
import cn.lemonit.paperplane.kit.model.VCSPullArgs
import cn.lemonit.paperplane.kit.model.VCSPushArgs
import cn.lemonit.paperplane.kit.model.VCSPushPreview
import cn.lemonit.paperplane.kit.model.VCSRebasePlan
import cn.lemonit.paperplane.kit.model.VCSRefArgs
import cn.lemonit.paperplane.kit.model.VCSRemote
import cn.lemonit.paperplane.kit.model.VCSRemotes
import cn.lemonit.paperplane.kit.model.VCSRepoInfo
import cn.lemonit.paperplane.kit.model.VCSRepoList
import cn.lemonit.paperplane.kit.model.VCSRepoOverview
import cn.lemonit.paperplane.kit.model.VCSReposArgs
import cn.lemonit.paperplane.kit.model.VCSResetArgs
import cn.lemonit.paperplane.kit.model.VCSResolveArgs
import cn.lemonit.paperplane.kit.model.VCSRewordArgs
import cn.lemonit.paperplane.kit.model.VCSRoot
import cn.lemonit.paperplane.kit.model.VCSSetUpstreamArgs
import cn.lemonit.paperplane.kit.model.VCSShowArgs
import cn.lemonit.paperplane.kit.model.VCSStashArgs
import cn.lemonit.paperplane.kit.model.VCSStashList
import cn.lemonit.paperplane.kit.model.VCSStashSaveArgs
import cn.lemonit.paperplane.kit.model.VCSStashShow
import cn.lemonit.paperplane.kit.model.VCSStatus
import cn.lemonit.paperplane.kit.model.VCSSubmoduleUpdateArgs
import cn.lemonit.paperplane.kit.model.VCSSubmodules
import cn.lemonit.paperplane.kit.model.VCSTagCreateArgs
import cn.lemonit.paperplane.kit.model.VCSTagPushArgs
import cn.lemonit.paperplane.kit.model.VCSTags
import cn.lemonit.paperplane.kit.model.VCSUndoCommitArgs
import cn.lemonit.paperplane.kit.model.VCSWorktreeAddArgs
import cn.lemonit.paperplane.kit.model.VCSWorktreeRemoveArgs
import cn.lemonit.paperplane.kit.model.VCSWorktrees
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer

// 版本控制：vcs.<op>，输入 {projectId, root 可选, ...Args 平铺}（docs/api.md 第 5 节）。对照 Mac Operations.swift 的 vcs 部分。
// root 为仓库绝对路径（工程内有多个仓库时用，见 vcsRoots / vcsOverview），默认工程主仓库。
// 远程操作（push/pull/fetch…）同时生成任务，输出在 consoles/<taskId>/stream；需要凭据时内核发 prompt 事件。

/** 各操作的超时（毫秒）：远程操作、钩子、变基可能很久；读操作用客户端默认值（null） */
fun vcsTimeoutMs(op: String): Long? = when (op) {
    VCSOp.PUSH, VCSOp.PULL, VCSOp.FETCH, VCSOp.CLONE, VCSOp.SUBMODULE_UPDATE, VCSOp.TAG_PUSH -> OpTimeout.HOUR
    VCSOp.COMMIT, VCSOp.MERGE, VCSOp.REBASE, VCSOp.REBASE_INTERACTIVE, VCSOp.CONTINUE, VCSOp.SKIP,
    VCSOp.CHERRY_PICK, VCSOp.REVERT, VCSOp.CHECKOUT, VCSOp.STASH_SAVE, VCSOp.STASH_APPLY, VCSOp.REWORD,
    VCSOp.BRANCH_DELETE, VCSOp.TAG_CREATE, VCSOp.TAG_DELETE, VCSOp.MERGE_TOOL,
    -> OpTimeout.MINUTES_15
    else -> null
}

/** 通用形式：args 平铺进输入，结果按 result 解码。 */
suspend fun <A, T> OpCaller.vcs(
    op: String,
    projectId: String,
    root: String?,
    argsSerializer: KSerializer<A>,
    args: A,
    result: DeserializationStrategy<T>,
    timeoutMs: Long? = null,
): T = callAs(
    "vcs.$op", result,
    argsObject(argsSerializer, args, "projectId" to projectId, "root" to root),
    timeoutMs ?: vcsTimeoutMs(op),
)

/** 没有参数的 vcs 操作 */
suspend fun <T> OpCaller.vcs(
    op: String,
    projectId: String,
    root: String?,
    result: DeserializationStrategy<T>,
    timeoutMs: Long? = null,
): T = callAs("vcs.$op", result, jsonObjectOf("projectId" to projectId, "root" to root), timeoutMs ?: vcsTimeoutMs(op))

/** 写操作的通用形式：返回 VCSOpResult（如 vcsRun(VCSOp.STAGE, pid, root, VCSPathsArgs(listOf("a.txt")))） */
suspend inline fun <reified A> OpCaller.vcsRun(op: String, projectId: String, root: String? = null, args: A): VCSOpResult =
    vcs(op, projectId, root, serializer<A>(), args, VCSOpResult.serializer())

/** 没有参数的写操作（abort / continue / skip / init…） */
suspend fun OpCaller.vcsRun(op: String, projectId: String, root: String? = null): VCSOpResult =
    vcs(op, projectId, root, VCSOpResult.serializer())

// ---------------------------------------------------------------- 仓库与状态

/** 工作区内全部仓库及状态（workspaceId 为空表示全部工作区；不需要 projectId） */
suspend fun OpCaller.vcsOverview(workspaceId: String?): List<VCSRepoOverview> =
    callList("vcs.overview", VCSRepoOverview.serializer(), jsonObjectOf("workspaceId" to workspaceId), OpTimeout.MINUTES_5)

/** 工程内的仓库（主仓库、嵌套仓库、子模块） */
suspend fun OpCaller.vcsRoots(projectId: String): List<VCSRoot> =
    callAs("vcs.${VCSOp.ROOTS}", ListSerializer(VCSRoot.serializer()), jsonObjectOf("projectId" to projectId)) { emptyList() }

suspend fun OpCaller.vcsDetect(projectId: String, root: String? = null): VCSRepoInfo =
    vcs(VCSOp.DETECT, projectId, root, VCSRepoInfo.serializer())

/** 在目录下找所有仓库（含嵌套 / 子模块） */
suspend fun OpCaller.vcsRepos(projectId: String, root: String? = null, maxDepth: Int? = null): VCSRepoList =
    vcs(VCSOp.REPOS, projectId, root, VCSReposArgs.serializer(), VCSReposArgs(maxDepth), VCSRepoList.serializer())

suspend fun OpCaller.vcsStatus(projectId: String, root: String? = null): VCSStatus =
    vcs(VCSOp.STATUS, projectId, root, VCSStatus.serializer())

suspend fun OpCaller.vcsDiff(projectId: String, root: String? = null, args: VCSDiffArgs): VCSDiff =
    vcs(VCSOp.DIFF, projectId, root, VCSDiffArgs.serializer(), args, VCSDiff.serializer())

suspend fun OpCaller.vcsFileContent(projectId: String, root: String? = null, args: VCSFileContentArgs): VCSFileContent =
    vcs(VCSOp.FILE_CONTENT, projectId, root, VCSFileContentArgs.serializer(), args, VCSFileContent.serializer())

suspend fun OpCaller.vcsBlame(projectId: String, root: String? = null, args: VCSBlameArgs): VCSBlame =
    vcs(VCSOp.BLAME, projectId, root, VCSBlameArgs.serializer(), args, VCSBlame.serializer())

// ---------------------------------------------------------------- 历史

suspend fun OpCaller.vcsLog(projectId: String, root: String? = null, args: VCSLogArgs): VCSLog =
    vcs(VCSOp.LOG, projectId, root, VCSLogArgs.serializer(), args, VCSLog.serializer())

suspend fun OpCaller.vcsFileHistory(projectId: String, root: String? = null, args: VCSFileHistoryArgs): VCSLog =
    vcs(VCSOp.FILE_HISTORY, projectId, root, VCSFileHistoryArgs.serializer(), args, VCSLog.serializer())

suspend fun OpCaller.vcsShow(projectId: String, root: String? = null, hash: String, parent: Int? = null): VCSCommitDetail =
    vcs(VCSOp.SHOW, projectId, root, VCSShowArgs.serializer(), VCSShowArgs(hash, parent), VCSCommitDetail.serializer())

suspend fun OpCaller.vcsContainingBranches(projectId: String, root: String? = null, hash: String): VCSContainingBranches =
    vcs(VCSOp.CONTAINING_BRANCHES, projectId, root, VCSShowArgs.serializer(), VCSShowArgs(hash), VCSContainingBranches.serializer())

suspend fun OpCaller.vcsCompare(projectId: String, root: String? = null, args: VCSCompareArgs): VCSCompare =
    vcs(VCSOp.COMPARE, projectId, root, VCSCompareArgs.serializer(), args, VCSCompare.serializer())

// ---------------------------------------------------------------- 提交、暂存、丢弃

suspend fun OpCaller.vcsCommit(projectId: String, root: String? = null, args: VCSCommitArgs): VCSCommitResult =
    vcs(VCSOp.COMMIT, projectId, root, VCSCommitArgs.serializer(), args, VCSCommitResult.serializer())

suspend fun OpCaller.vcsStage(projectId: String, root: String? = null, paths: List<String>): VCSOpResult =
    vcsRun(VCSOp.STAGE, projectId, root, VCSPathsArgs(paths))

suspend fun OpCaller.vcsUnstage(projectId: String, root: String? = null, paths: List<String>): VCSOpResult =
    vcsRun(VCSOp.UNSTAGE, projectId, root, VCSPathsArgs(paths))

/** 丢弃本地修改（未跟踪文件会被删除，除非 keepNew） */
suspend fun OpCaller.vcsDiscard(projectId: String, root: String? = null, args: VCSDiscardArgs): VCSOpResult =
    vcsRun(VCSOp.DISCARD, projectId, root, args)

suspend fun OpCaller.vcsStageHunks(projectId: String, root: String? = null, args: VCSHunksArgs): VCSOpResult =
    vcsRun(VCSOp.STAGE_HUNKS, projectId, root, args)

suspend fun OpCaller.vcsUnstageHunks(projectId: String, root: String? = null, args: VCSHunksArgs): VCSOpResult =
    vcsRun(VCSOp.UNSTAGE_HUNKS, projectId, root, args)

suspend fun OpCaller.vcsDiscardHunks(projectId: String, root: String? = null, args: VCSHunksArgs): VCSOpResult =
    vcsRun(VCSOp.DISCARD_HUNKS, projectId, root, args)

/** 撤销最近一次提交，改动保留在工作区（force：已推送也撤销） */
suspend fun OpCaller.vcsUndoCommit(projectId: String, root: String? = null, force: Boolean? = null): VCSOpResult =
    vcsRun(VCSOp.UNDO_COMMIT, projectId, root, VCSUndoCommitArgs(force))

suspend fun OpCaller.vcsReword(projectId: String, root: String? = null, hash: String, message: String): VCSOpResult =
    vcsRun(VCSOp.REWORD, projectId, root, VCSRewordArgs(hash, message))

/** 把文件恢复到某个版本 */
suspend fun OpCaller.vcsCheckoutFile(projectId: String, root: String? = null, path: String, ref: String): VCSOpResult =
    vcsRun(VCSOp.CHECKOUT_FILE, projectId, root, VCSCheckoutFileArgs(path, ref))

/** 删除未跟踪文件 */
suspend fun OpCaller.vcsClean(projectId: String, root: String? = null, args: VCSCleanArgs): VCSOpResult =
    vcsRun(VCSOp.CLEAN, projectId, root, args)

suspend fun OpCaller.vcsIgnore(projectId: String, root: String? = null, args: VCSIgnoreArgs): VCSOpResult =
    vcsRun(VCSOp.IGNORE, projectId, root, args)

/** 在工程文件夹初始化仓库 */
suspend fun OpCaller.vcsInit(projectId: String, initialBranch: String? = null): VCSOpResult =
    vcsRun(VCSOp.INIT, projectId, null, VCSInitArgs(initialBranch))

// ---------------------------------------------------------------- 远程

suspend fun OpCaller.vcsPush(projectId: String, root: String? = null, args: VCSPushArgs): VCSOpResult =
    vcsRun(VCSOp.PUSH, projectId, root, args)

suspend fun OpCaller.vcsPull(projectId: String, root: String? = null, args: VCSPullArgs): VCSOpResult =
    vcsRun(VCSOp.PULL, projectId, root, args)

suspend fun OpCaller.vcsFetch(projectId: String, root: String? = null, args: VCSFetchArgs): VCSOpResult =
    vcsRun(VCSOp.FETCH, projectId, root, args)

/** 将要推送的提交，以及远端是否领先 */
suspend fun OpCaller.vcsPushPreview(projectId: String, root: String? = null, args: VCSPushArgs): VCSPushPreview =
    vcs(VCSOp.PUSH_PREVIEW, projectId, root, VCSPushArgs.serializer(), args, VCSPushPreview.serializer())

suspend fun OpCaller.vcsRemotes(projectId: String, root: String? = null): VCSRemotes =
    vcs(VCSOp.REMOTES, projectId, root, VCSRemotes.serializer())

/** 新增远程或修改 url */
suspend fun OpCaller.vcsRemoteSet(projectId: String, root: String? = null, remote: VCSRemote): VCSOpResult =
    vcsRun(VCSOp.REMOTE_SET, projectId, root, remote)

suspend fun OpCaller.vcsRemoteRemove(projectId: String, root: String? = null, name: String): VCSOpResult =
    vcsRun(VCSOp.REMOTE_REMOVE, projectId, root, VCSRefArgs(name))

/** 设置或取消（upstream 为空）分支的上游 */
suspend fun OpCaller.vcsSetUpstream(projectId: String, root: String? = null, args: VCSSetUpstreamArgs): VCSOpResult =
    vcsRun(VCSOp.SET_UPSTREAM, projectId, root, args)

// ---------------------------------------------------------------- 分支

suspend fun OpCaller.vcsBranches(projectId: String, root: String? = null): VCSBranches =
    vcs(VCSOp.BRANCHES, projectId, root, VCSBranches.serializer())

suspend fun OpCaller.vcsCheckout(projectId: String, root: String? = null, args: VCSCheckoutArgs): VCSOpResult =
    vcsRun(VCSOp.CHECKOUT, projectId, root, args)

suspend fun OpCaller.vcsBranchCreate(projectId: String, root: String? = null, args: VCSBranchCreateArgs): VCSOpResult =
    vcsRun(VCSOp.BRANCH_CREATE, projectId, root, args)

suspend fun OpCaller.vcsBranchDelete(projectId: String, root: String? = null, args: VCSBranchDeleteArgs): VCSOpResult =
    vcsRun(VCSOp.BRANCH_DELETE, projectId, root, args)

suspend fun OpCaller.vcsBranchRename(projectId: String, root: String? = null, old: String, new: String): VCSOpResult =
    vcsRun(VCSOp.BRANCH_RENAME, projectId, root, VCSBranchRenameArgs(old, new))

suspend fun OpCaller.vcsMerge(projectId: String, root: String? = null, args: VCSRefArgs): VCSOpResult =
    vcsRun(VCSOp.MERGE, projectId, root, args)

suspend fun OpCaller.vcsRebase(projectId: String, root: String? = null, args: VCSRefArgs): VCSOpResult =
    vcsRun(VCSOp.REBASE, projectId, root, args)

/** 交互式变基（按计划 pick / reword / squash / fixup / drop，可调整顺序） */
suspend fun OpCaller.vcsRebaseInteractive(projectId: String, root: String? = null, plan: VCSRebasePlan): VCSOpResult =
    vcsRun(VCSOp.REBASE_INTERACTIVE, projectId, root, plan)

suspend fun OpCaller.vcsCherryPick(projectId: String, root: String? = null, args: VCSRefArgs): VCSOpResult =
    vcsRun(VCSOp.CHERRY_PICK, projectId, root, args)

suspend fun OpCaller.vcsRevert(projectId: String, root: String? = null, args: VCSRefArgs): VCSOpResult =
    vcsRun(VCSOp.REVERT, projectId, root, args)

/** mode：soft | mixed | hard | keep | merge */
suspend fun OpCaller.vcsReset(projectId: String, root: String? = null, ref: String, mode: String): VCSOpResult =
    vcsRun(VCSOp.RESET, projectId, root, VCSResetArgs(ref, mode))

// ---------------------------------------------------------------- 进行中的操作与冲突

/** 中止进行中的 merge / rebase / cherry-pick / revert */
suspend fun OpCaller.vcsAbort(projectId: String, root: String? = null): VCSOpResult = vcsRun(VCSOp.ABORT, projectId, root)

/** 解决冲突后继续 */
suspend fun OpCaller.vcsContinue(projectId: String, root: String? = null): VCSOpResult = vcsRun(VCSOp.CONTINUE, projectId, root)

/** 跳过当前提交（变基遇到空提交 / 冲突时） */
suspend fun OpCaller.vcsSkip(projectId: String, root: String? = null): VCSOpResult = vcsRun(VCSOp.SKIP, projectId, root)

suspend fun OpCaller.vcsResolve(projectId: String, root: String? = null, args: VCSResolveArgs): VCSOpResult =
    vcsRun(VCSOp.RESOLVE, projectId, root, args)

/** 在服务器上用外部合并工具打开冲突文件（手机上一般用不到） */
suspend fun OpCaller.vcsMergeTool(projectId: String, root: String? = null, paths: List<String>, tool: String? = null): VCSOpResult =
    vcsRun(VCSOp.MERGE_TOOL, projectId, root, VCSPathsArgs(paths, tool))

suspend fun OpCaller.vcsMergeTools(projectId: String, root: String? = null): VCSMergeTools =
    vcs(VCSOp.MERGE_TOOLS, projectId, root, VCSMergeTools.serializer())

// ---------------------------------------------------------------- 储藏

suspend fun OpCaller.vcsStashList(projectId: String, root: String? = null): VCSStashList =
    vcs(VCSOp.STASH_LIST, projectId, root, VCSStashList.serializer())

suspend fun OpCaller.vcsStashShow(projectId: String, root: String? = null, index: Int): VCSStashShow =
    vcs(VCSOp.STASH_SHOW, projectId, root, VCSStashArgs.serializer(), VCSStashArgs(index), VCSStashShow.serializer())

suspend fun OpCaller.vcsStashSave(projectId: String, root: String? = null, args: VCSStashSaveArgs): VCSOpResult =
    vcsRun(VCSOp.STASH_SAVE, projectId, root, args)

suspend fun OpCaller.vcsStashApply(projectId: String, root: String? = null, args: VCSStashArgs): VCSOpResult =
    vcsRun(VCSOp.STASH_APPLY, projectId, root, args)

suspend fun OpCaller.vcsStashDrop(projectId: String, root: String? = null, args: VCSStashArgs): VCSOpResult =
    vcsRun(VCSOp.STASH_DROP, projectId, root, args)

// ---------------------------------------------------------------- 标签

suspend fun OpCaller.vcsTags(projectId: String, root: String? = null): VCSTags =
    vcs(VCSOp.TAGS, projectId, root, VCSTags.serializer())

suspend fun OpCaller.vcsTagCreate(projectId: String, root: String? = null, args: VCSTagCreateArgs): VCSOpResult =
    vcsRun(VCSOp.TAG_CREATE, projectId, root, args)

/** 删除标签（args.remote 非空时同时删远程上的） */
suspend fun OpCaller.vcsTagDelete(projectId: String, root: String? = null, args: VCSRefArgs): VCSOpResult =
    vcsRun(VCSOp.TAG_DELETE, projectId, root, args)

suspend fun OpCaller.vcsTagPush(projectId: String, root: String? = null, args: VCSTagPushArgs): VCSOpResult =
    vcsRun(VCSOp.TAG_PUSH, projectId, root, args)

// ---------------------------------------------------------------- 子模块、工作树、配置

suspend fun OpCaller.vcsSubmodules(projectId: String, root: String? = null): VCSSubmodules =
    vcs(VCSOp.SUBMODULES, projectId, root, VCSSubmodules.serializer())

suspend fun OpCaller.vcsSubmoduleUpdate(projectId: String, root: String? = null, args: VCSSubmoduleUpdateArgs): VCSOpResult =
    vcsRun(VCSOp.SUBMODULE_UPDATE, projectId, root, args)

suspend fun OpCaller.vcsWorktrees(projectId: String, root: String? = null): VCSWorktrees =
    vcs(VCSOp.WORKTREES, projectId, root, VCSWorktrees.serializer())

suspend fun OpCaller.vcsWorktreeAdd(projectId: String, root: String? = null, args: VCSWorktreeAddArgs): VCSOpResult =
    vcsRun(VCSOp.WORKTREE_ADD, projectId, root, args)

suspend fun OpCaller.vcsWorktreeRemove(projectId: String, root: String? = null, args: VCSWorktreeRemoveArgs): VCSOpResult =
    vcsRun(VCSOp.WORKTREE_REMOVE, projectId, root, args)

/** 读 git 配置（如 user.name、user.email、pull.rebase） */
suspend fun OpCaller.vcsConfigGet(projectId: String, root: String? = null, keys: List<String>, global: Boolean? = null): VCSConfig =
    vcs(VCSOp.CONFIG_GET, projectId, root, VCSConfigGetArgs.serializer(), VCSConfigGetArgs(keys, global), VCSConfig.serializer())

suspend fun OpCaller.vcsConfigSet(projectId: String, root: String? = null, args: VCSConfigSetArgs): VCSOpResult =
    vcsRun(VCSOp.CONFIG_SET, projectId, root, args)
