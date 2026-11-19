package cn.lemonit.paperplane.kit.model

import java.time.Instant
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

// 对应插件 SDK 的 vcs.go。操作名为 "vcs.<op>"，输入为 {projectId, root 可选, ...Args 平铺}（docs/api.md 第 5 节）。
// git 插件返回的是 SDK 结构的 JSON 超集（扩展字段见 lemon-paperplane-plugin-git/README.md），这里一并建模。
// 类型名与 Mac PaperplaneKit/Models/VCSModels.swift 一致。
// 结果类型的字段都有默认值；参数类型（*Args）的可选字段为 null 时不输出。

/** vcs.<op> 的 op 名。 */
object VCSOp {
    const val DETECT = "detect"
    const val STATUS = "status"
    const val DIFF = "diff"
    const val COMMIT = "commit"
    const val PUSH = "push"
    const val PULL = "pull"
    const val FETCH = "fetch"
    const val BRANCHES = "branches"
    const val CHECKOUT = "checkout"
    const val BRANCH_CREATE = "branchCreate"
    const val BRANCH_DELETE = "branchDelete"
    const val BRANCH_RENAME = "branchRename"
    const val MERGE = "merge"
    const val REBASE = "rebase"
    const val ABORT = "abort"
    const val CONTINUE = "continue"
    const val LOG = "log"
    const val SHOW = "show"
    const val DISCARD = "discard"
    const val STASH_LIST = "stashList"
    const val STASH_SAVE = "stashSave"
    const val STASH_APPLY = "stashApply"
    const val STASH_DROP = "stashDrop"
    const val RESOLVE = "resolve"
    const val MERGE_TOOL = "mergeTool"
    const val CHERRY_PICK = "cherryPick"
    const val REVERT = "revert"
    const val RESET = "reset"
    const val TAGS = "tags"
    const val TAG_CREATE = "tagCreate"
    const val TAG_DELETE = "tagDelete"
    const val REMOTES = "remotes"
    const val REMOTE_SET = "remoteSet"
    const val REMOTE_REMOVE = "remoteRemove"
    const val FILE_HISTORY = "fileHistory"
    const val CLONE = "clone"
    const val INIT = "init"
    const val STAGE = "stage"
    const val UNSTAGE = "unstage"
    const val STAGE_HUNKS = "stageHunks"
    const val UNSTAGE_HUNKS = "unstageHunks"
    const val DISCARD_HUNKS = "discardHunks"
    const val UNDO_COMMIT = "undoCommit"
    const val REWORD = "reword"
    const val REBASE_INTERACTIVE = "rebaseInteractive"
    const val CHECKOUT_FILE = "checkoutFile"
    const val COMPARE = "compare"
    const val PUSH_PREVIEW = "pushPreview"
    const val BLAME = "blame"
    const val SET_UPSTREAM = "setUpstream"
    const val SUBMODULES = "submodules"
    const val SUBMODULE_UPDATE = "submoduleUpdate"
    const val WORKTREES = "worktrees"
    const val WORKTREE_ADD = "worktreeAdd"
    const val WORKTREE_REMOVE = "worktreeRemove"
    const val CONFIG_GET = "configGet"
    const val CONFIG_SET = "configSet"
    const val IGNORE = "ignore"
    const val CLEAN = "clean"
    const val REPOS = "repos"

    // git 插件的扩展操作
    const val SKIP = "skip"
    const val FILE_CONTENT = "fileContent"
    const val CONTAINING_BRANCHES = "containingBranches"
    const val TAG_PUSH = "tagPush"
    const val MERGE_TOOLS = "mergeTools"
    const val STASH_SHOW = "stashShow"

    /** 内核额外提供的「列出工程内仓库」操作 vcs.roots */
    const val ROOTS = "roots"
}

