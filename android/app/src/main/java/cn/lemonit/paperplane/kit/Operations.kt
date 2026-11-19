package cn.lemonit.paperplane.kit

import cn.lemonit.paperplane.kit.model.AppSettings
import cn.lemonit.paperplane.kit.model.AvailableVersion
import cn.lemonit.paperplane.kit.model.Contributions
import cn.lemonit.paperplane.kit.model.Diagnosis
import cn.lemonit.paperplane.kit.model.FixResult
import cn.lemonit.paperplane.kit.model.LaunchAddResult
import cn.lemonit.paperplane.kit.model.LaunchGroup
import cn.lemonit.paperplane.kit.model.LaunchIDs
import cn.lemonit.paperplane.kit.model.LaunchResult
import cn.lemonit.paperplane.kit.model.LenientMapSerializer
import cn.lemonit.paperplane.kit.model.LogsResult
import cn.lemonit.paperplane.kit.model.MirrorStatus
import cn.lemonit.paperplane.kit.model.Option
import cn.lemonit.paperplane.kit.model.PluginInfo
import cn.lemonit.paperplane.kit.model.PortKillResult
import cn.lemonit.paperplane.kit.model.Project
import cn.lemonit.paperplane.kit.model.ProjectSummary
import cn.lemonit.paperplane.kit.model.Prompt
import cn.lemonit.paperplane.kit.model.Run
import cn.lemonit.paperplane.kit.model.RunConfig
import cn.lemonit.paperplane.kit.model.RunConfigTypeChoice
import cn.lemonit.paperplane.kit.model.RunnableList
import cn.lemonit.paperplane.kit.model.ScanEntry
import cn.lemonit.paperplane.kit.model.StopAllResult
import cn.lemonit.paperplane.kit.model.StoreEntry
import cn.lemonit.paperplane.kit.model.SystemInfo
import cn.lemonit.paperplane.kit.model.TaskInfo
import cn.lemonit.paperplane.kit.model.TerminalBusy
import cn.lemonit.paperplane.kit.model.TerminalHistory
import cn.lemonit.paperplane.kit.model.TerminalInfo
import cn.lemonit.paperplane.kit.model.TerminalText
import cn.lemonit.paperplane.kit.model.Toolchain
import cn.lemonit.paperplane.kit.model.ToolchainKind
import cn.lemonit.paperplane.kit.model.ToolchainOverride
import cn.lemonit.paperplane.kit.model.ViewEventResult
import cn.lemonit.paperplane.kit.model.ViewRenderResult
import cn.lemonit.paperplane.kit.model.Workspace
import cn.lemonit.paperplane.kit.model.stringValue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

// docs/api.md 第 5 节全部操作的强类型封装（对照 Mac PaperplaneKit/API/Operations.swift）。界面代码优先用这里的函数；
// 插件贡献的工具（<pluginId>.<tool>）用通用的 call / callRaw。
// 手机直连远程服务器，下面这些不提供：gateway.*（桌面内核的网关）、system.shutdown / mcp.config（服务器拒绝远程调用）、
// cli.install（装到服务器 PATH，手机上用不到）。版本控制见 VcsOperations.kt，文件见 FileOperations.kt，
// 远程设置见 RemoteOperations.kt，资源监控见 ResourceOperations.kt。

// ---------------------------------------------------------------- 系统与设置

suspend fun OpCaller.systemInfo(timeoutMs: Long? = null): SystemInfo =
    callAs("system.info", SystemInfo.serializer(), timeoutMs = timeoutMs)

suspend fun OpCaller.getSettings(): AppSettings = callAs("settings.get", AppSettings.serializer())

/** JSON merge：只传要改的字段（如 {"theme": "dark"}、{"editor": {"fontSize": 14}}） */
suspend fun OpCaller.updateSettings(patch: JsonObject): AppSettings =
    callAs("settings.update", AppSettings.serializer(), patch)

suspend fun OpCaller.mirrorsStatus(): MirrorStatus = callAs("mirrors.status", MirrorStatus.serializer())

/** 重新测速（最多 1 分钟） */
suspend fun OpCaller.mirrorsProbe(): MirrorStatus =
    callAs("mirrors.probe", MirrorStatus.serializer(), timeoutMs = OpTimeout.MINUTE)

