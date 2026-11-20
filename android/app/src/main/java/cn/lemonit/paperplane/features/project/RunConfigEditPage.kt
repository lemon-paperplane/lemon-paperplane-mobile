package cn.lemonit.paperplane.features.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.Route
import cn.lemonit.paperplane.features.forms.FieldLabel
import cn.lemonit.paperplane.features.forms.FormFields
import cn.lemonit.paperplane.features.forms.FormValues
import cn.lemonit.paperplane.features.forms.KeyValueEditor
import cn.lemonit.paperplane.kit.createRunConfig
import cn.lemonit.paperplane.kit.liveOptions
import cn.lemonit.paperplane.kit.model.FormField
import cn.lemonit.paperplane.kit.model.Option
import cn.lemonit.paperplane.kit.model.Project
import cn.lemonit.paperplane.kit.model.RemoteLinks
import cn.lemonit.paperplane.kit.model.RunConfig
import cn.lemonit.paperplane.kit.model.RunConfigTypeChoice
import cn.lemonit.paperplane.kit.runConfigTypes
import cn.lemonit.paperplane.kit.setRemoteLinks
import cn.lemonit.paperplane.kit.updateRunConfig
import cn.lemonit.paperplane.kit.util.IconSource
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperCard
import cn.lemonit.paperplane.ui.components.PaperCheckbox
import cn.lemonit.paperplane.ui.components.PaperMoreButton
import cn.lemonit.paperplane.ui.components.PaperSectionHeader
import cn.lemonit.paperplane.ui.components.PaperSelect
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.components.PaperTextField
import cn.lemonit.paperplane.ui.components.SelectOption
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.shell.PageScaffold
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/** 运行配置编辑的三种方式 */
enum class RunConfigEditMode { CREATE, EDIT, DUPLICATE }

/** 新建 / 编辑 / 复制运行配置（全屏页；对照 Mac RunConfigDialog） */
data class RunConfigEditRoute(val project: Project, val mode: RunConfigEditMode, val config: RunConfig? = null) : Route {
    override val key get() = "runconfig:${project.id}:${mode.name}:${config?.id.orEmpty()}"

    @Composable
    override fun Content() {
        val state = remember(key) { RunConfigEditState(project, mode, config) }
        RunConfigEditPage(state)
    }

    companion object {
        fun openNew(project: Project) = AppModel.nav.push(RunConfigEditRoute(project, RunConfigEditMode.CREATE))
        fun openEdit(project: Project, c: RunConfig) = AppModel.nav.push(RunConfigEditRoute(project, RunConfigEditMode.EDIT, c))
        fun openDuplicate(project: Project, c: RunConfig) = AppModel.nav.push(RunConfigEditRoute(project, RunConfigEditMode.DUPLICATE, c))
    }
}

/** 编辑页的状态（截图测试可直接构造） */
@Stable
class RunConfigEditState(val project: Project, val mode: RunConfigEditMode, val config: RunConfig?) {
    val isEdit get() = mode == RunConfigEditMode.EDIT
    val isCreate get() = mode == RunConfigEditMode.CREATE

    var moduleId by mutableStateOf(config?.moduleId ?: project.modules.firstOrNull()?.id.orEmpty())
    var choices by mutableStateOf<List<RunConfigTypeChoice>>(emptyList())
    var typeId by mutableStateOf(config?.type.orEmpty())
    var name by mutableStateOf(
        when (mode) {
            RunConfigEditMode.CREATE -> ""
            RunConfigEditMode.EDIT -> config?.name.orEmpty()
            RunConfigEditMode.DUPLICATE -> config?.name.orEmpty() + " " + tr("runconfig.copySuffix")
        },
    )
    var values by mutableStateOf<Map<String, JsonElement>>(config?.options.orEmpty())
    var env by mutableStateOf(config?.env.orEmpty().toList().sortedBy { it.first })
    var loading by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var showErrors by mutableStateOf(false)
    /** 实时下拉选项（如已连接设备），覆盖分析时取的快照 */
    var liveOptions by mutableStateOf<Map<String, List<Option>>>(emptyMap())
    val initialLinks: RemoteLinks = if (config != null && !isCreate) project.remoteLinks(config.id) else RemoteLinks.BOTH
    var links by mutableStateOf(initialLinks)

    /** 当前类型的表单字段 */
    val fields: List<FormField>
        get() = choices.firstOrNull { it.type.id == typeId }?.type?.options
            ?: AppModel.data.contributions.runConfigType(config?.plugin, typeId)?.options
            ?: emptyList()

    val optionValues: Map<String, List<Option>>
        get() {
            val o = LinkedHashMap(project.module(moduleId)?.optionValues.orEmpty())
            choices.firstOrNull { it.type.id == typeId }?.optionValues?.let { o.putAll(it) }
            o.putAll(liveOptions)
            return o
        }

    /** 本类配置里标了 live 的 optionsFrom 键 */
    val liveKeys: List<String> get() = fields.filter { it.live }.mapNotNull { it.optionsFrom?.takeIf { k -> k.isNotEmpty() } }.distinct().sorted()