/** VCSOpResult.reason 的取值（git 插件 README「错误与结果」） */
object VCSReason {
    const val CONFLICTS = "conflicts"
    const val UP_TO_DATE = "upToDate"
    const val FAST_FORWARD = "fastForward"
    const val SQUASHED = "squashed"
    const val NEEDS_AUTH = "needsAuth"
    const val HOST_KEY = "hostKey"
    const val REJECTED = "rejected"
    const val REMOTE_REJECTED = "remoteRejected"
    const val REPO_NOT_FOUND = "repoNotFound"
    const val NETWORK = "network"
    const val LOCAL_CHANGES = "localChanges"
    const val UNTRACKED_OVERWRITTEN = "untrackedOverwritten"
    const val UNRESOLVED_CONFLICTS = "unresolvedConflicts"
    const val EMPTY_COMMIT = "emptyCommit"
    const val NOT_FULLY_MERGED = "notFullyMerged"
    const val NO_UPSTREAM = "noUpstream"
    const val DIVERGENT = "divergent"
    const val UNRELATED_HISTORIES = "unrelatedHistories"
    const val CHECKED_OUT_ELSEWHERE = "checkedOutElsewhere"
    const val LOCKED = "locked"
    const val MARKERS_REMAIN = "markersRemain"
    const val APPLY_FAILED = "applyFailed"
    const val NO_CHANGES = "noChanges"
    const val AUTOSTASH_CONFLICT = "autostashConflict"
    const val IDENTITY_MISSING = "identityMissing"
    const val FAILED = "failed"
}

/** diff / 按块操作的模式 */
object VCSDiffMode {
    /** HEAD → 工作区 */
    const val HEAD = "head"

    /** 暂存区 → 工作区 */
    const val UNSTAGED = "unstaged"

    /** HEAD → 暂存区 */
    const val STAGED = "staged"

    /** 提交 → 父提交 */
    const val COMMIT = "commit"

    /** base → head（head 空为工作区） */
    const val RANGE = "range"
}

@Serializable
data class VCSRepoInfo(
    val isRepo: Boolean = false,
    val root: String? = null,
    val vcs: String? = null,
    /** repos：nested | submodule | worktree */
    val kind: String? = null,
    val parent: String? = null,
)

/** 进行中的操作详情。SDK 声明为字符串，git 插件返回对象；两种都能解码（字符串放进 message）。 */
@Serializable
data class VCSOperationDetail(
    val op: String? = null,
    val step: Int? = null,
    val total: Int? = null,
    /** 变基中的分支 */
    val headName: String? = null,
    /** 变基目标提交 */
    val onto: String? = null,
    /** 正在应用 / 合并的提交 */
    val current: String? = null,
    /** MERGE_MSG 第一行，或字符串形式的详情 */
    val message: String? = null,
)

/** 字符串形式的 operationDetail 包成 {"message": "..."} */
object VCSOperationDetailSerializer : JsonTransformingSerializer<VCSOperationDetail>(VCSOperationDetail.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive && element.isString) JsonObject(mapOf("message" to element)) else element
}

@Serializable
data class VCSStatus(
    /** 分离头时为空 */
    val branch: String = "",
    val detached: Boolean = false,
    /** 当前提交短哈希 */
    val head: String? = null,
    val upstream: String? = null,
    val upstreamGone: Boolean = false,
    val ahead: Int = 0,
    val behind: Int = 0,
    /** merge | rebase | cherry-pick | revert | am */
    val operation: String? = null,
    @Serializable(with = VCSOperationDetailSerializer::class)
    val operationDetail: VCSOperationDetail? = null,
    val files: List<VCSFileChange> = emptyList(),
    val untrackedTruncated: Boolean = false,
    val untrackedCount: Int = 0,
    val conflicts: Int = 0,
    val stashCount: Int = 0,
    /** HEAD 完整哈希 */
    val headHash: String? = null,
    /** 还没有任何提交 */
    val unborn: Boolean = false,
)

/** status：M 修改 | A 新增 | D 删除 | R 重命名 | C 复制 | U 冲突 | ? 未跟踪 | T 类型变化 */
@Serializable
data class VCSFileChange(
    val path: String = "",
    val oldPath: String? = null,
    val status: String = "",
    val staged: Boolean = false,
    val unstaged: Boolean = false,
    val conflicted: Boolean = false,
    val additions: Int = 0,
    val deletions: Int = 0,
    val binary: Boolean = false,
    /** 暂存区相对 HEAD 的状态字母（M A D R C T），空表示没变 */
    val indexStatus: String? = null,
    /** 工作区相对暂存区的状态字母（M D T ?），空表示没变 */
    val worktreeStatus: String? = null,
    /** UU 双方修改 | AA 双方新增 | DU 我方删除 | UD 对方删除 | AU 我方新增 | UA 对方新增 | DD 双方删除 */
    val conflictType: String? = null,
    val submodule: Boolean = false,
) {
    val id: String get() = path
    val isUntracked: Boolean get() = status == "?"
}

