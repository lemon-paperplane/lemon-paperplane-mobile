package cn.lemonit.paperplane.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.BuildConfig
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.kit.PaperplaneJson
import cn.lemonit.paperplane.kit.getSettings
import cn.lemonit.paperplane.kit.mirrorsProbe
import cn.lemonit.paperplane.kit.mirrorsStatus
import cn.lemonit.paperplane.kit.model.AppSettings
import cn.lemonit.paperplane.kit.model.EditorSettings
import cn.lemonit.paperplane.kit.model.MirrorStatus
import cn.lemonit.paperplane.kit.model.SystemInfo
import cn.lemonit.paperplane.kit.systemInfo
import cn.lemonit.paperplane.kit.updateSettings
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.ui.components.PaperGroup
import cn.lemonit.paperplane.ui.components.PaperListRow
import cn.lemonit.paperplane.ui.components.PaperSegmented
import cn.lemonit.paperplane.ui.components.PaperSelectSheet
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.components.PaperToggleRow
import cn.lemonit.paperplane.ui.components.SelectOption
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.theme.PaperTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

// 当前服务器上的设置（settings.get / settings.update）：镜像、运行与资源、编辑器、关于。对照 Mac SettingsPage / EditorSettingsSection。

/** 改服务器设置：先本地生效（避免控件回跳），再 settings.update（JSON merge）；失败时取回服务器上的值 */
internal fun updateSettings(patch: Map<String, JsonElement>, after: (suspend () -> Unit)? = null) {
    val cur = AppModel.data.settings
    if (cur != null) {
        runCatching {
            val merged = JsonObject(PaperplaneJson.encodeToJsonElement(AppSettings.serializer(), cur).jsonObject + patch)
            AppModel.data.settings = PaperplaneJson.decodeFromJsonElement(AppSettings.serializer(), merged)
        }
    }
    AppModel.scope.launch {
        try {
            AppModel.data.settings = AppModel.api().updateSettings(JsonObject(patch))
            after?.invoke()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Toasts.error(e, tr("settings.saveFailed"))
            runCatching { AppModel.data.settings = AppModel.api().getSettings() }
        }
    }
}

// ---------------------------------------------------------------- 镜像

@Composable
internal fun MirrorGroup(s: AppSettings) {
    val c = PaperTheme.colors
    var mirrors by remember { mutableStateOf<MirrorStatus?>(null) }
    var probing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(AppModel.currentServerId) { mirrors = runCatching { AppModel.api().mirrorsStatus() }.getOrNull() }
    fun regionTitle(r: String) = when (r) {
        "china" -> tr("settings.mirror.china")
        "global" -> tr("settings.mirror.global")
        else -> r
    }
    PaperGroup(tr("settings.mirrors"), info = tr("settings.mirrors.info")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = PaperTheme.spacing.xl, vertical = PaperTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("settings.mirrorRegion"), style = PaperTheme.typography.body, color = c.textPrimary, modifier = Modifier.weight(1f))
                mirrors?.effective?.takeIf { it.isNotEmpty() }?.let {
                    Text(tr("settings.mirror.effective", regionTitle(it)), style = PaperTheme.typography.small, color = c.textSecondary)
                }
            }
            PaperSegmented(
                listOf("auto" to tr("settings.mirror.auto"), "china" to tr("settings.mirror.china"), "global" to tr("settings.mirror.global")),
                selected = s.mirrorRegion.ifEmpty { "auto" },
                onSelect = { v -> updateSettings(mapOf("mirrorRegion" to JsonPrimitive(v))) { mirrors = runCatching { AppModel.api().mirrorsStatus() }.getOrNull() } },
                fill = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        for (p in mirrors?.probes.orEmpty()) {
            PaperListRow(
                p.name,
                subtitle = p.url,
                subtitleMono = true,
                leading = { Box(Modifier.size(7.dp).background(if (p.ok) c.statusRunning else c.statusFailed, CircleShape)) },
                value = if (p.ok) "${(p.ms ?: 0.0).toInt()} ms" else tr("settings.mirror.unreachable"),
            )
        }
        SettingRow(tr("settings.mirror.probe"), icon = "gauge", onClick = {
            if (probing) return@SettingRow
            probing = true
            scope.launch {
                try {
                    mirrors = AppModel.api().mirrorsProbe()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toasts.error(e)
                } finally {
                    probing = false
                }
            }
        }, trailing = { if (probing) PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary) })
    }
}

// ---------------------------------------------------------------- 运行与资源

/** 数字设置：一行显示「值 单位」，点开输入面板 */
private data class NumberSetting(val key: String, val title: String, val info: String?, val value: Int, val unit: String, val range: IntRange)