/** 全部插件的界面贡献（已本地化；插件变化后重新取） */
suspend fun OpCaller.contributions(): Contributions = callAs("ui.contributions", Contributions.serializer())

/** 回答对话框（prompt 事件） */
suspend fun OpCaller.respondPrompt(id: String, cancelled: Boolean, values: Map<String, JsonElement>) =
    send("ui.respond", jsonObjectOf("promptId" to id, "cancelled" to cancelled, "values" to JsonObject(values)))

/** 尚未回答的对话框（重连时补弹） */
suspend fun OpCaller.pendingPrompts(): List<Prompt> = callList("ui.prompts", Prompt.serializer())

// ---------------------------------------------------------------- 工作区

suspend fun OpCaller.workspaces(): List<Workspace> = callList("workspace.list", Workspace.serializer())

suspend fun OpCaller.createWorkspace(name: String): Workspace =
    callAs("workspace.create", Workspace.serializer(), jsonObjectOf("name" to name))

suspend fun OpCaller.renameWorkspace(id: String, name: String): Workspace =
    callAs("workspace.rename", Workspace.serializer(), jsonObjectOf("workspaceId" to id, "name" to name))

/** 不删磁盘文件；不给 moveTo 时其中的工程从纸飞机里移除 */
suspend fun OpCaller.deleteWorkspace(id: String, moveTo: String? = null) =
    send("workspace.delete", jsonObjectOf("workspaceId" to id, "moveTo" to moveTo))

suspend fun OpCaller.reorderWorkspaces(ids: List<String>) =
    send("workspace.reorder", jsonObjectOf("workspaceIds" to ids))

// ---------------------------------------------------------------- 工程

/** 工程卡片（按工作区内顺序）；workspaceId 为空表示全部 */
suspend fun OpCaller.projects(workspaceId: String? = null): List<ProjectSummary> =
    callList("project.list", ProjectSummary.serializer(), jsonObjectOf("workspaceId" to workspaceId))

suspend fun OpCaller.getProject(id: String): Project =
    callAs("project.get", Project.serializer(), jsonObjectOf("projectId" to id))

/** 批量导入前预览（path 是服务器上的文件夹） */
suspend fun OpCaller.scanProjects(path: String, maxDepth: Int? = null): List<ScanEntry> =
    callList("project.scan", ScanEntry.serializer(), jsonObjectOf("path" to path, "maxDepth" to maxDepth), OpTimeout.MINUTES_2)

/** 导入服务器上的一个文件夹（识别 + 分析完成后返回） */
suspend fun OpCaller.importProject(path: String, workspaceId: String, name: String? = null): Project =
    callAs(
        "project.import", Project.serializer(),
        jsonObjectOf("path" to path, "workspaceId" to workspaceId, "name" to name), OpTimeout.MINUTES_10,
    )

suspend fun OpCaller.importProjects(paths: List<String>, workspaceId: String): List<Project> =
    callList("project.importMany", Project.serializer(), jsonObjectOf("paths" to paths, "workspaceId" to workspaceId), OpTimeout.MINUTES_30)

/** project.clone：克隆并导入（同步等克隆完成；克隆输出在任务控制台） */
suspend fun OpCaller.cloneProject(url: String, dest: String, branch: String? = null, workspaceId: String? = null): Project =
    callAs(
        "project.clone", Project.serializer(),
        jsonObjectOf("url" to url, "dest" to dest, "branch" to branch, "workspaceId" to workspaceId), OpTimeout.HOUR,
    )

/** 从纸飞机移除（不删磁盘文件） */
suspend fun OpCaller.removeProject(id: String) = send("project.remove", jsonObjectOf("projectId" to id))

suspend fun OpCaller.renameProject(id: String, name: String): ProjectSummary =
    callAs("project.rename", ProjectSummary.serializer(), jsonObjectOf("projectId" to id, "name" to name))

suspend fun OpCaller.moveProject(id: String, workspaceId: String, index: Int? = null) =
    send("project.move", jsonObjectOf("projectId" to id, "workspaceId" to workspaceId, "index" to index))

suspend fun OpCaller.reorderProjects(workspaceId: String, projectIds: List<String>) =
    send("project.reorder", jsonObjectOf("workspaceId" to workspaceId, "projectIds" to projectIds))