@Serializable
data class VCSDiffArgs(
    val path: String,
    val oldPath: String? = null,
    /** commit 模式的提交（commit 非空且 mode 为空时即 commit 模式） */
    val commit: String? = null,
    /** 上下文行数，默认 3；-1 表示整个文件 */
    val context: Int? = null,
    /** head | unstaged | staged | commit | range（见 VCSDiffMode） */
    val mode: String? = null,
    val staged: Boolean? = null,
    val base: String? = null,
    val head: String? = null,
    val mergeBase: Boolean? = null,
    /** commit 模式对比第几个父提交，默认 1 */
    val parent: Int? = null,
    val ignoreWhitespace: Boolean? = null,
    /** 不做 1 MB / 5000 行限制 */
    val noLimit: Boolean? = null,
)

@Serializable
data class VCSDiff(
    val path: String = "",
    val oldPath: String? = null,
    val binary: Boolean = false,
    val tooLarge: Boolean = false,
    val hunks: List<VCSDiffHunk> = emptyList(),
    val language: String? = null,
    /** 生成本 diff 的模式（按块操作要传回同样的 mode） */
    val mode: String? = null,
    val context: Int? = null,
    /** A 新增 | D 删除 | M 修改 | R 重命名 | C 复制 | T 类型变化 */
    val status: String? = null,
    val oldMode: String? = null,
    val newMode: String? = null,
    val similarity: Int? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val untracked: Boolean = false,
)

@Serializable
data class VCSDiffHunk(
    val header: String = "",
    val oldStart: Int = 0,
    val oldLines: Int = 0,
    val newStart: Int = 0,
    val newLines: Int = 0,
    val lines: List<VCSDiffLine> = emptyList(),
)

@Serializable
data class VCSDiffLine(
    /** " " | "+" | "-" */
    val kind: String = "",
    val old: Int = 0,
    val new: Int = 0,
    val text: String = "",
    /** 文件最后一行且没有换行符 */
    val noEol: Boolean = false,
)

@Serializable
data class VCSCommitArgs(
    val message: String,
    /** 要提交的文件（含未跟踪的新文件），只提交这些路径的工作区内容；为空表示提交暂存区 */
    val paths: List<String>? = null,
    /** 提交全部变更（paths 为空时有效） */
    val all: Boolean? = null,
    /** 部分提交：文件 → 块下标（基于 mode=head、上下文 hunkContext 的 diff） */
    val hunks: Map<String, List<Int>>? = null,
    val hunkContext: Int? = null,
    val amend: Boolean? = null,
    val push: Boolean? = null,
    /** 「名字 <邮箱>」 */
    val author: String? = null,
    val signOff: Boolean? = null,
    /** 跳过 pre-commit / commit-msg 钩子 */
    val noVerify: Boolean? = null,
)

@Serializable
data class VCSCommitResult(
    val hash: String = "",
    val short: String? = null,
    val push: VCSOpResult? = null,
)

@Serializable
data class VCSPushArgs(
    val remote: String? = null,
    val branch: String? = null,
    /** 使用 --force-with-lease */
    val force: Boolean? = null,
    val setUpstream: Boolean? = null,
    val tags: Boolean? = null,
    /** tags 为 true 时：all（--tags）| follow（--follow-tags） */
    val tagsMode: String? = null,
    val noVerify: Boolean? = null,
)

@Serializable
data class VCSPullArgs(
    /** merge | rebase | ff-only | config（按 git 配置） */
    val mode: String? = null,
    val remote: String? = null,
    val branch: String? = null,
    val autostash: Boolean? = null,
)

@Serializable
data class VCSFetchArgs(
    val remote: String? = null,
    val prune: Boolean? = null,
    val tags: Boolean? = null,
)