@Composable
internal fun BehaviorGroup(s: AppSettings) {
    var editing by remember { mutableStateOf<NumberSetting?>(null) }
    val numbers = listOf(
        NumberSetting("pluginIdleSeconds", tr("settings.pluginIdle"), tr("settings.pluginIdle.info"), s.pluginIdleSeconds, tr("unit.seconds"), 0..86_400),
        NumberSetting("logLinesPerRun", tr("settings.logLines"), tr("settings.logLines.info"), s.logLinesPerRun, tr("unit.lines"), 0..10_000_000),
        NumberSetting("logRetentionRuns", tr("settings.logRetention"), null, s.logRetentionRuns, tr("unit.runs"), 0..100_000),
        NumberSetting("gitAutoFetchMinutes", tr("settings.gitFetch"), tr("settings.gitFetch.info"), s.gitAutoFetchMinutes, tr("unit.minutes"), 0..100_000),
    )
    PaperGroup(tr("settings.behavior")) {
        PaperToggleRow(tr("settings.autoToolchains"), s.autoInstallToolchains, { updateSettings(mapOf("autoInstallToolchains" to JsonPrimitive(it))) }, info = tr("settings.autoToolchains.info"))
        PaperToggleRow(tr("settings.autoDeps"), s.autoInstallDependencies, { updateSettings(mapOf("autoInstallDependencies" to JsonPrimitive(it))) }, info = tr("settings.autoDeps.info"))
        PaperToggleRow(tr("settings.autoSync"), s.autoSyncOnImport, { updateSettings(mapOf("autoSyncOnImport" to JsonPrimitive(it))) }, info = tr("settings.autoSync.info"))
        for (n in numbers) {
            SettingRow(n.title, info = n.info, value = "${n.value} ${n.unit}", showChevron = true, onClick = { editing = n })
        }
    }
    editing?.let { n ->
        ValueInputSheet(n.title, n.value.toString(), onDismiss = { editing = null }, number = true, range = n.range, suffix = n.unit) { v ->
            val x = v.toInt()
            if (x != n.value) updateSettings(mapOf(n.key to JsonPrimitive(x)))
        }
    }
}

// ---------------------------------------------------------------- 编辑器