suspend fun OpCaller.reimportProject(id: String): TaskInfo =
    callAs("project.reimport", TaskInfo.serializer(), jsonObjectOf("projectId" to id))

suspend fun OpCaller.syncProject(id: String, moduleId: String? = null): TaskInfo =
    callAs("project.sync", TaskInfo.serializer(), jsonObjectOf("projectId" to id, "moduleId" to moduleId))

/**
 * env：整体替换工程环境变量；toolchains：kind → ToolchainOverride（{id} 或 {constraint}），值为 null 表示清除该 kind 的覆盖。
 * 两个都为 null 的不改。
 */
suspend fun OpCaller.updateProjectSettings(
    id: String,
    env: Map<String, String>? = null,
    toolchains: Map<String, ToolchainOverride?>? = null,
): Project {
    val input = LinkedHashMap<String, JsonElement>()
    input["projectId"] = toJsonElement(id)!!
    if (env != null) input["env"] = toJsonElement(env)!!
    if (toolchains != null) {
        input["toolchains"] = JsonObject(
            toolchains.mapValues { (_, v) -> v?.let { PaperplaneJson.encodeToJsonElement(ToolchainOverride.serializer(), it) } ?: JsonNull },
        )
    }
    return callAs("project.settings", Project.serializer(), JsonObject(input))
}

/** 在服务器上用 finder | terminal | idea | vscode | default 打开（手机上一般用不到） */
suspend fun OpCaller.openProject(id: String, moduleId: String? = null, with: String) =
    send("project.open", jsonObjectOf("projectId" to id, "moduleId" to moduleId, "with" to with))

/** 静态问题 + 缺工具链 + 依赖未装 */
suspend fun OpCaller.projectProblems(id: String): List<Diagnosis> =
    callList("project.problems", Diagnosis.serializer(), jsonObjectOf("projectId" to id))

/** 运行插件动作；runId：挂在 run.toolbar 的动作触发时所在的运行 */
suspend fun OpCaller.invokeAction(
    projectId: String,
    moduleId: String,
    plugin: String,
    action: String,
    args: Map<String, JsonElement>? = null,
    runId: String? = null,
): TaskInfo = callAs(
    "project.action", TaskInfo.serializer(),
    jsonObjectOf(
        "projectId" to projectId, "moduleId" to moduleId, "plugin" to plugin, "action" to action,
        "args" to args?.let { JsonObject(it) }, "runId" to runId,
    ),
)

suspend fun OpCaller.installDependencies(projectId: String, moduleId: String? = null, clean: Boolean = false): TaskInfo =
    callAs("deps.install", TaskInfo.serializer(), jsonObjectOf("projectId" to projectId, "moduleId" to moduleId, "clean" to clean.takeIf { it }))

// ---------------------------------------------------------------- 运行配置

/** 可新建的类型（表单选项已解析） */
suspend fun OpCaller.runConfigTypes(projectId: String, moduleId: String): List<RunConfigTypeChoice> =
    callList("runconfig.types", RunConfigTypeChoice.serializer(), jsonObjectOf("projectId" to projectId, "moduleId" to moduleId))

suspend fun OpCaller.createRunConfig(
    projectId: String,
    moduleId: String,
    type: String,
    name: String,
    options: Map<String, JsonElement>,
    env: Map<String, String>,
): RunConfig = callAs(
    "runconfig.create", RunConfig.serializer(),
    jsonObjectOf(
        "projectId" to projectId, "moduleId" to moduleId, "type" to type, "name" to name,
        "options" to JsonObject(options), "env" to env,
    ),
)

/** options 合并（值为 JsonNull 删除该项），env 整体替换；标记 edited */
suspend fun OpCaller.updateRunConfig(
    projectId: String,
    configId: String,
    name: String? = null,
    options: Map<String, JsonElement>? = null,
    env: Map<String, String>? = null,
): RunConfig = callAs(
    "runconfig.update", RunConfig.serializer(),
    jsonObjectOf(
        "projectId" to projectId, "configId" to configId, "name" to name,
        "options" to options?.let { JsonObject(it) }, "env" to env,
    ),
)

suspend fun OpCaller.deleteRunConfig(projectId: String, configId: String) =
    send("runconfig.delete", jsonObjectOf("projectId" to projectId, "configId" to configId))