@Serializable
data class VCSOpResult(
    val ok: Boolean = false,
    /** 给人看的简要结果（已本地化） */
    val message: String? = null,
    /** 原始输出（截断） */
    val output: String? = null,
    val conflicts: List<String> = emptyList(),
    val needsAuth: Boolean = false,
    val rejected: Boolean = false,
    /** 结构化原因（见 VCSReason） */
    val reason: String? = null,
    /** 相关文件（如会被覆盖的本地修改） */
    val files: List<String> = emptyList(),
    /** 相关提交（如被删分支的提交，可用于恢复） */
    val hash: String? = null,
    /** mergeTool 用到的工具 */
    val tool: String? = null,
    /** 远程操作（push/pull/fetch/clone/submoduleUpdate/mergeTool/tagPush）生成的任务，输出看 consoles/<taskId>/stream */
    val taskId: String? = null,
    /** clone：克隆出的目录 */
    val path: String? = null,
)

@Serializable
data class VCSBranches(
    val current: String = "",
    val local: List<VCSBranch> = emptyList(),
    val remote: List<VCSBranch> = emptyList(),
    val detached: Boolean = false,
    /** HEAD 完整哈希 */
    val head: String? = null,
    /** 最近检出过的本地分支（新的在前） */
    val recent: List<String> = emptyList(),
)

@Serializable
data class VCSBranch(
    val name: String = "",
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    /** 短哈希 */
    val head: String? = null,
    val subject: String? = null,
    val date: String? = null,
    val current: Boolean = false,
    val gone: Boolean = false,
    /** 完整哈希 */
    val hash: String? = null,
    /** 远程分支所属远程 */
    val remote: String? = null,
    /** 在别的工作树里检出着 */
    val worktree: String? = null,
    val author: String? = null,
) {
    val id: String get() = name
    val parsedDate: Instant? get() = GoTime.parse(date)
}

@Serializable
data class VCSCheckoutArgs(
    val ref: String,
    val create: Boolean? = null,
    val start: String? = null,
    val force: Boolean? = null,
    /** 储藏 → 检出 → 恢复（IDEA 的 Smart Checkout） */
    val smart: Boolean? = null,
)

@Serializable
data class VCSBranchCreateArgs(
    val name: String,
    val start: String? = null,
    val checkout: Boolean? = null,
)

@Serializable
data class VCSBranchDeleteArgs(
    val name: String,
    val force: Boolean? = null,
    val remote: Boolean? = null,
)

@Serializable
data class VCSBranchRenameArgs(
    val old: String,
    val new: String,
)

@Serializable
data class VCSRefArgs(
    val ref: String,
    /** cherryPick/revert 多个提交 */
    val refs: List<String>? = null,
    val noFF: Boolean? = null,
    val squash: Boolean? = null,
    val message: String? = null,
    val ffOnly: Boolean? = null,
    val allowUnrelated: Boolean? = null,
    /** rebase --onto */
    val onto: String? = null,
    /** cherry-pick -x */
    val recordOrigin: Boolean? = null,
    /** cherry-pick/revert -n */
    val noCommit: Boolean? = null,
    val mainline: Int? = null,
    /** tagDelete：同时删除这个远程上的标签 */
    val remote: String? = null,
)

@Serializable
data class VCSLogArgs(
    /** 空表示当前分支；"--all" 表示全部分支 */
    val ref: String? = null,
    val skip: Int? = null,
    /** 默认 200 */
    val limit: Int? = null,
    val author: String? = null,
    val grep: String? = null,
    val path: String? = null,
    val refs: List<String>? = null,
    val authors: List<String>? = null,
    val since: String? = null,
    val until: String? = null,
    val paths: List<String>? = null,
    /** topo（默认）| date */
    val order: String? = null,
    val firstParent: Boolean? = null,
    val noGraph: Boolean? = null,
)

@Serializable
data class VCSLog(
    val commits: List<VCSCommit> = emptyList(),
    val hasMore: Boolean = false,
    /** 是否带提交图（按作者/关键词/日期过滤时为 false）；旧版没有这个字段时为 null */
    val graph: Boolean? = null,
)

@Serializable
data class VCSCommit(
    val hash: String = "",
    val short: String = "",
    val parents: List<String> = emptyList(),
    val author: String = "",
    val email: String? = null,
    /** RFC3339 字符串（见 parsedDate） */
    val date: String = "",
    val subject: String = "",
    val refs: List<VCSRef> = emptyList(),
    val graph: VCSGraphRow? = null,
    /** fileHistory：该提交里文件的路径（跟踪重命名） */
    val path: String? = null,
    /** fileHistory：该提交对文件做了什么（A/M/D/R） */
    val pathStatus: String? = null,
) {
    val id: String get() = hash
    val parsedDate: Instant? get() = GoTime.parse(date)
}

