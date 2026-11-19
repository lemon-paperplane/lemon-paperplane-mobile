@file:UseSerializers(InstantSerializer::class)

package cn.lemonit.paperplane.kit.model

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonElement

// 对应 lemon-paperplane-main-core/internal/model/model.go，字段名以 Go json tag 为准。
// 类型名与 Mac PaperplaneKit/Models/CoreModels.swift 一致：Task → TaskInfo，Settings → AppSettings，Event → DaemonEvent（见 Events.kt）。
// 解码用的字段都有默认值：缺失或 null 时取默认值，不会整条失败。

// ---------------------------------------------------------------- 工作区与工程

@Serializable
data class Workspace(
    val id: String = "",
    val name: String = "",
    val projectIds: List<String> = emptyList(),
    val createdAt: Instant? = null,
)

@Serializable
data class Project(
    val id: String = "",
    val name: String = "",
    val path: String = "",
    val workspaceId: String = "",
    val modules: List<Module> = emptyList(),
    val settings: ProjectSettings? = null,
    val vcsRoots: List<VCSRoot> = emptyList(),
    val importedAt: Instant? = null,
    val analyzedAt: Instant? = null,
    val defaultConfigId: String? = null,
    /** 默认配置是用户指定的（否则每次分析后自动重选） */
    val defaultByUser: Boolean = false,
    /** 加到工作区「启动」面板的运行配置 id（按加入顺序） */
    val launch: List<String> = emptyList(),
    /** 启动项别名（运行配置 id → 别名） */
    val launchAlias: LenientMap<String> = emptyMap(),
    /** 连接远程服务器时运行地址的打开方式（运行配置 id → 设置；没有记录的按两种都显示） */
    val remoteLinks: LenientMap<RemoteLinks> = emptyMap(),
    /** 最近一次结束的运行 */
    val lastRun: LastRun? = null,
) {
    /** 全部运行配置（按模块顺序） */
    val allRunConfigs: List<RunConfig> get() = modules.flatMap { it.runConfigs }

    fun module(id: String?): Module? = id?.let { i -> modules.firstOrNull { it.id == i } }

    fun runConfig(id: String?): RunConfig? = id?.let { i -> allRunConfigs.firstOrNull { it.id == i } }

    /** 工程里出现过的全部 facet id */
    val facetIds: Set<String> get() = modules.flatMap { m -> m.facets.map { it.id } }.toSet()

    /** 参与过本工程的插件 */
    val pluginIds: Set<String>
        get() {
            val s = LinkedHashSet<String>()
            for (m in modules) {
                for (f in m.facets) f.plugin?.let { s.add(it) }
                s.addAll(m.analysis.keys)
            }
            return s
        }

    /** 某个运行配置的远程打开方式（没有记录 = 两种都要；两种都关的脏数据也按两种都要） */
    fun remoteLinks(configId: String): RemoteLinks = RemoteLinks.resolve(remoteLinks[configId])
}

@Serializable
data class ProjectSettings(
    val env: LenientMap<String> = emptyMap(),
    val toolchains: LenientMap<ToolchainOverride> = emptyMap(),
    val notes: String? = null,
)

@Serializable
data class ToolchainOverride(
    val id: String? = null,
    val constraint: String? = null,
)

@Serializable
data class VCSRoot(
    val path: String = "",
    val relPath: String = "",
    val vcs: String = "",
    val plugin: String = "",
)

@Serializable
data class Module(
    val id: String = "",
    val name: String = "",
    val relDir: String = "",
    val facets: List<Facet> = emptyList(),
    val models: LenientMap<JsonElement> = emptyMap(),
    val analysis: LenientMap<ModuleAnalysis> = emptyMap(),
    val toolchains: LenientMap<ToolchainSelection> = emptyMap(),
    val requirements: List<ToolchainRequirement> = emptyList(),
    val runConfigs: List<RunConfig> = emptyList(),
    val dependencies: DependencyState? = null,
    val synced: Boolean = false,
    val needsSync: Boolean = false,
) {
    /** 各插件 info 合并（按插件 id 排序） */
    val info: List<InfoItem> get() = analysis.keys.sorted().flatMap { analysis[it]?.info.orEmpty() }

    /** 各插件静态问题合并 */
    val problems: List<Diagnosis> get() = analysis.keys.sorted().flatMap { analysis[it]?.problems.orEmpty() }

    /** 各插件动态下拉选项合并（FormField.optionsFrom 查这里） */
    val optionValues: Map<String, List<Option>>
        get() {
            val out = LinkedHashMap<String, MutableList<Option>>()
            for (k in analysis.keys.sorted()) {
                for ((key, opts) in analysis[k]?.optionValues.orEmpty()) out.getOrPut(key) { mutableListOf() }.addAll(opts)
            }
            return out
        }

    fun hasAnyFacet(ids: List<String>): Boolean = ids.isEmpty() || facets.any { it.id in ids }
}