/** 丢掉用户修改，恢复插件生成的版本 */
suspend fun OpCaller.resetRunConfig(projectId: String, configId: String): RunConfig =
    callAs("runconfig.reset", RunConfig.serializer(), jsonObjectOf("projectId" to projectId, "configId" to configId))

/** 实时取表单里 live 的下拉选项（如 ios.devices / android.devices） */
suspend fun OpCaller.liveOptions(projectId: String, moduleId: String?, keys: List<String>): Map<String, List<Option>> =
    callAs(
        "runconfig.options", LenientMapSerializer(String.serializer(), ListSerializer(Option.serializer())),
        jsonObjectOf("projectId" to projectId, "moduleId" to moduleId, "keys" to keys), OpTimeout.SECONDS_30,
    ) { emptyMap() }

suspend fun OpCaller.setDefaultRunConfig(projectId: String, configId: String) =
    send("runconfig.setDefault", jsonObjectOf("projectId" to projectId, "configId" to configId))

// ---------------------------------------------------------------- 运行

/** 启动（configId 为空用默认配置；同一配置已在运行则返回它） */
suspend fun OpCaller.startRun(projectId: String, configId: String? = null): Run =
    callAs("run.start", Run.serializer(), jsonObjectOf("projectId" to projectId, "configId" to configId))

suspend fun OpCaller.stopRun(runId: String): Run = callAs("run.stop", Run.serializer(), jsonObjectOf("runId" to runId))

/** 重启，返回新的运行 */
suspend fun OpCaller.restartRun(runId: String): Run = callAs("run.restart", Run.serializer(), jsonObjectOf("runId" to runId))

suspend fun OpCaller.restartRun(projectId: String, configId: String): Run =
    callAs("run.restart", Run.serializer(), jsonObjectOf("projectId" to projectId, "configId" to configId))

/** 停止全部（或某工作区 / 工程的） */
suspend fun OpCaller.stopAll(workspaceId: String? = null, projectId: String? = null): StopAllResult =
    callAs("run.stopAll", StopAllResult.serializer(), jsonObjectOf("workspaceId" to workspaceId, "projectId" to projectId))

/** 运行列表（含最近结束的） */
suspend fun OpCaller.runs(projectId: String? = null, active: Boolean? = null): List<Run> =
    callList("run.list", Run.serializer(), jsonObjectOf("projectId" to projectId, "active" to active))

suspend fun OpCaller.getRun(id: String): Run = callAs("run.get", Run.serializer(), jsonObjectOf("runId" to id))

/**
 * 读最近的日志（consoleId = runId 或 taskId）：最后 tail 行（默认 200，最多 5000），since 只要 seq 更大的，
 * grep 为不区分大小写的正则，stream 只要 stdout | stderr | system。raw=true 保留 ANSI 颜色（界面着色时要传）。
 */
suspend fun OpCaller.runLogs(
    consoleId: String,
    tail: Int? = null,
    since: Long? = null,
    grep: String? = null,
    stream: String? = null,
    raw: Boolean? = null,
): LogsResult = callAs(
    "run.logs", LogsResult.serializer(),
    jsonObjectOf("consoleId" to consoleId, "tail" to tail, "since" to since, "grep" to grep, "stream" to stream, "raw" to raw),
)

/**
 * 分页读完整日志（含早已滚出内存、只在日志文件里的行）：从第 from 条（1 = 第一行）起最多 limit 行（默认 500，最多 5000）。
 * 结果的 firstSeq / lastSeq 是能读到的范围，下一页用 result.nextFrom。日志文件里的老行只有文本（t、s 为空）。
 */
suspend fun OpCaller.runLogsPage(consoleId: String, from: Long, limit: Int? = null, raw: Boolean? = null): LogsResult =
    callAs(
        "run.logs", LogsResult.serializer(),
        jsonObjectOf("consoleId" to consoleId, "from" to from.coerceAtLeast(1), "limit" to limit, "raw" to raw),
    )

/** 写 stdin（自动补换行） */
suspend fun OpCaller.runInput(runId: String, text: String) = send("run.input", jsonObjectOf("runId" to runId, "text" to text))

/** 清空运行的内存控制台缓冲 */
suspend fun OpCaller.clearRun(runId: String) = send("run.clear", jsonObjectOf("runId" to runId))

