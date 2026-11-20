package cn.lemonit.paperplane.features.toolchains

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.features.importflow.ServerPathPicker
import cn.lemonit.paperplane.features.runs.TaskLog
import cn.lemonit.paperplane.kit.addToolchain
import cn.lemonit.paperplane.kit.availableToolchains
import cn.lemonit.paperplane.kit.installToolchain
import cn.lemonit.paperplane.kit.model.AvailableVersion
import cn.lemonit.paperplane.kit.model.TaskInfo
import cn.lemonit.paperplane.kit.model.Toolchain
import cn.lemonit.paperplane.kit.model.ToolchainKind
import cn.lemonit.paperplane.kit.refreshToolchains
import cn.lemonit.paperplane.kit.removeToolchain
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.kit.util.IconSource
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperBadge
import cn.lemonit.paperplane.ui.components.PaperBottomSheet
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperCard
import cn.lemonit.paperplane.ui.components.PaperCheckbox
import cn.lemonit.paperplane.ui.components.PaperCountBadge
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperLoadingPage
import cn.lemonit.paperplane.ui.components.PaperLongPressArea
import cn.lemonit.paperplane.ui.components.PaperMoreButton
import cn.lemonit.paperplane.ui.components.PaperProgressBar
import cn.lemonit.paperplane.ui.components.PaperSearchField
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.icons.RefIcon
import cn.lemonit.paperplane.ui.shell.PageScaffold
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 工具链（对照 Mac Features/Toolchains）：按种类列出已装的，下载新版本（任务进度），添加服务器上已有的路径，移除，重新扫描。 */
@Composable
fun ToolchainsPage() {
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { AppModel.reloadToolchains() }
    PageScaffold(
        title = tr("sidebar.toolchains"),
        actions = {
            PaperIconButton("refresh-cw", tr("toolchain.rescan"), onClick = {
                refreshing = true
                scope.launch {
                    try {
                        AppModel.api().refreshToolchains()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        cn.lemonit.paperplane.ui.components.Toasts.error(e)
                    }
                    AppModel.reloadToolchains()
                    refreshing = false
                }
            }, loading = refreshing)
        },
    ) {
        ToolchainsContent(AppModel.data.toolchainKinds)
    }
}

/** 页面内容（截图测试直接给种类） */
@Composable
fun ToolchainsContent(kinds: List<ToolchainKind>) {
    if (kinds.isEmpty()) {
        if (!AppModel.data.loaded) PaperLoadingPage() else PaperEmptyState("boxes", tr("toolchain.noKinds"))
        return
    }
    val sp = PaperTheme.spacing
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(sp.lg), verticalArrangement = Arrangement.spacedBy(sp.xxl)) {
        for (k in kinds) androidx.compose.runtime.key(k.kind) { KindSection(k) }
    }
}

@Composable
private fun KindSection(kind: ToolchainKind) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val list = AppModel.data.toolchains(kind.kind).sortedWith { a, b -> compareVersions(b.version, a.version) }
    val runs = AppModel.runs
    val installing = runs.activeTasks.filter { t ->
        runs.toolchainTaskKind[t.id]?.let { it == kind.kind } ?: (t.kind == "toolchain" && t.title.contains(kind.name, ignoreCase = true))
    }
    var download by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
        Row(Modifier.padding(horizontal = sp.xs), horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalAlignment = Alignment.CenterVertically) {
                RefIcon(IconSource.parse(kind.icon, kind.plugin), fallback = "boxes", tint = c.iconPrimary)
                Text(kind.name, style = PaperTheme.typography.title, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (list.isNotEmpty()) PaperCountBadge(list.size)
            }
            if (kind.installable) {
                PaperButton(tr("toolchain.download"), onClick = { download = true }, size = ButtonSize.Small, icon = "download")
            }
            PaperMoreButton({
                listOf(MenuEntry.Item(tr("toolchain.addPath").trimEnd('…'), "folder-plus") { addPath(kind) })
            }, size = ButtonSize.Small)
        }
        for (t in installing) androidx.compose.runtime.key(t.id) { InstallingCard(t) }
        if (list.isEmpty()) {
            PaperCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("toolchain.noneInstalled"), style = PaperTheme.typography.body, color = c.textSecondary, modifier = Modifier.weight(1f))
                    PaperButton(tr("toolchain.addPath").trimEnd('…'), onClick = { addPath(kind) }, kind = ButtonKind.Text, size = ButtonSize.Small)
                }
            }
        } else {
            PaperCard(padding = 0.dp) {
                Column {
                    list.forEachIndexed { i, tc ->
                        if (i > 0) PaperDivider(subtle = true, modifier = Modifier.padding(start = sp.lg))
                        ToolchainRow(tc)
                    }
                }
            }
        }
    }
    if (download) AvailableVersionsSheet(kind) { download = false }
}