@Serializable
data class VCSRef(
    val name: String = "",
    /** head | local | remote | tag */
    val kind: String = "",
)

@Serializable
data class VCSGraphRow(
    val lane: Int = 0,
    val lanes: Int = 0,
    val edges: List<VCSGraphEdge> = emptyList(),
    val color: Int = 0,
)

@Serializable
data class VCSGraphEdge(
    val from: Int = 0,
    val to: Int = 0,
    val color: Int = 0,
)

@Serializable
data class VCSShowArgs(
    val hash: String,
    /** 合并提交对比第几个父提交，默认 1 */
    val parent: Int? = null,
)

@Serializable
data class VCSCommitDetail(
    val commit: VCSCommit = VCSCommit(),
    /** 主题行之后的正文 */
    val body: String? = null,
    val files: List<VCSFileChange> = emptyList(),
    /** 完整提交信息 */
    val message: String? = null,
    val committer: String? = null,
    val committerEmail: String? = null,
    val committerDate: String? = null,
    /** 文件列表对比的是第几个父提交 */
    val parent: Int? = null,
)

@Serializable
data class VCSPathsArgs(
    val paths: List<String>,
    /** mergeTool 用：指定外部合并工具 id（mergeTools 的结果），null 表示按设置/自动 */
    val tool: String? = null,
)

/** discard 的参数（VCSPathsArgs 的超集） */
@Serializable
data class VCSDiscardArgs(
    val paths: List<String>,
    /** head（默认，恢复到 HEAD）| unstaged（只丢弃未暂存部分） */
    val mode: String? = null,
    /** 新文件只取消暂存、未跟踪文件保留 */
    val keepNew: Boolean? = null,
)

/** clean 的参数 */
@Serializable
data class VCSCleanArgs(
    val paths: List<String>,
    val all: Boolean? = null,
    val ignored: Boolean? = null,
)

@Serializable
data class VCSStashList(val stashes: List<VCSStash> = emptyList())

@Serializable
data class VCSStash(
    val index: Int = 0,
    val message: String = "",
    val branch: String? = null,
    val date: String? = null,
    /** 储藏提交，可用 diff(commit) 查看内容 */
    val hash: String? = null,
) {
    val id: Int get() = index
    val parsedDate: Instant? get() = GoTime.parse(date)
}

@Serializable
data class VCSStashSaveArgs(
    val message: String? = null,
    val includeUntracked: Boolean? = null,
    /** 只储藏这些文件 */
    val paths: List<String>? = null,
    /** 保留暂存区 */
    val keepIndex: Boolean? = null,
    /** 只储藏已暂存的 */
    val staged: Boolean? = null,
)

@Serializable
data class VCSStashArgs(
    val index: Int,
    val pop: Boolean? = null,
    /** 同时恢复暂存区（--index） */
    val restoreIndex: Boolean? = null,
    /** 恢复为新分支（git stash branch） */
    val branch: String? = null,
    /** stashDrop：清空全部 */
    val all: Boolean? = null,
)

/** stashShow 的结果 */
@Serializable
data class VCSStashShow(
    val hash: String = "",
    val files: List<VCSFileChange> = emptyList(),
    /** 储藏里的未跟踪文件 */
    val untracked: List<String> = emptyList(),
)

@Serializable
data class VCSResolveArgs(
    val path: String,
    /** ours | theirs | resolved */
    val side: String,
    val paths: List<String>? = null,
    /** resolved 时文件里还有冲突标记也标记解决 */
    val force: Boolean? = null,
)

@Serializable
data class VCSResetArgs(
    val ref: String,
    /** soft | mixed | hard | keep | merge */
    val mode: String,
)

@Serializable
data class VCSUndoCommitArgs(
    /** 提交已推送也撤销 */
    val force: Boolean? = null,
)

@Serializable
data class VCSTags(val tags: List<VCSTag> = emptyList())

@Serializable
data class VCSTag(
    val name: String = "",
    /** 指向的提交 */
    val hash: String = "",
    val subject: String? = null,
    val date: String? = null,
    val annotated: Boolean = false,
    /** 附注标签的说明 */
    val message: String? = null,
    val tagger: String? = null,
) {
    val id: String get() = name
}