/**
 * 一键修复：运行中的诊断传 runId（+ diagnosisId）；任务（安装 / 同步）的诊断传 taskId + diagnosisId；
 * 静态问题传 projectId + diagnosisId。
 */
suspend fun OpCaller.applyFix(
    fixId: String,
    runId: String? = null,
    taskId: String? = null,
    projectId: String? = null,
    diagnosisId: String? = null,
): FixResult = callAs(
    "fix.apply", FixResult.serializer(),
    jsonObjectOf("runId" to runId, "taskId" to taskId, "projectId" to projectId, "diagnosisId" to diagnosisId, "fixId" to fixId),
    OpTimeout.MINUTES_10,
)

/** 结束服务器上占用端口的进程 */
suspend fun OpCaller.killPort(port: Int): PortKillResult =
    callAs("port.kill", PortKillResult.serializer(), jsonObjectOf("port" to port))

// ---------------------------------------------------------------- 启动面板

/** 「启动」页：按工程分组的启动项，带各自最近一次运行 */
suspend fun OpCaller.launchList(workspaceId: String? = null, projectId: String? = null): List<LaunchGroup> =
    callList("launch.list", LaunchGroup.serializer(), jsonObjectOf("workspaceId" to workspaceId, "projectId" to projectId))

suspend fun OpCaller.launchAdd(projectId: String, configIds: List<String>): LaunchIDs =
    callAs("launch.add", LaunchIDs.serializer(), jsonObjectOf("projectId" to projectId, "configIds" to configIds))

suspend fun OpCaller.launchRemove(projectId: String, configIds: List<String>): LaunchIDs =
    callAs("launch.remove", LaunchIDs.serializer(), jsonObjectOf("projectId" to projectId, "configIds" to configIds))

/** 设置启动项别名（空字符串清除） */
suspend fun OpCaller.launchSetAlias(projectId: String, configId: String, alias: String) =
    send("launch.setAlias", jsonObjectOf("projectId" to projectId, "configId" to configId, "alias" to alias))

/** 工程里能作为启动项运行的文件（脚本、程序；includeSources 时加上其他源码） */
suspend fun OpCaller.launchCandidates(projectId: String, includeSources: Boolean, limit: Int? = null): RunnableList =
    callAs(
        "launch.candidates", RunnableList.serializer(),
        jsonObjectOf("projectId" to projectId, "includeSources" to includeSources, "limit" to limit), OpTimeout.MINUTE,
    )

/** 把工程里的脚本 / 可执行文件加到启动（同一文件已有运行配置就复用）；path 相对工程根 */
suspend fun OpCaller.launchAddFile(projectId: String, path: String): LaunchAddResult =
    callAs("launch.addFile", LaunchAddResult.serializer(), jsonObjectOf("projectId" to projectId, "path" to path))

/** 把一段自定义命令加到启动；cwd 为空表示工程根目录；shell：auto | zsh | bash | sh | pwsh | cmd */
suspend fun OpCaller.launchAddCommand(projectId: String, name: String, command: String, cwd: String, shell: String? = null): LaunchAddResult =
    callAs(
        "launch.addCommand", LaunchAddResult.serializer(),
        jsonObjectOf("projectId" to projectId, "name" to name, "command" to command, "cwd" to cwd, "shell" to shell),
    )

/** 启动全部还没在跑的启动项（可限定工作区 / 工程 / 配置） */
suspend fun OpCaller.launchRun(workspaceId: String? = null, projectId: String? = null, configIds: List<String>? = null): LaunchResult =
    callAs(
        "launch.run", LaunchResult.serializer(),
        jsonObjectOf("workspaceId" to workspaceId, "projectId" to projectId, "configIds" to configIds), OpTimeout.MINUTES_2,
    )

suspend fun OpCaller.launchStop(workspaceId: String? = null, projectId: String? = null, configIds: List<String>? = null): LaunchResult =
    callAs("launch.stop", LaunchResult.serializer(), jsonObjectOf("workspaceId" to workspaceId, "projectId" to projectId, "configIds" to configIds))

// ---------------------------------------------------------------- 终端（输出流见 model.TerminalStreamMessage）