@Serializable
data class ModuleAnalysis(
    val info: List<InfoItem> = emptyList(),
    val problems: List<Diagnosis> = emptyList(),
    val optionValues: LenientMap<List<Option>> = emptyMap(),
    val dependencies: DependencyState? = null,
    val at: Instant? = null,
)

@Serializable
data class ToolchainSelection(
    val kind: String = "",
    val toolchainId: String? = null,
    val version: String? = null,
    val constraint: String? = null,
    val preferred: String? = null,
    val missing: Boolean = false,
    val override: Boolean = false,
    val source: String? = null,
)

/** 持久化的运行配置（Go 里嵌入了 sdk.RunConfiguration，JSON 平铺）。 */
@Serializable
data class RunConfig(
    val id: String = "",
    val name: String = "",
    val type: String = "",
    val plugin: String? = null,
    val moduleId: String? = null,
    val options: LenientMap<JsonElement> = emptyMap(),
    val env: LenientMap<String> = emptyMap(),
    val default: Boolean = false,
    /** auto | user | idea | vscode */
    val source: String? = null,
    val priority: Int = 0,
    val edited: Boolean = false,
    val stale: Boolean = false,
)

// ---------------------------------------------------------------- 卡片摘要

@Serializable
data class ProjectSummary(
    val id: String = "",
    val name: String = "",
    val path: String = "",
    val workspaceId: String = "",
    val facets: List<FacetBadge> = emptyList(),
    val info: List<InfoItem> = emptyList(),
    /** idle | starting | running | failed | stopping | partial */
    val status: String = "",
    val runs: List<RunBrief> = emptyList(),
    val configs: List<ConfigBrief> = emptyList(),
    val defaultConfigId: String? = null,
    /** 加到工作区「启动」面板的运行配置 id（按加入顺序，只含仍存在的） */
    val launch: List<String> = emptyList(),
    /** 启动项别名（运行配置 id → 别名），只在「启动」里显示 */
    val launchAlias: LenientMap<String> = emptyMap(),
    val vcs: VCSBrief? = null,
    val problems: Int = 0,
    /** 最严重的：error | warning | info */
    val problemSeverity: String? = null,
    val missingToolchains: List<String> = emptyList(),
    /** ok | missing | outdated | unknown（各模块中最差的） */
    val dependencies: String? = null,
    /** 正在进行的任务标题（导入、同步、安装…） */
    val busy: String? = null,
    /** 最近一次结束的运行（卡片「未运行 · 上次运行 3 小时前」） */
    val lastRun: LastRun? = null,
    /** 连接远程服务器时运行地址的打开方式（运行配置 id → 设置） */
    val remoteLinks: LenientMap<RemoteLinks> = emptyMap(),
) {
    /** 是否有运行中（含启动中、准备中、停止中）的运行 */
    val hasActiveRun: Boolean get() = runs.any { RunState.isActive(it.state) }

    /** 某个运行配置的远程打开方式（没有记录 = 两种都要；两种都关的脏数据也按两种都要） */
    fun remoteLinks(configId: String): RemoteLinks = RemoteLinks.resolve(remoteLinks[configId])
}

/** 工程最近一次结束的运行（内核随工程保存，内核重启后也还在）。 */
@Serializable
data class LastRun(
    val configId: String = "",
    val name: String = "",
    val endedAt: Instant? = null,
    val exitCode: Int = 0,
    /** 异常退出或启动失败（用户停止不算） */
    val failed: Boolean = false,
)

@Serializable
data class FacetBadge(
    val id: String = "",
    val name: String = "",
    val icon: String? = null,
    val category: String? = null,
    val plugin: String = "",
)

@Serializable
data class ConfigBrief(
    val id: String = "",
    val name: String = "",
    val type: String = "",
    val icon: String? = null,
    val moduleId: String = "",
    val moduleName: String = "",
)