/** 正在下载的版本：进度、看日志、取消 */
@Composable
private fun InstallingCard(t: TaskInfo) {
    val c = PaperTheme.colors
    PaperCard(padding = PaperTheme.spacing.md) {
        Row(horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = c.accent)
            Column(Modifier.weight(1f)) {
                Text(t.title, style = PaperTheme.typography.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                t.message?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = PaperTheme.typography.small, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            PaperIconButton("scroll-text", tr("task.viewLog"), onClick = { TaskLog.show(t.id) }, size = ButtonSize.Small, tint = c.iconSecondary)
            PaperIconButton("x", tr("common.cancel"), onClick = { Actions.cancelTask(t.id) }, size = ButtonSize.Small, tint = c.iconSecondary)
        }
        PaperProgressBar(if (t.percent < 0) null else (t.percent / 100).toFloat().coerceIn(0f, 1f))
    }
}

/** 一个已安装的版本：版本、厂商、架构、来源、路径；··· 复制路径、移除 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolchainRow(tc: Toolchain) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val menu = {
        listOf(
            MenuEntry.Item(tr("toolchain.copyPath"), "copy") { Actions.copy(tc.path) },
            MenuEntry.Divider,
            MenuEntry.Item(tr("common.remove"), "trash-2", destructive = true) { remove(tc) },
        )
    }
    PaperLongPressArea(onClick = null, menu = menu, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = sp.lg, end = sp.xs, top = sp.md, bottom = sp.md),
            horizontalArrangement = Arrangement.spacedBy(sp.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                    Text(tc.version, style = PaperTheme.typography.bodyStrong, color = c.textPrimary, modifier = Modifier.align(Alignment.CenterVertically))
                    tc.vendor?.takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = PaperTheme.typography.body, color = c.textSecondary, modifier = Modifier.align(Alignment.CenterVertically))
                    }
                    tc.arch?.takeIf { it.isNotEmpty() }?.let { PaperBadge(it, mono = true) }
                    if (tc.managed) PaperBadge(tr("toolchain.managed"), tone = Tone.Info)
                    else tc.source?.takeIf { it.isNotEmpty() }?.let { PaperBadge(it) }
                }
                Text(Format.abbreviatePath(tc.path), style = PaperTheme.typography.monoSmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
            }
            PaperMoreButton(menu, size = ButtonSize.Small)
        }
    }
}

private fun remove(tc: Toolchain) {
    Dialogs.confirm(
        tr("toolchain.remove.title", tc.displayName),
        message = if (tc.managed) tr("toolchain.remove.managed") else tr("toolchain.remove.external"),
        confirmText = tr("common.remove"),
        danger = true,
    ) {
        AppModel.api().removeToolchain(tc.id)
        AppModel.reloadToolchains()
    }
}

/** 登记服务器上已有的工具链（选服务器上的安装目录） */
private fun addPath(kind: ToolchainKind) {
    ServerPathPicker.pick(tr("toolchain.addPath").trimEnd('…')) { path ->
        AppModel.perform(tr("toolchain.addPath").trimEnd('…')) { api ->
            api.addToolchain(kind.kind, path)
            AppModel.reloadToolchains()
        }
    }
}

/** 可下载的版本（toolchain.available）：筛选、只看 LTS、推荐标记；点一个开始下载 */
@Composable
private fun AvailableVersionsSheet(kind: ToolchainKind, onDismiss: () -> Unit) {
    var versions by remember { mutableStateOf<List<AvailableVersion>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }
    var ltsOnly by rememberSaveable { mutableStateOf(false) }
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    LaunchedEffect(kind.kind) {
        try {
            versions = AppModel.api().availableToolchains(kind.kind)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        }
    }
    PaperBottomSheet(onDismiss = onDismiss, title = tr("mobile.toolchain.downloadTitle", kind.name), scrollable = false, edgeToEdge = true) { dismiss ->
        Row(Modifier.fillMaxWidth().padding(horizontal = sp.xl, vertical = sp.xs), horizontalArrangement = Arrangement.spacedBy(sp.md), verticalAlignment = Alignment.CenterVertically) {
            PaperSearchField(filter, { filter = it }, placeholder = tr("common.filter"), modifier = Modifier.weight(1f))
            PaperCheckbox(ltsOnly, onCheckedChange = { ltsOnly = it }, label = "LTS")
        }
        val list = versions
        when {
            list != null -> {
                val f = filter.trim()
                val shown = list.filter { v ->
                    (!ltsOnly || v.lts) && (f.isEmpty() || v.version.contains(f) || v.label.orEmpty().contains(f, true) || v.vendor.orEmpty().contains(f, true))
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    items(shown, key = { it.id }) { v ->
                        PaperLongPressArea(onClick = {
                            dismiss()
                            install(kind, v)
                        }, menu = null, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = PaperTheme.sizes.rowHeight).padding(horizontal = sp.xl, vertical = sp.sm),
                                horizontalArrangement = Arrangement.spacedBy(sp.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalAlignment = Alignment.CenterVertically) {
                                    Text(v.label ?: v.version, style = PaperTheme.typography.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    v.vendor?.takeIf { it.isNotEmpty() && !(v.label ?: "").contains(it) }?.let {
                                        Text(it, style = PaperTheme.typography.small, color = c.textTertiary, maxLines = 1)
                                    }
                                    if (v.lts) PaperBadge("LTS", tone = Tone.Success)
                                    if (v.recommended) PaperBadge(tr("toolchain.recommended"), tone = Tone.Info)
                                }
                                PaperIcon("download", size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
                            }
                        }
                    }
                }
            }
            error != null -> Text(error ?: "", style = PaperTheme.typography.body, color = c.danger, modifier = Modifier.padding(sp.xl))
            else -> Row(Modifier.fillMaxWidth().padding(sp.xl), horizontalArrangement = Arrangement.Center) { PaperSpinner(tint = c.iconSecondary) }
        }
    }
}

private fun install(kind: ToolchainKind, v: AvailableVersion) = AppModel.perform(tr("toolchain.download")) { api ->
    val t = api.installToolchain(kind.kind, v.version)
    AppModel.runs.toolchainTaskKind[t.id] = kind.kind
    AppModel.track(t)
}

/** 版本号比较（数字段逐段比较；a 比 b 新为正） */
fun compareVersions(a: String, b: String): Int {
    val pa = a.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toLongOrNull() ?: 0 }
    val pb = b.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toLongOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrElse(i) { 0 }
        val y = pb.getOrElse(i) { 0 }
        if (x != y) return x.compareTo(y)
    }
    return a.compareTo(b)
}
