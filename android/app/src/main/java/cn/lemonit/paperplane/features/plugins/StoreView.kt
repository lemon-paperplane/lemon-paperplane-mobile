package cn.lemonit.paperplane.features.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.kit.installFromStore
import cn.lemonit.paperplane.kit.model.StoreEntry
import cn.lemonit.paperplane.kit.storeList
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.PaperBadge
import cn.lemonit.paperplane.ui.components.PaperBottomSheet
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperCard
import cn.lemonit.paperplane.ui.components.PaperEmptyState
import cn.lemonit.paperplane.ui.components.PaperGroup
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperListRow
import cn.lemonit.paperplane.ui.components.PaperLoadingPage
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 本页发起的商店安装：插件 id → 任务 id（任务结束后刷新列表） */
private val installing = mutableStateMapOf<String, String>()

/** 商店（store.list）：可安装 / 可更新 / 已安装 / 不兼容；点卡片看全部版本 */
@Composable
internal fun StoreView() {
    var entries by remember { mutableStateOf<List<StoreEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<StoreEntry?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun load(refresh: Boolean) {
        loading = true
        try {
            entries = AppModel.api().storeList(refresh)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }
    LaunchedEffect(Unit) { load(false) }
    // 本页发起的安装结束后刷新「已安装版本」
    val tasks = AppModel.runs.tasks
    // 任务不在了（切换过服务器、已被清理）也算结束
    val finished = installing.entries.filter { (_, tid) -> tasks[tid]?.isRunning != true }.map { it.key }
    LaunchedEffect(finished) {
        if (finished.isEmpty()) return@LaunchedEffect
        finished.forEach { installing.remove(it) }
        load(false)
    }
    val list = entries
    when {
        list != null -> if (list.isEmpty()) {
            PaperEmptyState("store", tr("plugin.storeEmpty"), actionText = tr("plugin.storeRefresh"), actionIcon = "refresh-cw") { scope.launch { load(true) } }
        } else {
            val sp = PaperTheme.spacing
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(sp.lg), verticalArrangement = Arrangement.spacedBy(sp.md)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("mobile.store.count", list.size), style = PaperTheme.typography.label, color = PaperTheme.colors.textTertiary, modifier = Modifier.weight(1f).padding(start = sp.xs))
                        PaperIconButton("refresh-cw", tr("plugin.storeRefresh"), onClick = { scope.launch { load(true) } }, size = ButtonSize.Small, loading = loading, tint = PaperTheme.colors.iconSecondary)
                    }
                }
                items(list, key = { it.id }) { e -> StoreCard(e) { detail = e } }
            }
        }
        error != null -> PaperEmptyState("circle-alert", error ?: "", actionText = tr("connection.retry"), actionIcon = "refresh-cw") { scope.launch { load(true) } }
        else -> PaperLoadingPage()
    }
    detail?.let { e -> StoreDetailSheet(e) { detail = null } }
}

private fun canUpdate(e: StoreEntry) = e.installed != null && e.installed != e.version

private fun install(e: StoreEntry) = AppModel.perform(tr("plugin.installFailed")) { api ->
    val t = api.installFromStore(e.id)
    installing[e.id] = t.id
    AppModel.track(t)
}

/** 卡片右上角：不兼容 / 已安装 / 安装 / 更新 */
@Composable
private fun StoreAction(e: StoreEntry) {
    val busy = installing[e.id]?.let { AppModel.runs.tasks[it]?.isRunning } == true
    when {
        !e.compatible -> PaperBadge(tr("plugin.incompatible"), tone = Tone.Warning)
        e.installed != null && !canUpdate(e) -> PaperBadge(tr("plugin.installedVersion", e.installed!!), tone = Tone.Success)
        else -> PaperButton(
            if (canUpdate(e)) tr("plugin.update") else tr("plugin.install"),
            onClick = { install(e) },
            kind = ButtonKind.Primary,
            size = ButtonSize.Small,
            icon = "download",
            loading = busy,
        )
    }
}

@Composable
private fun StoreCard(e: StoreEntry, onOpen: () -> Unit) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    PaperCard(onClick = onOpen) {
        Row(horizontalArrangement = Arrangement.spacedBy(sp.md), verticalAlignment = Alignment.CenterVertically) {
            PaperIcon("puzzle", size = PaperTheme.sizes.iconLarge, tint = c.iconSecondary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(e.name, style = PaperTheme.typography.title, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(e.version, e.vendor?.takeIf { it.isNotEmpty() }).joinToString("  ·  "),
                    style = PaperTheme.typography.small,
                    color = c.textTertiary,
                    maxLines = 1,
                )
            }
            StoreAction(e)
        }
        if (!e.description.isNullOrEmpty()) {
            Text(e.description!!, style = PaperTheme.typography.small, color = c.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 商店条目详情：说明、兼容内核、全部版本（内核按兼容性挑好要装的那个） */
@Composable
private fun StoreDetailSheet(e: StoreEntry, onDismiss: () -> Unit) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    PaperBottomSheet(onDismiss = onDismiss, title = e.name) { _ ->
        Row(horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull(e.id, e.vendor).joinToString("  ·  "), style = PaperTheme.typography.small, color = c.textSecondary, modifier = Modifier.weight(1f))
            StoreAction(e)
        }
        if (!e.description.isNullOrEmpty()) Text(e.description!!, style = PaperTheme.typography.body, color = c.textSecondary)
        e.engines?.paperplane?.takeIf { it.isNotEmpty() }?.let {
            Text(tr("plugin.engines", it), style = PaperTheme.typography.small, color = c.textTertiary)
        }
        if (e.versions.isNotEmpty()) {
            PaperGroup(tr("mobile.store.versions"), info = tr("mobile.store.versions.info")) {
                for (v in e.versions) {
                    PaperListRow(
                        v.version,
                        subtitle = v.engines?.paperplane?.takeIf { it.isNotEmpty() }?.let { tr("plugin.engines", it) },
                        trailing = {
                            if (v.version == e.version && e.compatible) PaperBadge(tr("mobile.store.selected"), tone = Tone.Info)
                            if (v.version == e.installed) PaperBadge(tr("plugin.installed"), tone = Tone.Success)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(sp.xs))
    }
}