@Serializable
data class RunBrief(
    val id: String = "",
    val projectId: String? = null,
    val configId: String = "",
    val name: String = "",
    val state: String = "",
    val urls: List<String> = emptyList(),
    val cpu: Double = 0.0,
    val rssMB: Double = 0.0,
    /** 本次运行的内存峰值 */
    val peakRSSMB: Double = 0.0,
    /** 进程树里的进程数 */
    val procs: Int = 0,
    /** 采样时间（Unix 毫秒），据此追加趋势点 */
    val t: Long = 0,
)

@Serializable
data class VCSBrief(
    val branch: String = "",
    val ahead: Int = 0,
    val behind: Int = 0,
    val changes: Int = 0,
    val conflicts: Int = 0,
    val operation: String? = null,
    /** 工程内仓库数 */
    val roots: Int = 0,
    val at: Instant? = null,
)

// ---------------------------------------------------------------- 运行与任务

/** Run.state / RunBrief.state 的取值。 */
object RunState {
    const val PREPARING = "preparing"
    const val STARTING = "starting"
    const val RUNNING = "running"
    const val STOPPING = "stopping"
    const val EXITED = "exited"
    const val FAILED = "failed"

    /** 还没结束 */
    fun isActive(s: String?): Boolean = s == PREPARING || s == STARTING || s == RUNNING || s == STOPPING
}

@Serializable
data class Run(
    val id: String = "",
    val projectId: String = "",
    val moduleId: String = "",
    val configId: String = "",
    val name: String = "",
    val plugin: String = "",
    val state: String = "",
    val pid: Int? = null,
    val command: String? = null,
    val cwd: String? = null,
    val startedAt: Instant? = null,
    val readyAt: Instant? = null,
    val endedAt: Instant? = null,
    val exitCode: Int? = null,
    val stoppedByUser: Boolean = false,
    /** 准备阶段失败原因 */
    val error: String? = null,
    val urls: List<String> = emptyList(),
    val ports: List<Int> = emptyList(),
    val cpu: Double = 0.0,
    val rssMB: Double = 0.0,
    /** 本次运行的内存峰值 */
    val peakRSSMB: Double = 0.0,
    /** 进程树里的进程数 */
    val procs: Int = 0,
    val diagnoses: List<Diagnosis> = emptyList(),
    val logFile: String? = null,
    /** 已产生的日志行数 */
    val lines: Long = 0,
) {
    val isActive: Boolean get() = RunState.isActive(state)
}

