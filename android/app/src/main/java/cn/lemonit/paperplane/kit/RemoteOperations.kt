package cn.lemonit.paperplane.kit

import cn.lemonit.paperplane.kit.model.FSBrowseResult
import cn.lemonit.paperplane.kit.model.PathResult
import cn.lemonit.paperplane.kit.model.RemoteAuditEntry
import cn.lemonit.paperplane.kit.model.RemoteKey
import cn.lemonit.paperplane.kit.model.RemoteKeyCreated
import cn.lemonit.paperplane.kit.model.RemoteStatus
import cn.lemonit.paperplane.kit.model.RemoteUser

// docs/remote.md：服务器端 remote.*（手机连上服务器后管理它的「允许其他设备连接」：监听、访问密钥、账号、安全日志）、
// fs.browse / fs.mkdir（选择服务器上的文件夹）、runconfig.setRemoteLinks。对照 Mac PaperplaneKit/API/RemoteOperations.swift。
// gateway.* 是桌面内核的客户端网关，手机直连服务器（net.RemoteClient），这里不提供。

/** 服务器的远程访问状态 */
suspend fun OpCaller.remoteStatus(): RemoteStatus = callAs("remote.status", RemoteStatus.serializer())

/**
 * 改远程访问设置，只传要改的字段。注意：经远程连接关掉 enabled 或改 port 会断开自己的连接。
 * allowCIDRs 为空列表表示不限制来源。
 */
suspend fun OpCaller.remoteConfigure(
    enabled: Boolean? = null,
    port: Int? = null,
    passwordLogin: Boolean? = null,
    autostart: Boolean? = null,
    allowCIDRs: List<String>? = null,
): RemoteStatus = callAs(
    "remote.configure", RemoteStatus.serializer(),
    jsonObjectOf(
        "enabled" to enabled, "port" to port, "passwordLogin" to passwordLogin,
        "autostart" to autostart, "allowCIDRs" to allowCIDRs,
    ),
    OpTimeout.SECONDS_30,
)

/** 访问密钥列表（current 标出本设备正在用的那条） */
suspend fun OpCaller.remoteKeys(): List<RemoteKey> = callList("remote.keys", RemoteKey.serializer())

/**
 * 生成访问密钥。address：额外放在连接码最前面的地址（如外网域名）。
 * 结果里的 token、code（连接码）、qrSvg（连接码二维码 SVG）只返回这一次。
 */
suspend fun OpCaller.remoteKeyCreate(name: String, address: String? = null): RemoteKeyCreated =
    callAs("remote.keyCreate", RemoteKeyCreated.serializer(), jsonObjectOf("name" to name, "address" to address?.takeIf { it.isNotEmpty() }))

/** 吊销密钥；用它的设备立即被拒（吊销本设备的密钥后自己也会断开） */
suspend fun OpCaller.remoteKeyRevoke(id: String) = send("remote.keyRevoke", jsonObjectOf("id" to id))

suspend fun OpCaller.remoteUsers(): List<RemoteUser> = callList("remote.users", RemoteUser.serializer())

/** 新建账号或改密码（至少 10 位） */
suspend fun OpCaller.remoteUserSet(name: String, password: String) =
    send("remote.userSet", jsonObjectOf("name" to name, "password" to password), OpTimeout.SECONDS_30)

/** 删除账号，并吊销用它登录的设备 */
suspend fun OpCaller.remoteUserRemove(name: String) = send("remote.userRemove", jsonObjectOf("name" to name))

/** 最近的安全事件（新的在前） */
suspend fun OpCaller.remoteAudit(limit: Int = 50): List<RemoteAuditEntry> =
    callList("remote.audit", RemoteAuditEntry.serializer(), jsonObjectOf("limit" to limit))

// ---------------------------------------------------------------- 服务器上的文件夹

/** 在服务器上新建文件夹（绝对路径，上一级必须存在），返回新路径 */
suspend fun OpCaller.fsMkdir(path: String): String =
    callAs("fs.mkdir", PathResult.serializer(), jsonObjectOf("path" to path)).path.ifEmpty { path }

/** 列出服务器上任意文件夹（path 为空 = 主目录） */
suspend fun OpCaller.fsBrowse(path: String?): FSBrowseResult =
    callAs("fs.browse", FSBrowseResult.serializer(), jsonObjectOf("path" to path?.takeIf { it.isNotEmpty() }), OpTimeout.SECONDS_30)

/** 运行地址的打开方式：forward（端口转发到手机）和/或 direct（用服务器地址直接打开），至少一个为 true */
suspend fun OpCaller.setRemoteLinks(projectId: String, configId: String, forward: Boolean, direct: Boolean) =
    send(
        "runconfig.setRemoteLinks",
        jsonObjectOf("projectId" to projectId, "configId" to configId, "forward" to forward, "direct" to direct),
    )