@Serializable
data class VCSTagCreateArgs(
    val name: String,
    val ref: String? = null,
    /** 有 message 时建附注标签 */
    val message: String? = null,
    val force: Boolean? = null,
    /** 创建后推送到远程 */
    val push: Boolean? = null,
    val remote: String? = null,
)

/** tagPush 的参数 */
@Serializable
data class VCSTagPushArgs(
    val ref: String,
    val remote: String? = null,
    val delete: Boolean? = null,
    val force: Boolean? = null,
)

@Serializable
data class VCSRemotes(val remotes: List<VCSRemote> = emptyList())

/** 远程（也是 remoteSet 的参数） */
@Serializable
data class VCSRemote(
    val name: String = "",
    val fetchUrl: String = "",
    val pushUrl: String? = null,
    /** 网页地址（GitHub/GitLab 等） */
    val webUrl: String? = null,
) {
    val id: String get() = name
}

@Serializable
data class VCSFileHistoryArgs(
    val path: String,
    val limit: Int? = null,
    val skip: Int? = null,
    /** 默认 HEAD；"--all" 表示全部分支 */
    val ref: String? = null,
    /** 跟踪重命名，默认 true */
    val follow: Boolean? = null,
)

@Serializable
data class VCSCloneArgs(
    val url: String,
    val dest: String,
    val branch: String? = null,
    val depth: Int? = null,
    val recurseSubmodules: Boolean? = null,
)

@Serializable
data class VCSInitArgs(val initialBranch: String? = null)

@Serializable
data class VCSHunksArgs(
    val path: String,
    /** VCSDiff.hunks 的下标 */
    val hunks: List<Int>,
    /** 取 diff 时的上下文行数，需与界面取 diff 时一致 */
    val context: Int? = null,
    /** 取 diff 时用的 mode（界面显示 head diff 时传 head） */
    val mode: String? = null,
    /** 所选块的 @@ 头，用来确认 diff 没过期 */
    val headers: List<String>? = null,
    val oldPath: String? = null,
)

@Serializable
data class VCSFileContentArgs(
    val path: String,
    /** 空 = 工作区，":" = 暂存区，其它为提交 / 分支 */
    val ref: String? = null,
    val maxBytes: Int? = null,
)

@Serializable
data class VCSFileContent(
    val path: String = "",
    val ref: String? = null,
    val exists: Boolean = false,
    val size: Int = 0,
    val binary: Boolean = false,
    val tooLarge: Boolean = false,
    val text: String? = null,
    /** 二进制（图片对比）或非 UTF-8 文本 */
    val base64: String? = null,
    val notUtf8: Boolean = false,
) {
    /** 文件内容字节（base64 优先，否则 text 的 UTF-8） */
    fun bytes(): ByteArray? =
        base64?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: text?.toByteArray(Charsets.UTF_8)
}