/** TaskInfo.state 的取值。 */
object TaskState {
    const val RUNNING = "running"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

/** model.Task */
@Serializable
data class TaskInfo(
    val id: String = "",
    /** import | reimport | sync | install | toolchain | action | vcs | plugin */
    val kind: String = "",
    val title: String = "",
    val projectId: String? = null,
    val moduleId: String? = null,
    val state: String = "",
    val message: String? = null,
    /** <0 不确定 */
    val percent: Double = 0.0,
    val error: String? = null,
    val startedAt: Instant? = null,
    val endedAt: Instant? = null,
    val result: JsonElement? = null,
    val diagnoses: List<Diagnosis> = emptyList(),
) {
    val isRunning: Boolean get() = state == TaskState.RUNNING
}

/** 控制台的一行。text 保留 ANSI 转义（ANSIParser 着色）。 */
@Serializable
data class LogLine(
    val seq: Long = 0,
    /** Unix 毫秒 */
    val t: Long = 0,
    /** stdout | stderr | system */
    val s: String = "",
    val text: String = "",
)

/** LogLine.s 的取值。 */
object LogStream {
    const val STDOUT = "stdout"
    const val STDERR = "stderr"
    const val SYSTEM = "system"
}

// ---------------------------------------------------------------- 工具链与插件

@Serializable
data class ToolchainKind(
    val kind: String = "",
    val name: String = "",
    val icon: String? = null,
    val installable: Boolean = false,
    val plugin: String = "",
) {
    val id: String get() = kind
}

@Serializable
data class PluginInfo(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val vendor: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val category: String? = null,
    val homepage: String? = null,
    /** bundled | user | dev */
    val source: String = "",
    val dir: String = "",
    val enabled: Boolean = false,
    val running: Boolean = false,
    val error: String? = null,
    val dependencies: List<Dependency> = emptyList(),
    val bundledVersion: String? = null,
    val contributes: ContribSummary? = null,
)

@Serializable
data class ContribSummary(
    val facets: List<String> = emptyList(),
    val toolchains: List<String> = emptyList(),
    val runConfigTypes: List<String> = emptyList(),
    val diagnostics: Int = 0,
    val views: List<String> = emptyList(),
    val actions: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val vcs: String? = null,
)

@Serializable
data class StoreEntry(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val vendor: String? = null,
    val description: String? = null,
    /** 图标 URL */
    val icon: String? = null,
    val engines: Engines? = null,
    val platforms: LenientMap<StorePlatform> = emptyMap(),
    /** 在线商店给的全部可用版本（新到旧）；内核已按兼容性挑好 version/engines/platforms */
    val versions: List<StoreVersion> = emptyList(),
    /** 已安装版本 */
    val installed: String? = null,
    val compatible: Boolean = false,
)

@Serializable
data class StoreVersion(
    val version: String = "",
    val engines: Engines? = null,
    val platforms: LenientMap<StorePlatform> = emptyMap(),
)

@Serializable
data class StorePlatform(
    val url: String = "",
    val sha256: String = "",
    val size: Long? = null,
)

// ---------------------------------------------------------------- 界面贡献

@Serializable
data class Contributions(
    val facets: List<FacetBadge> = emptyList(),
    val toolchainKinds: List<ToolchainKind> = emptyList(),
    val runConfigTypes: List<RunConfigTypeInfo> = emptyList(),
    val views: List<ViewInfo> = emptyList(),
    val actions: List<ActionInfo> = emptyList(),
    val settings: List<PluginSettingsInfo> = emptyList(),
    val vcs: List<VCSInfo> = emptyList(),
    /** 编辑器：语言着色、快捷键方案、配色主题（docs/editor.md 第 4 节） */
    val languages: List<LanguageInfo> = emptyList(),
    val keymaps: List<KeymapInfo> = emptyList(),
    val editorThemes: List<EditorThemeInfo> = emptyList(),
    /** 服务器所在平台默认的快捷键方案 id（手机上用 EditorSettings.PLATFORM_DEFAULT_KEYMAP） */
    val defaultKeymap: String? = null,
) {
    /** 挂在某个位置的视图（按 order、title 排序） */
    fun views(at: String): List<ViewInfo> =
        views.filter { it.location == at }.sortedWith(compareBy<ViewInfo> { it.order }.thenBy { it.title })

    /** 挂在某个位置的动作；locations 为空的默认挂 project.menu */
    fun actions(at: String): List<ActionInfo> =
        actions.filter { a -> if (a.locations.isEmpty()) at == ActionLocation.PROJECT_MENU else at in a.locations }

    fun runConfigType(plugin: String?, type: String): RunConfigTypeInfo? =
        runConfigTypes.firstOrNull { it.id == type && (plugin == null || it.plugin == plugin) }
            ?: runConfigTypes.firstOrNull { it.id == type }

    fun facet(id: String): FacetBadge? = facets.firstOrNull { it.id == id }
}

/** RunConfigTypeInfo：sdk.RunConfigTypeDef + plugin（平铺） */
@Serializable
data class RunConfigTypeInfo(
    val id: String = "",
    val name: String = "",
    val icon: String? = null,
    val facets: List<String> = emptyList(),
    val options: List<FormField> = emptyList(),
    val plugin: String = "",
)

/** ViewInfo：sdk.ViewDef + plugin */
@Serializable
data class ViewInfo(
    val id: String = "",
    val title: String = "",
    val icon: String? = null,
    val location: String = "",
    val facets: List<String> = emptyList(),
    /** declarative | webview */
    val kind: String? = null,
    val entry: String? = null,
    val order: Int = 0,
    val runState: String? = null,
    val plugin: String = "",
) {
    /** 全局唯一键 */
    val key: String get() = "$plugin/$id"
    val isWebview: Boolean get() = kind == "webview"
}

/** ActionInfo：sdk.ActionDef + plugin */
@Serializable
data class ActionInfo(
    val id: String = "",
    val title: String = "",
    val icon: String? = null,
    val facets: List<String> = emptyList(),
    val confirm: String? = null,
    val group: String? = null,
    val locations: List<String> = emptyList(),
    val form: List<FormField> = emptyList(),
    val plugin: String = "",
) {
    val key: String get() = "$plugin/$id"
}

@Serializable
data class PluginSettingsInfo(
    val plugin: String = "",
    val title: String = "",
    val fields: List<FormField> = emptyList(),
    val values: LenientMap<JsonElement> = emptyMap(),
)

@Serializable
data class VCSInfo(
    val id: String = "",
    val name: String = "",
    val rootMarker: String = "",
    val plugin: String = "",
)

// ---------------------------------------------------------------- 设置

/** model.Settings */
@Serializable
data class AppSettings(
    /** system | zh-Hans | en */
    val locale: String = "",
    /** system | light | dark */
    val theme: String = "",
    /** auto | china | global */
    val mirrorRegion: String = "",
    val autoInstallToolchains: Boolean = false,
    val autoInstallDependencies: Boolean = false,
    /** 导入后自动执行构建工具同步（如 Gradle） */
    val autoSyncOnImport: Boolean = false,
    val pluginIdleSeconds: Int = 0,
    val logLinesPerRun: Int = 0,
    val logRetentionRuns: Int = 0,
    val apiPort: Int = 0,
    val storeIndexUrl: String? = null,
    val gitAutoFetchMinutes: Int = 0,
    val terminal: String? = null,
    val pluginSettings: LenientMap<Map<String, JsonElement>> = emptyMap(),
    val disabledPlugins: List<String> = emptyList(),
    /** settings.editor（缺失时用 EditorSettings() 的默认值） */
    val editor: EditorSettings? = null,
)

// ---------------------------------------------------------------- 对话框与系统信息

/** 需要界面回答的对话框（Go 里嵌入了 sdk.PromptParams，JSON 平铺）。 */
@Serializable
data class Prompt(
    val id: String = "",
    val title: String = "",
    val message: String? = null,
    val fields: List<FormField> = emptyList(),
    val okText: String? = null,
    val danger: Boolean = false,
    val plugin: String? = null,
)

@Serializable
data class SystemInfo(
    val version: String = "",
    val os: String = "",
    val arch: String = "",
    val pid: Int = 0,
    val startedAt: Instant? = null,
    val dataDir: String = "",
    val apiBase: String = "",
    val mcpUrl: String = "",
    val executable: String = "",
    val memoryMB: Double = 0.0,
    val pluginProcs: Int = 0,
    val pluginMemoryMB: Double = 0.0,
    val locale: String = "",
    val mirrorRegion: String = "",
    /** 可用插件数；0 表示安装不完整，任何工程都识别不了 */
    val plugins: Int = 0,
    val pluginErrors: List<String>? = null,
    val pluginDirs: List<String>? = null,
    /** 主机名（切换服务器时显示） */
    val hostname: String = "",
    /** 用户主目录（选择服务器上的文件夹从这里开始；路径缩写 ~ 用它） */
    val home: String = "",
    /** 服务器的路径分隔符：拼服务器路径时用它（ServerPath(sep)） */
    val pathSeparator: String = "",
    /** 远程协议版本 */
    val apiVersion: Int = 0,
)

// ---------------------------------------------------------------- 各操作的专用输出

/** project.scan 的一项 */
@Serializable
data class ScanEntry(
    val path: String = "",
    val name: String = "",
    val facets: List<FacetBadge> = emptyList(),
    val imported: Boolean = false,
    val projectId: String? = null,
) {
    val id: String get() = path
}

/** runconfig.types 的一项 */
@Serializable
data class RunConfigTypeChoice(
    val type: RunConfigTypeInfo = RunConfigTypeInfo(),
    val optionValues: LenientMap<List<Option>> = emptyMap(),
)

/**
 * run.logs 输出。
 * - 普通模式（tail/since/grep）：lines 为最后 tail 行，truncated 表示前面还有；total 是过滤后的总行数。
 * - 分页模式（from/limit）：lines 从 from 开始；firstSeq 是最早能读到的一行（有日志文件时为 1），lastSeq 是最新一行。
 */
@Serializable
data class LogsResult(
    val lines: List<LogLine> = emptyList(),
    val lastSeq: Long = 0,
    val firstSeq: Long = 0,
    val total: Long = 0,
    val truncated: Boolean = false,
) {
    /** 分页模式下，下一页的 from（已读到末尾时为 null） */
    val nextFrom: Long? get() = lines.lastOrNull()?.seq?.takeIf { it < lastSeq }?.plus(1)
}

/** fix.apply 输出 */
@Serializable
data class FixResult(
    val message: String? = null,
    val openUrl: String? = null,
    val rerun: Run? = null,
    /** 修复动作生成的任务（如安装依赖、下载工具链） */
    val task: TaskInfo? = null,
)

/** mirrors.status / mirrors.probe 输出 */
@Serializable
data class MirrorStatus(
    val configured: String = "",
    val effective: String = "",
    val probes: List<MirrorProbe> = emptyList(),
    val at: Instant? = null,
)

@Serializable
data class MirrorProbe(
    val name: String = "",
    val url: String = "",
    val ms: Double? = null,
    val ok: Boolean = false,
) {
    val id: String get() = "$name|$url"
}

/** mcp.config 输出（远程禁止调用，模型留着读本地夹具） */
@Serializable
data class MCPConfig(
    val url: String = "",
    val token: String = "",
    val stdioCommand: List<String> = emptyList(),
    val claudeCode: String = "",
    val claudeDesktop: JsonElement? = null,
)

@Serializable
data class StopAllResult(val stopped: Int = 0)

/** launch.candidates 的一项：工程里能作为启动项运行的文件 */
@Serializable
data class RunnableFile(
    val path: String = "",
    /** script | binary | source */
    val kind: String = "",
    val exec: Boolean = false,
    val size: Long = 0,
    /** 已经在启动里 */
    val launch: Boolean = false,
) {
    val id: String get() = path
    val name: String get() = path.substringAfterLast('/')

    /** 所在目录（相对工程根，根目录为空） */
    val directory: String get() = if ('/' in path) path.substringBeforeLast('/') else ""
}

@Serializable
data class RunnableList(
    val files: List<RunnableFile> = emptyList(),
    val truncated: Boolean = false,
)

/** launch.addFile / launch.addCommand 的结果 */
@Serializable
data class LaunchAddResult(
    val configId: String = "",
    val moduleId: String = "",
    val name: String = "",
    val created: Boolean = false,
    val launch: List<String> = emptyList(),
)

/** launch.add / launch.remove 的结果：该工程现在的启动项 */
@Serializable
data class LaunchIDs(val launch: List<String> = emptyList())

/** launch.run / launch.stop 的结果 */
@Serializable
data class LaunchResult(
    val started: List<Run> = emptyList(),
    val stopped: Int = 0,
    val skipped: Int = 0,
    val errors: List<LaunchRunError> = emptyList(),
)

@Serializable
data class LaunchRunError(
    val projectId: String = "",
    val configId: String = "",
    val error: String = "",
)

/** launch.list 的一组：一个工程的启动项 */
@Serializable
data class LaunchGroup(
    val projectId: String = "",
    val projectName: String = "",
    val path: String = "",
    val items: List<LaunchItem> = emptyList(),
)

@Serializable
data class LaunchItem(
    val configId: String = "",
    val name: String = "",
    /** 用户起的别名（显示时优先） */
    val alias: String? = null,
    val type: String = "",
    val plugin: String? = null,
    val moduleId: String = "",
    val moduleName: String? = null,
    /** 最近一次运行（进行中优先） */
    val run: RunBrief? = null,
) {
    val displayName: String get() = alias?.takeIf { it.isNotEmpty() } ?: name
}

@Serializable
data class PathResult(val path: String = "")

/** 交互终端（内核里伪终端中的登录 shell；terminal.* 接口与 terminals.changed 事件） */
@Serializable
data class TerminalInfo(
    val id: String = "",
    val workspaceId: String = "",
    val projectId: String = "",
    val name: String = "",
    /** 起始目录 */
    val cwd: String = "",
    val shell: String = "",
    val pid: Int = 0,
    val cols: Int = 0,
    val rows: Int = 0,
    val startedAt: Instant? = null,
    val exited: Boolean = false,
    val exitCode: Int = 0,
    /** 累计输出字节数（流的末尾偏移） */
    val output: Long = 0,
)

@Serializable
data class TerminalBusy(
    val busy: Boolean = false,
    val process: String = "",
)

/** terminal.read 输出：最近输出的纯文本 */
@Serializable
data class TerminalText(val text: String = "")

/**
 * terminal.history 输出：按字节偏移读的一段完整输出（磁盘 + 内存）。
 * 从 first 开始读，下一页传回 end；end == total 表示读到最新。data 是 UTF-8 文本（strip 时去掉了颜色与光标控制）。
 */
@Serializable
data class TerminalHistory(
    /** 这段输出的起始偏移 */
    val start: Long = 0,
    /** 这段输出的结束偏移（下次从这里接着读） */
    val end: Long = 0,
    /** 最早还能读到的偏移 */
    val first: Long = 0,
    /** 累计输出字节数 */
    val total: Long = 0,
    val data: String = "",
) {
    /** 是否已读到最新 */
    val atEnd: Boolean get() = end >= total
}

@Serializable
data class PortKillResult(val pids: List<Int> = emptyList())