    suspend fun loadTypes() {
        if (moduleId.isEmpty()) return
        loading = true
        try {
            choices = AppModel.api().runConfigTypes(project.id, moduleId)
            if (isCreate && choices.none { it.type.id == typeId }) choices.firstOrNull()?.let { selectType(it.type.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isCreate) error = e.message
        } finally {
            loading = false
        }
    }

    /** 新建时换类型：表单重置为该类型的默认值，名字没改过就跟着类型名 */
    fun selectType(t: String) {
        val oldTypeName = choices.firstOrNull { it.type.id == typeId }?.type?.name
        typeId = t
        if (isCreate) {
            values = FormValues.initial(fields, emptyMap())
            if (name.isEmpty() || name == oldTypeName) name = choices.firstOrNull { it.type.id == t }?.type?.name ?: name
        }
    }

    /** 保存；成功返回 true */
    suspend fun submit(): Boolean {
        val n = name.trim()
        if (n.isEmpty() || FormValues.missingRequired(fields, values).isNotEmpty()) {
            showErrors = true
            return false
        }
        busy = true
        error = null
        try {
            val api = AppModel.api()
            val envMap = env.filter { it.first.isNotEmpty() }.toMap()
            val opts = FormValues.normalized(fields, values)
            val configId = if (isEdit && config != null) {
                api.updateRunConfig(project.id, config.id, name = n, options = opts, env = envMap)
                config.id
            } else {
                api.createRunConfig(project.id, moduleId, typeId, n, opts, envMap).id
            }
            // 新建的配置没有记录（= 两种都要）；只在和原来不同时写
            if (links != initialLinks && configId.isNotEmpty()) api.setRemoteLinks(project.id, configId, links.forward, links.direct)
            AppModel.bus.projectChanged.tryEmit(project.id)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: tr("common.failed")
            return false
        } finally {
            busy = false
        }
    }
}

/** 关掉这个编辑页（保存、删除后） */
private fun close(s: RunConfigEditState) {
    val key = RunConfigEditRoute(s.project, s.mode, s.config).key
    AppModel.nav.removeWhere { it.key == key }
}

@Composable
fun RunConfigEditPage(state: RunConfigEditState) {
    val s = state
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val scope = rememberCoroutineScope()
    LaunchedEffect(s.moduleId) { s.loadTypes() }
    // 打开期间每 4 秒刷新 live 选项（插拔设备后下拉框跟着变）；切到后台暂停
    val liveKeys = s.liveKeys
    val foreground = AppModel.foreground
    LaunchedEffect(s.moduleId, s.typeId, liveKeys, foreground) {
        if (liveKeys.isEmpty() || !foreground) return@LaunchedEffect
        while (true) {
            runCatching { AppModel.api().liveOptions(s.project.id, s.moduleId, liveKeys) }.getOrNull()?.let { v ->
                if (v.any { (k, o) -> s.liveOptions[k] != o }) s.liveOptions = s.liveOptions + v
            }
            delay(4000)
        }
    }
    PageScaffold(
        title = if (s.isEdit) tr("runconfig.edit") else tr("runconfig.new"),
        subtitle = s.project.name,
        actions = {
            if (s.isEdit && s.config != null) PaperMoreButton({ RunConfigMenu.entries(s.project, s.config, inEditor = true, onDeleted = { close(s) }) })
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = sp.xl, vertical = sp.md), horizontalArrangement = Arrangement.spacedBy(sp.md)) {
                PaperButton(tr("common.cancel"), onClick = { AppModel.nav.pop() }, modifier = Modifier.weight(1f))
                PaperButton(
                    if (s.isEdit) tr("common.save") else tr("common.create"),
                    onClick = { scope.launch { if (s.submit()) close(s) } },
                    kind = ButtonKind.Primary,
                    loading = s.busy,
                    enabled = s.typeId.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(sp.lg), verticalArrangement = Arrangement.spacedBy(sp.xl)) {
            s.error?.let { PaperAlert(Tone.Danger, it) }
            PaperCard {
                if (s.project.modules.size > 1) {
                    PaperSelect(
                        s.project.modules.map { SelectOption(it.id, it.name, detail = it.relDir.ifEmpty { null }) },
                        selected = s.moduleId,
                        onSelect = { s.moduleId = it },
                        label = tr("project.module"),
                        enabled = !s.isEdit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (s.isCreate) {
                    if (s.loading && s.choices.isEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                            FieldLabel(tr("runconfig.type"))
                            PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
                        }
                    } else {
                        PaperSelect(
                            s.choices.map { ch ->
                                SelectOption(ch.type.id, ch.type.name, icon = (IconSource.parse(ch.type.icon, ch.type.plugin) as? IconSource.Builtin)?.name)
                            },
                            selected = s.typeId.ifEmpty { null },
                            onSelect = { s.selectType(it) },
                            label = tr("runconfig.type"),
                            placeholder = tr("form.choose"),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                        FieldLabel(tr("runconfig.type"))
                        Text(
                            AppModel.data.contributions.runConfigType(s.config?.plugin, s.typeId)?.name ?: s.typeId,
                            style = PaperTheme.typography.body,
                            color = c.textPrimary,
                        )
                    }
                }
                PaperTextField(
                    s.name,
                    { s.name = it },
                    label = tr("common.name"),
                    error = if (s.showErrors && s.name.isBlank()) "" else null,
                )
            }
            val fields = s.fields
            if (fields.isNotEmpty()) {
                PaperCard {
                    FormFields(fields, s.values, { s.values = it }, optionValues = s.optionValues, showErrors = s.showErrors)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                PaperSectionHeader(tr("config.env"))
                PaperCard { KeyValueEditor(s.env, { s.env = it }) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                PaperSectionHeader(tr("runconfig.remoteLinks"), info = tr("runconfig.remoteLinks.info"))
                PaperCard(padding = sp.md) {
                    // 至少选一个：取消最后一个时不生效
                    PaperCheckbox(s.links.forward, onCheckedChange = { v ->
                        if (v || s.links.direct) s.links = s.links.copy(forward = v)
                    }, label = tr("runconfig.remoteLinks.forward"), modifier = Modifier.fillMaxWidth())
                    PaperCheckbox(s.links.direct, onCheckedChange = { v ->
                        if (v || s.links.forward) s.links = s.links.copy(direct = v)
                    }, label = tr("runconfig.remoteLinks.direct"), modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