/** containingBranches 的结果 */
@Serializable
data class VCSContainingBranches(
    val refs: List<VCSRef> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
data class VCSMergeToolInfo(
    val id: String = "",
    val name: String = "",
    val path: String = "",
    val builtin: Boolean = false,
)

/** mergeTools 的结果 */
@Serializable
data class VCSMergeTools(
    /** 用户 git 配置里的 merge.tool */
    val configured: String? = null,
    val tools: List<VCSMergeToolInfo> = emptyList(),
)

@Serializable
data class VCSRewordArgs(
    val hash: String,
    val message: String,
)

@Serializable
data class VCSRebasePlan(
    /** 从这个提交之后开始（不含它）；空为 --root */
    val base: String,
    /** 从旧到新 */
    val steps: List<VCSRebaseStep>,
)

@Serializable
data class VCSRebaseStep(
    val hash: String,
    /** pick | reword | squash | fixup | drop */
    val action: String,
    val message: String? = null,
)

@Serializable
data class VCSCheckoutFileArgs(
    val path: String,
    val ref: String,
)

@Serializable
data class VCSCompareArgs(
    val base: String,
    /** 为空表示当前分支 */
    val head: String? = null,
)

@Serializable
data class VCSCompare(
    /** 在 head 不在 base */
    val ahead: List<VCSCommit> = emptyList(),
    /** 在 base 不在 head */
    val behind: List<VCSCommit> = emptyList(),
    /** merge-base(base, head) 到 head 的文件变化 */
    val files: List<VCSFileChange> = emptyList(),
    val mergeBase: String? = null,
    val truncated: Boolean = false,
)

@Serializable
data class VCSPushPreview(
    val remote: String = "",
    val branch: String = "",
    val remoteRef: String = "",
    val newBranch: Boolean = false,
    val commits: List<VCSCommit> = emptyList(),
    /** 远端领先的提交数（>0 时推送会被拒） */
    val behind: Int = 0,
    /** 推送时会设置上游 */
    val setUpstream: Boolean = false,
    /** 提交太多，只列出前 500 个 */
    val truncated: Boolean = false,
)

@Serializable
data class VCSBlameArgs(
    val path: String,
    val ref: String? = null,
)

@Serializable
data class VCSBlame(val lines: List<VCSBlameLine> = emptyList())

@Serializable
data class VCSBlameLine(
    val line: Int = 0,
    val hash: String = "",
    val author: String = "",
    val date: String = "",
    val summary: String? = null,
)

@Serializable
data class VCSSetUpstreamArgs(
    val branch: String? = null,
    /** 如 origin/main；空表示取消跟踪 */
    val upstream: String,
)

@Serializable
data class VCSSubmodules(val submodules: List<VCSSubmodule> = emptyList())

@Serializable
data class VCSSubmodule(
    val path: String = "",
    val url: String? = null,
    val head: String? = null,
    val status: String = "",
) {
    val id: String get() = path
}

@Serializable
data class VCSSubmoduleUpdateArgs(
    val paths: List<String>? = null,
    @SerialName("init") val initialize: Boolean? = null,
    val recursive: Boolean? = null,
    val remote: Boolean? = null,
)

@Serializable
data class VCSWorktrees(val worktrees: List<VCSWorktree> = emptyList())

@Serializable
data class VCSWorktree(
    val path: String = "",
    val branch: String? = null,
    val head: String? = null,
    val main: Boolean = false,
    val locked: Boolean = false,
) {
    val id: String get() = path
}

@Serializable
data class VCSWorktreeAddArgs(
    val path: String,
    val branch: String? = null,
    val newBranch: Boolean? = null,
    val start: String? = null,
)

@Serializable
data class VCSWorktreeRemoveArgs(
    val path: String,
    val force: Boolean? = null,
)

@Serializable
data class VCSConfigGetArgs(
    val keys: List<String>,
    /** 只读全局配置 */
    val global: Boolean? = null,
)

@Serializable
data class VCSConfig(
    val values: LenientMap<String> = emptyMap(),
    val scopes: LenientMap<String> = emptyMap(),
)

@Serializable
data class VCSConfigSetArgs(
    val key: String,
    val value: String,
    val global: Boolean? = null,
    val unset: Boolean? = null,
)

@Serializable
data class VCSIgnoreArgs(
    val patterns: List<String>,
    /** 默认仓库根 .gitignore；"exclude" 表示 .git/info/exclude（不提交）；也可为子目录的 .gitignore */
    val file: String? = null,
)

@Serializable
data class VCSReposArgs(val maxDepth: Int? = null)

@Serializable
data class VCSRepoList(val repos: List<VCSRepoInfo> = emptyList())

/**
 * vcs.overview 的一项：工作区里的一个仓库（工作区内全部工程的全部仓库，去重）。
 * 写操作用 vcs.* {projectId, root}。status 是插件返回的 VCSStatus 超集；取状态失败时 status 为空、error 有说明。
 */
@Serializable
data class VCSRepoOverview(
    val projectId: String = "",
    val projectName: String = "",
    /** 仓库绝对路径（调 vcs.* 时作为 root 传） */
    val root: String = "",
    /** 相对工程根：主仓库为 "" 或 "."，工程在仓库子目录里时为 ".." */
    val relPath: String = "",
    /** 仓库目录名 */
    val name: String = "",
    val vcs: String = "",
    val status: VCSStatus? = null,
    val error: String? = null,
) {
    val id: String get() = root
}

/** vcs.changed 事件的 data */
@Serializable
data class VCSChangedEvent(
    val projectId: String = "",
    val root: String? = null,
)
