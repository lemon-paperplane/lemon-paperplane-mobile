package cn.lemonit.paperplane.kit

import cn.lemonit.paperplane.kit.model.FSDeleteResult
import cn.lemonit.paperplane.kit.model.FSGrepResult
import cn.lemonit.paperplane.kit.model.FSListResult
import cn.lemonit.paperplane.kit.model.FSReadResult
import cn.lemonit.paperplane.kit.model.FSSearchHit
import cn.lemonit.paperplane.kit.model.FSStat
import cn.lemonit.paperplane.kit.model.FSWriteResult
import cn.lemonit.paperplane.kit.model.PathResult

// fs.* 操作的强类型封装（docs/editor.md 第 3 节，对照 Mac PaperplaneKit/API/FileOperations.swift）。
// 路径相对工程根、"/" 分隔，内核拒绝越出工程根。服务器上任意文件夹用 RemoteOperations.kt 的 fsBrowse / fsMkdir。

/** 列出目录（目录在前、按名称排序）；path 为空表示工程根 */
suspend fun OpCaller.fsList(projectId: String, path: String = ""): FSListResult =
    callAs("fs.list", FSListResult.serializer(), jsonObjectOf("projectId" to projectId, "path" to path))

suspend fun OpCaller.fsRead(projectId: String, path: String, maxBytes: Int? = null): FSReadResult =
    callAs("fs.read", FSReadResult.serializer(), jsonObjectOf("projectId" to projectId, "path" to path, "maxBytes" to maxBytes))

/** expectedMtime 与磁盘不一致时抛 ApiException(user_error)，reason = FSReason.MODIFIED_EXTERNALLY */
suspend fun OpCaller.fsWrite(
    projectId: String,
    path: String,
    content: String,
    encoding: String? = null,
    bom: Boolean? = null,
    lineEnding: String? = null,
    expectedMtime: String? = null,
    create: Boolean? = null,
): FSWriteResult = callAs(
    "fs.write", FSWriteResult.serializer(),
    jsonObjectOf(
        "projectId" to projectId, "path" to path, "content" to content, "encoding" to encoding, "bom" to bom,
        "lineEnding" to lineEnding, "expectedMtime" to expectedMtime, "create" to create,
    ),
)

suspend fun OpCaller.fsStat(projectId: String, paths: List<String>): List<FSStat> =
    callList("fs.stat", FSStat.serializer(), jsonObjectOf("projectId" to projectId, "paths" to paths))

suspend fun OpCaller.fsCreate(projectId: String, path: String, dir: Boolean): PathResult =
    callAs("fs.create", PathResult.serializer(), jsonObjectOf("projectId" to projectId, "path" to path, "dir" to dir))

suspend fun OpCaller.fsRename(projectId: String, from: String, to: String): PathResult =
    callAs("fs.rename", PathResult.serializer(), jsonObjectOf("projectId" to projectId, "from" to from, "to" to to))

/** 移到服务器的废纸篓，不直接删除 */
suspend fun OpCaller.fsDelete(projectId: String, paths: List<String>): FSDeleteResult =
    callAs("fs.delete", FSDeleteResult.serializer(), jsonObjectOf("projectId" to projectId, "paths" to paths))

/** 按文件名模糊匹配（快速打开） */
suspend fun OpCaller.fsSearch(projectId: String, query: String, limit: Int? = null): List<FSSearchHit> =
    callList("fs.search", FSSearchHit.serializer(), jsonObjectOf("projectId" to projectId, "query" to query, "limit" to limit))

/** 在文件里找文本（或正则）；跳过二进制、2 MB 以上的文件与忽略的目录 */
suspend fun OpCaller.fsGrep(
    projectId: String,
    query: String,
    regex: Boolean = false,
    caseSensitive: Boolean = false,
    include: String? = null,
    limit: Int? = null,
): FSGrepResult = callAs(
    "fs.grep", FSGrepResult.serializer(),
    jsonObjectOf(
        "projectId" to projectId, "query" to query, "regex" to regex, "caseSensitive" to caseSensitive,
        "include" to include, "limit" to limit,
    ),
)