@Composable
internal fun EditorGroup(s: AppSettings) {
    val e = s.editor ?: EditorSettings()
    val contributions = AppModel.data.contributions
    // 空值 = 各端自己的默认方案；手机外接键盘用 Ctrl 组合键（同 Windows）
    val platformDefault = EditorSettings.PLATFORM_DEFAULT_KEYMAP
    var pick by remember { mutableStateOf<String?>(null) }
    var number by remember { mutableStateOf<String?>(null) }
    var font by remember { mutableStateOf(false) }
    fun update(change: (EditorSettings) -> EditorSettings) {
        val next = change(e)
        if (next == e) return
        updateSettings(mapOf("editor" to PaperplaneJson.encodeToJsonElement(EditorSettings.serializer(), next)))
    }
    fun platformTitle(p: String) = when (p) {
        "macos" -> "macOS"
        "windows" -> "Windows"
        "linux" -> "Linux"
        else -> tr("settings.editor.platform.any")
    }
    val keymaps = run {
        val order = mapOf("windows" to 0, "linux" to 1, "any" to 2)
        val list = contributions.keymaps.sortedWith(compareBy({ order[it.platform] ?: 3 }, { it.name }))
        val opts = list.map { k ->
            SelectOption(k.id, k.name.ifEmpty { k.id }, detail = platformTitle(k.platform) + if (k.id == platformDefault) " · " + tr("settings.editor.keymap.default") else "")
        }
        if (opts.none { it.value == platformDefault }) listOf(SelectOption(platformDefault, "Eclipse (Windows)", detail = tr("settings.editor.keymap.default"))) + opts else opts
    }
    val themes = listOf(SelectOption("", tr("settings.editor.theme.followApp"))) + contributions.editorThemes.map { t ->
        SelectOption(t.id, t.name.ifEmpty { t.id }, detail = if (t.kind == "dark") tr("settings.dark") else tr("settings.light"))
    }
    val autoSave = listOf(
        SelectOption("off", tr("settings.editor.autoSave.off")),
        SelectOption("onFocusChange", tr("settings.editor.autoSave.onFocusChange")),
        SelectOption("afterDelay", tr("settings.editor.autoSave.afterDelay")),
    )
    val keymapSel = e.keymap.ifEmpty { platformDefault }
    PaperGroup(tr("settings.editor"), info = tr("mobile.settings.editor.info")) {
        SettingRow(tr("settings.editor.keymap"), value = keymaps.firstOrNull { it.value == keymapSel }?.title ?: keymapSel, showChevron = true, onClick = { pick = "keymap" })
        SettingRow(tr("settings.editor.theme"), value = themes.firstOrNull { it.value == e.theme }?.title ?: e.theme, showChevron = true, onClick = { pick = "theme" })
        SettingRow(tr("settings.editor.fontSize"), value = "${e.fontSize} pt", showChevron = true, onClick = { number = "fontSize" })
        SettingRow(tr("settings.editor.fontFamily"), value = e.fontFamily.ifEmpty { tr("settings.editor.fontFamily.default") }, showChevron = true, onClick = { font = true })
        SettingRow(tr("settings.editor.tabSize"), value = "${e.tabSize}", showChevron = true, onClick = { number = "tabSize" })
        SettingRow(tr("settings.editor.indent")) {
            PaperSegmented(
                listOf(true to tr("settings.editor.spaces"), false to tr("settings.editor.tabs")),
                selected = e.insertSpaces,
                onSelect = { v -> update { it.copy(insertSpaces = v) } },
            )
        }
        SettingRow(tr("settings.editor.autoSave"), value = autoSave.firstOrNull { it.value == e.autoSave.ifEmpty { "off" } }?.title, showChevron = true, onClick = { pick = "autoSave" })
        PaperToggleRow(tr("settings.editor.wordWrap"), e.wordWrap, { v -> update { it.copy(wordWrap = v) } })
        PaperToggleRow(tr("settings.editor.trim"), e.trimTrailingWhitespace, { v -> update { it.copy(trimTrailingWhitespace = v) } })
        PaperToggleRow(tr("settings.editor.finalNewline"), e.insertFinalNewline, { v -> update { it.copy(insertFinalNewline = v) } })
        PaperToggleRow(tr("settings.editor.showWhitespace"), e.showWhitespace, { v -> update { it.copy(showWhitespace = v) } })
    }
    when (pick) {
        "keymap" -> PaperSelectSheet(keymaps, keymapSel, onDismiss = { pick = null }, title = tr("settings.editor.keymap")) { v ->
            update { it.copy(keymap = if (v == platformDefault) "" else v) }
        }
        "theme" -> PaperSelectSheet(themes, e.theme, onDismiss = { pick = null }, title = tr("settings.editor.theme")) { v -> update { it.copy(theme = v) } }
        "autoSave" -> PaperSelectSheet(autoSave, e.autoSave.ifEmpty { "off" }, onDismiss = { pick = null }, title = tr("settings.editor.autoSave")) { v ->
            update { it.copy(autoSave = v) }
        }
    }
    when (number) {
        "fontSize" -> ValueInputSheet(tr("settings.editor.fontSize"), "${e.fontSize}", onDismiss = { number = null }, number = true, range = 8..40, suffix = "pt") { v ->
            update { it.copy(fontSize = v.toInt()) }
        }
        "tabSize" -> ValueInputSheet(tr("settings.editor.tabSize"), "${e.tabSize}", onDismiss = { number = null }, number = true, range = 1..16) { v ->
            update { it.copy(tabSize = v.toInt()) }
        }
    }
    if (font) {
        ValueInputSheet(tr("settings.editor.fontFamily"), e.fontFamily, onDismiss = { font = false }, placeholder = tr("settings.editor.fontFamily.default"), allowEmpty = true) { v ->
            update { it.copy(fontFamily = v) }
        }
    }
}

// ---------------------------------------------------------------- 关于

@Composable
internal fun AboutGroup() {
    var info by remember { mutableStateOf<SystemInfo?>(AppModel.systemInfo) }
    // 每次打开时刷新一次内存数据
    LaunchedEffect(AppModel.currentServerId) { runCatching { AppModel.api().systemInfo() }.getOrNull()?.let { info = it } }
    val i = info
    PaperGroup(tr("settings.about")) {
        SettingRow(tr("mobile.settings.about.app"), value = BuildConfig.VERSION_NAME)
        SettingRow(tr("settings.about.daemon"), value = i?.version?.ifEmpty { "-" } ?: "-")
        SettingRow(tr("settings.about.daemonMemory"), value = Format.memory(i?.memoryMB ?: 0.0))
        SettingRow(tr("settings.about.pluginProcs"), value = "${i?.pluginProcs ?: 0}")
        SettingRow(tr("settings.about.pluginMemory"), value = Format.memory(i?.pluginMemoryMB ?: 0.0))
        PaperListRow(tr("settings.about.dataDir"), subtitle = Format.abbreviatePath(i?.dataDir.orEmpty()), subtitleMono = true, subtitleMaxLines = 3)
    }
}