suspend fun OpCaller.terminalList(workspaceId: String? = null): List<TerminalInfo> =
    callList("terminal.list", TerminalInfo.serializer(), jsonObjectOf("workspaceId" to workspaceId))

/**
 * 新建终端：给 projectId 时起始目录是工程根目录（或相对它的 cwd），名字默认是工程名；
 * 不给工程时传 workspaceId + cwd（服务器上的绝对路径）。
 */
suspend fun OpCaller.terminalCreate(
    cols: Int,
    rows: Int,
    projectId: String? = null,
    workspaceId: String? = null,
    cwd: String? = null,
    name: String? = null,
): TerminalInfo = callAs(
    "terminal.create", TerminalInfo.serializer(),
    jsonObjectOf("projectId" to projectId, "workspaceId" to workspaceId, "cwd" to cwd, "name" to name, "cols" to cols, "rows" to rows),
)

/** 原样写入（回车用 \r，Ctrl-C 用 \u0003） */
suspend fun OpCaller.terminalInput(id: String, data: String) = send("terminal.input", jsonObjectOf("id" to id, "data" to data))

/** 最近输出的纯文本（去掉颜色、光标控制），maxBytes 默认 16384 */
suspend fun OpCaller.terminalRead(id: String, maxBytes: Int? = null): String =
    callAs("terminal.read", TerminalText.serializer(), jsonObjectOf("id" to id, "maxBytes" to maxBytes)).text

/**
 * 按字节偏移分页读终端的完整输出（老输出存在磁盘上，不只最后 1 MB）。
 * 从 offset（第一次传 0 即从最早能读到的 first 开始）读最多 limit 字节（默认 256 KB，最多 1 MB），下一页传回 result.end；
 * strip=true 去掉颜色与光标控制只留文本。
 */
suspend fun OpCaller.terminalHistory(id: String, offset: Long = 0, limit: Int? = null, strip: Boolean = false): TerminalHistory =
    callAs(
        "terminal.history", TerminalHistory.serializer(),
        jsonObjectOf("id" to id, "offset" to offset, "limit" to limit, "strip" to strip.takeIf { it }),
    )

suspend fun OpCaller.terminalResize(id: String, cols: Int, rows: Int) =
    send("terminal.resize", jsonObjectOf("id" to id, "cols" to cols, "rows" to rows))

suspend fun OpCaller.terminalRename(id: String, name: String) = send("terminal.rename", jsonObjectOf("id" to id, "name" to name))

/** 已退出的终端在原目录重新启动（输出接在后面） */
suspend fun OpCaller.terminalRestart(id: String): TerminalInfo =
    callAs("terminal.restart", TerminalInfo.serializer(), jsonObjectOf("id" to id))

/** 是否有命令在前台运行（关闭前确认用） */
suspend fun OpCaller.terminalBusy(id: String): TerminalBusy = callAs("terminal.busy", TerminalBusy.serializer(), jsonObjectOf("id" to id))

suspend fun OpCaller.terminalClose(id: String) = send("terminal.close", jsonObjectOf("id" to id))

// ---------------------------------------------------------------- 任务

suspend fun OpCaller.tasks(active: Boolean? = null): List<TaskInfo> =
    callList("task.list", TaskInfo.serializer(), jsonObjectOf("active" to active))

/** waitSeconds：最多等这么多秒直到任务结束 */
suspend fun OpCaller.getTask(id: String, waitSeconds: Int? = null): TaskInfo =
    callAs(
        "task.get", TaskInfo.serializer(), jsonObjectOf("taskId" to id, "wait" to waitSeconds),
        waitSeconds?.let { it * 1000L + OpTimeout.SECONDS_30 },
    )

suspend fun OpCaller.cancelTask(id: String) = send("task.cancel", jsonObjectOf("taskId" to id))

// ---------------------------------------------------------------- 工具链

suspend fun OpCaller.toolchainKinds(): List<ToolchainKind> = callList("toolchain.kinds", ToolchainKind.serializer())

suspend fun OpCaller.toolchains(kind: String? = null): List<Toolchain> =
    callList("toolchain.list", Toolchain.serializer(), jsonObjectOf("kind" to kind))

suspend fun OpCaller.availableToolchains(kind: String): List<AvailableVersion> =
    callList("toolchain.available", AvailableVersion.serializer(), jsonObjectOf("kind" to kind), OpTimeout.MINUTE)

