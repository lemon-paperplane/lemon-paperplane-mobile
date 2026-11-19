package cn.lemonit.paperplane.kit.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// 对应插件 SDK（lemon-paperplane-main-plugin-sdk）的 protocol.go / manifest.go / view.go 中界面会用到的类型。
// 类型名与 Mac PaperplaneKit/Models/SDKModels.swift 一致（TableColumn/TableRow → ViewTableColumn/ViewTableRow）。

@Serializable
data class Facet(
    val id: String = "",
    val plugin: String? = null,
    val data: Map<String, JsonElement>? = null,
)

@Serializable
data class Toolchain(
    val id: String = "",
    val kind: String = "",
    val version: String = "",
    val vendor: String? = null,
    val arch: String? = null,
    val path: String = "",
    val executable: String? = null,
    val env: LenientMap<String> = emptyMap(),
    val pathPrepend: List<String> = emptyList(),
    val source: String? = null,
    val managed: Boolean = false,
    val label: String? = null,
) {
    /** 界面显示名：优先 label，否则「厂商 版本」 */
    val displayName: String
        get() = when {
            !label.isNullOrEmpty() -> label
            !vendor.isNullOrEmpty() -> "$vendor $version"
            else -> version
        }
}

@Serializable
data class ToolchainRequirement(
    val kind: String = "",
    val constraint: String? = null,
    val preferred: String? = null,
    val source: String? = null,
    val purpose: String? = null,
    val optional: Boolean = false,
)

@Serializable
data class DependencyState(
    /** ok | missing | outdated | unknown | none */
    val state: String = "",
    val manager: String? = null,
    val message: String? = null,
)

@Serializable
data class InfoItem(
    val label: String = "",
    val value: String = "",
    val icon: String? = null,
)

@Serializable
data class Option(
    val value: String = "",
    val label: String? = null,
) {
    val displayLabel: String get() = label?.takeIf { it.isNotEmpty() } ?: value
}

@Serializable
data class Fix(
    val id: String = "",
    val title: String = "",
    val kind: String = "",
    val params: LenientMap<String> = emptyMap(),
    val recommended: Boolean = false,
    val confirm: String? = null,
    val plugin: String? = null,
)

/** Fix.kind 的取值。 */
object FixKind {
    const val SET_TOOLCHAIN = "setToolchain"
    const val SET_ENV = "setEnv"
    const val APPEND_ENV = "appendEnv"
    const val UNSET_ENV = "unsetEnv"
    const val SET_RUN_OPTION = "setRunOption"
    const val INSTALL_DEPENDENCIES = "installDependencies"
    const val KILL_PORT = "killPort"
    const val REIMPORT = "reimport"
    const val RESTART = "restart"
    const val OPEN_URL = "openUrl"
    const val CUSTOM = "custom"
}

@Serializable
data class Diagnosis(
    val id: String? = null,
    val ruleId: String? = null,
    val plugin: String? = null,
    /** error | warning | info（见 Severity） */
    val severity: String = "",
    val title: String = "",
    val message: String? = null,
    val detail: String? = null,
    val fixes: List<Fix> = emptyList(),
    val line: String? = null,
) {
    /** 用于列表 diff 的稳定键 */
    val stableKey: String get() = id ?: "${ruleId.orEmpty()}|$title|${line.orEmpty()}"
}

object Severity {
    const val ERROR = "error"
    const val WARNING = "warning"
    const val INFO = "info"

    /** 严重度排序：error > warning > info > 其他 */
    fun rank(s: String?): Int = when (s) {
        ERROR -> 3
        WARNING -> 2
        INFO -> 1
        else -> 0
    }
}

/**
 * 声明式表单字段。type：string | text | boolean | number | select | multiselect | keyvalue | path | list | code
 * 另外界面识别 password / secret 为密码框。
 */
@Serializable
data class FormField(
    val key: String = "",
    val title: String = "",
    val type: String = "",
    val default: JsonElement? = null,
    val options: List<Option> = emptyList(),
    val optionsFrom: String? = null,
    /** type=code 时代码输入框的语言（如 shell） */
    val language: String? = null,
    val placeholder: String? = null,
    val info: String? = null,
    val required: Boolean = false,
    val advanced: Boolean = false,
    /** 选项随时会变（如已连接设备）：表单打开时及打开期间定期经 runconfig.options 实时取 */
    val live: Boolean = false,
) {
    val id: String get() = key

    /** 是否按密码框显示 */
    val isSecret: Boolean
        get() {
            if (type == "password" || type == "secret") return true
            val k = key.lowercase()
            return type == "string" && ("password" in k || "passphrase" in k || "secret" in k || k == "token")
        }
}