suspend fun OpCaller.installToolchain(kind: String, version: String): TaskInfo =
    callAs("toolchain.install", TaskInfo.serializer(), jsonObjectOf("kind" to kind, "version" to version))

/** 登记服务器上自定义位置的工具链 */
suspend fun OpCaller.addToolchain(kind: String, path: String): Toolchain =
    callAs("toolchain.add", Toolchain.serializer(), jsonObjectOf("kind" to kind, "path" to path))

suspend fun OpCaller.removeToolchain(id: String) = send("toolchain.remove", jsonObjectOf("toolchainId" to id))

suspend fun OpCaller.refreshToolchains() = send("toolchain.refresh", timeoutMs = OpTimeout.MINUTES_2)

// ---------------------------------------------------------------- 插件与商店

suspend fun OpCaller.plugins(): List<PluginInfo> = callList("plugin.list", PluginInfo.serializer())

suspend fun OpCaller.setPluginEnabled(id: String, enabled: Boolean) =
    send(if (enabled) "plugin.enable" else "plugin.disable", jsonObjectOf("pluginId" to id))

/** 重新扫描插件目录与 manifest（开发时用） */
suspend fun OpCaller.reloadPlugins() = send("plugin.reload", timeoutMs = OpTimeout.MINUTE)

/** 从服务器上的 zip 或目录安装 */
suspend fun OpCaller.installPlugin(path: String): PluginInfo =
    callAs("plugin.install", PluginInfo.serializer(), jsonObjectOf("path" to path), OpTimeout.MINUTES_2)

suspend fun OpCaller.uninstallPlugin(id: String) = send("plugin.uninstall", jsonObjectOf("pluginId" to id))

suspend fun OpCaller.updatePluginSettings(id: String, values: Map<String, JsonElement>) =
    send("plugin.settings", jsonObjectOf("pluginId" to id, "values" to JsonObject(values)))

suspend fun OpCaller.storeList(refresh: Boolean = false): List<StoreEntry> =
    callList("store.list", StoreEntry.serializer(), jsonObjectOf("refresh" to refresh.takeIf { it }), OpTimeout.MINUTE)

suspend fun OpCaller.installFromStore(id: String): TaskInfo =
    callAs("store.install", TaskInfo.serializer(), jsonObjectOf("pluginId" to id))

// ---------------------------------------------------------------- 插件界面

/** 插件视图调用上下文（view.render / view.event 的公共字段）。 */
data class ViewContext(
    val plugin: String,
    val view: String,
    val projectId: String? = null,
    val moduleId: String? = null,
    val runId: String? = null,
    /** 首次 render 时带给插件的 state（如版本控制页把当前仓库 {"root": …} 传给插件子页） */
    val initialState: Map<String, JsonElement>? = null,
) {
    val input: JsonObject
        get() = jsonObjectOf("plugin" to plugin, "view" to view, "projectId" to projectId, "moduleId" to moduleId, "runId" to runId)
}

suspend fun OpCaller.renderView(ctx: ViewContext, state: Map<String, JsonElement>?): ViewRenderResult =
    callAs("view.render", ViewRenderResult.serializer(), ctx.input + jsonObjectOf("state" to state?.let { JsonObject(it) }))

/**
 * webview 视图：view.render 不调插件，直接返回 {"webviewUrl": "..."}（docs/api.md 第 6 节）。
 * 返回服务器给的原始地址（可能是相对路径，也可能带服务器自己的回环地址与令牌），
 * 用 util.WebviewURL.rebase 换成 App 本地网关的地址和令牌后再加载。
 */
suspend fun OpCaller.webviewUrl(ctx: ViewContext): String? =
    (callRaw("view.render", ctx.input) as? JsonObject)?.get("webviewUrl")?.stringValue?.takeIf { it.isNotEmpty() }

suspend fun OpCaller.viewEvent(
    ctx: ViewContext,
    event: String,
    values: Map<String, JsonElement>,
    state: Map<String, JsonElement>?,
): ViewEventResult = callAs(
    "view.event", ViewEventResult.serializer(),
    ctx.input + jsonObjectOf("event" to event, "values" to JsonObject(values), "state" to state?.let { JsonObject(it) }),
)