@Serializable
data class Engines(val paperplane: String = "")

@Serializable
data class Dependency(
    val id: String = "",
    val version: String? = null,
    val optional: Boolean = false,
)

@Serializable
data class ActionDef(
    val id: String = "",
    val title: String = "",
    val icon: String? = null,
    val facets: List<String> = emptyList(),
    val confirm: String? = null,
    val group: String? = null,
    val locations: List<String> = emptyList(),
    val form: List<FormField> = emptyList(),
)

/** 界面挂载位置（ViewInfo.location）。 */
object ViewLocation {
    const val PROJECT_TAB = "project.tab"
    const val PROJECT_SECTION = "project.section"
    const val SIDEBAR = "sidebar"
    const val RUN_PANEL = "run.panel"
    const val VCS_TAB = "vcs.tab"
    const val SETTINGS = "settings"
}

/** 动作挂载位置（ActionInfo.locations）。 */
object ActionLocation {
    const val PROJECT_TOOLBAR = "project.toolbar"
    const val PROJECT_MENU = "project.menu"
    const val CARD_MENU = "card.menu"
    const val RUN_TOOLBAR = "run.toolbar"
    const val VCS_TOOLBAR = "vcs.toolbar"
    const val VCS_FILE_MENU = "vcs.fileMenu"
    const val VCS_COMMIT_MENU = "vcs.commitMenu"
    const val GLOBAL_MENU = "global.menu"
}

@Serializable
data class NotifyParams(
    /** info | warning | error */
    val level: String = "",
    val title: String = "",
    val message: String? = null,
)

@Serializable
data class AvailableVersion(
    val version: String = "",
    val label: String? = null,
    val vendor: String? = null,
    val lts: Boolean = false,
    val recommended: Boolean = false,
) {
    val id: String get() = "${vendor.orEmpty()}|$version"
}

// ---------------------------------------------------------------- 插件界面描述语言（view.go）

@Serializable
data class ViewNode(
    val type: String = "",
    val id: String? = null,
    val title: String? = null,
    val text: String? = null,
    val icon: String? = null,
    val tone: String? = null,
    val info: String? = null,
    val mono: Boolean = false,
    val style: String? = null,
    val action: String? = null,
    val href: String? = null,
    val confirm: String? = null,
    val disabled: Boolean = false,
    val value: Double = 0.0,
    val items: List<KV> = emptyList(),
    val columns: List<ViewTableColumn> = emptyList(),
    val rows: List<ViewTableRow> = emptyList(),
    val selectable: Boolean = false,
    val fields: List<FormField> = emptyList(),
    val values: Map<String, JsonElement>? = null,
    val submitText: String? = null,
    val console: String? = null,
    val collapsed: Boolean = false,
    val children: List<ViewNode> = emptyList(),
    val data: Map<String, String>? = null,
)

@Serializable
data class KV(
    val key: String = "",
    val value: String = "",
    val mono: Boolean = false,
    val href: String? = null,
    val tone: String? = null,
)

@Serializable
data class ViewTableColumn(
    val key: String = "",
    val title: String = "",
    val width: Int = 0,
    val align: String? = null,
    val mono: Boolean = false,
)

@Serializable
data class ViewTableRow(
    val id: String = "",
    val cells: LenientMap<String> = emptyMap(),
    val icon: String? = null,
    val tone: String? = null,
    val action: String? = null,
    val actions: List<ViewNode> = emptyList(),
    val children: List<ViewTableRow> = emptyList(),
    val expanded: Boolean = false,
)

@Serializable
data class ViewRenderResult(
    val root: ViewNode = ViewNode(),
    val state: Map<String, JsonElement>? = null,
    val refreshMs: Int = 0,
)

@Serializable
data class ViewEventResult(
    val refresh: Boolean = false,
    val state: Map<String, JsonElement>? = null,
    val message: String? = null,
    val openUrl: String? = null,
    val reimport: Boolean = false,
)
