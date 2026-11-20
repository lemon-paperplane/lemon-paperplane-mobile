package cn.lemonit.paperplane.features.importflow

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.Route
import cn.lemonit.paperplane.kit.fsBrowse
import cn.lemonit.paperplane.kit.fsMkdir
import cn.lemonit.paperplane.kit.model.FSBrowseEntry
import cn.lemonit.paperplane.kit.model.FSBrowseResult
import cn.lemonit.paperplane.kit.model.FileIcon
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.kit.util.ServerPath
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperListRow
import cn.lemonit.paperplane.ui.components.PaperMoreButton
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.shell.PageScaffold
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 选服务器上的路径（导入、添加工具链、克隆目标、启动项选执行目录等共用）。对照 Mac Features/Servers/ServerFolderPicker。
 * 打开一个全屏选择页（fs.browse 浏览、面包屑、新建文件夹、Windows 服务器的盘符、显示隐藏文件），选好后回调服务器上的绝对路径。
 * 起始目录：给了 start 用它，否则上次在这台服务器上浏览到的位置，再否则用户主目录。
 *
 * 注意：选择页是压栈的全屏页面，从底部弹出面板里调用时要先收起面板（面板盖在所有页面上面），选完再重新打开。
 *
 * @param title 选择页标题（说明要选什么，如「导入文件夹」）
 * @param start 起始目录；为空时从上次位置或用户主目录开始
 * @param files true 时选文件（否则选文件夹）
 * @param confirmText 确认按钮文字（默认「选择」）
 * @param onCancel 没选就返回时
 */
object ServerPathPicker {
    fun pick(
        title: String,
        start: String? = null,
        files: Boolean = false,
        confirmText: String? = null,
        onCancel: (() -> Unit)? = null,
        onPicked: (String) -> Unit,
    ) {
        AppModel.nav.push(ServerPathPickerRoute(title.trimEnd('…', '.'), start, files, confirmText, onCancel, onPicked))
    }

    private const val PREFS = "paperplane_picker"

    /** 上次浏览到的位置（按服务器记） */
    internal var lastPath: String?
        get() = AppModel.currentServerId?.let { id -> prefs()?.getString("last.$id", null) }
        set(v) {
            val id = AppModel.currentServerId ?: return
            prefs()?.edit()?.putString("last.$id", v)?.apply()
        }

    private fun prefs() = runCatching { AppModel.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()
}

/** 选择页（每次打开一个新的页面键，不和别的选择页合并） */
internal class ServerPathPickerRoute(
    val title: String,
    val start: String?,
    val files: Boolean,
    val confirmText: String?,
    val onCancel: (() -> Unit)?,
    val onPicked: (String) -> Unit,
) : Route {
    override val key: String = "serverPathPicker:" + UUID.randomUUID()

    @Composable
    override fun Content() {
        val state = remember { PickerState(files) }
        var picked by remember { mutableStateOf(false) }
        // 返回 = 取消
        BackHandler {
            AppModel.nav.removeWhere { it.key == key }
            onCancel?.invoke()
        }
        LaunchedEffect(Unit) { state.load(start?.takeIf { it.isNotEmpty() } ?: ServerPathPicker.lastPath) }
        ServerPathPickerPage(
            title = title,
            state = state,
            confirmText = confirmText,
            onBack = {
                AppModel.nav.removeWhere { it.key == key }
                onCancel?.invoke()
            },
            onChoose = { path ->
                if (picked) return@ServerPathPickerPage
                picked = true
                AppModel.nav.removeWhere { it.key == key }
                onPicked(path)
            },
        )
    }
}

/** 选择页的状态：当前目录的列表、选中的文件、是否显示隐藏文件 */
@Stable
internal class PickerState(val files: Boolean) {
    var listing by mutableStateOf<FSBrowseResult?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var showHidden by mutableStateOf(false)
    /** 选文件模式下选中的文件 */
    var selected by mutableStateOf<String?>(null)

    val path: ServerPath get() = listing?.sep?.takeIf { it.isNotEmpty() }?.let { ServerPath(it) } ?: AppModel.serverPath

    val visible: List<FSBrowseEntry>
        get() = listing?.entries.orEmpty().filter { e -> (showHidden || !e.hidden) && (e.dir || files) }

    suspend fun load(p: String?) {
        loading = true
        try {
            val r = AppModel.api().fsBrowse(p)
            listing = r
            selected = null
            error = null
            ServerPathPicker.lastPath = r.path
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
            // 记住的位置已经不在了：回到主目录
            if (listing == null && !p.isNullOrEmpty()) {
                loading = false
                load(null)
                return
            }
        } finally {
            loading = false
        }
    }
}

/** 面包屑：从根（或主目录 ~）到当前目录的每一级 */
internal fun breadcrumbs(path: String, home: String, sp: ServerPath): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    var cur: String? = sp.normalize(path)
    val homeN = home.takeIf { it.isNotEmpty() }?.let { sp.normalize(it) }
    while (cur != null) {
        if (homeN != null && sp.same(cur, homeN)) {
            out += "~" to cur
            break
        }
        out += sp.lastComponent(cur).ifEmpty { cur } to cur
        cur = sp.parent(cur)
    }
    return out.asReversed()
}

/** 选择页外观（状态由调用方给，截图测试直接画） */
@Composable
internal fun ServerPathPickerPage(
    title: String,
    state: PickerState,
    confirmText: String?,
    onBack: () -> Unit,
    onChoose: (String) -> Unit,
) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val scope = rememberCoroutineScope()
    val listing = state.listing
    PageScaffold(
        title = title,
        subtitle = if (state.files) tr("remotePicker.titleFile") else tr("remotePicker.title"),
        onBack = onBack,
        actions = {
            PaperIconButton("folder-plus", tr("remotePicker.newFolder"), onClick = { newFolder(state) }, enabled = listing != null)
            PaperMoreButton({
                listOf(
                    MenuEntry.Item(tr("remotePicker.home"), icon = "house") { scope.launch { state.load(listing?.home ?: "") } },
                    MenuEntry.Item(tr("mobile.picker.goTo"), icon = "text-cursor-input") { goTo(state) },
                    MenuEntry.Item(tr("remotePicker.showHidden"), icon = "eye", checked = state.showHidden) { state.showHidden = !state.showHidden },
                    MenuEntry.Item(tr("files.refresh"), icon = "refresh-cw") { scope.launch { state.load(listing?.path) } },
                )
            })
        },
        header = { Breadcrumbs(state) },
        bottomBar = {
            val chosen = if (state.files) state.selected else listing?.path
            Column(Modifier.fillMaxWidth().padding(horizontal = sp.lg, vertical = sp.md), verticalArrangement = Arrangement.spacedBy(sp.sm)) {
                if (chosen != null) {
                    Text(
                        Format.abbreviatePath(chosen),
                        style = PaperTheme.typography.monoSmall,
                        color = c.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                PaperButton(
                    confirmText ?: tr("remotePicker.choose"),
                    onClick = { chosen?.let(onChoose) },
                    kind = ButtonKind.Primary,
                    enabled = chosen != null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(c.bgContent)) {
            val list = state.visible
            when {
                state.loading && listing == null -> PaperSpinner(Modifier.align(Alignment.Center), tint = c.iconSecondary)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    state.error?.let { e -> item(key = "error") { PaperAlert(Tone.Danger, e, Modifier.padding(sp.lg)) } }
                    val parent = listing?.parent.orEmpty()
                    if (parent.isNotEmpty()) {
                        item(key = "..") {
                            PaperListRow("..", icon = "arrow-up", iconTint = c.iconSecondary, onClick = { scope.launch { state.load(parent) } })
                        }
                    }
                    if (list.isEmpty() && listing != null) {
                        item(key = "empty") {
                            Text(
                                tr("remotePicker.empty"),
                                style = PaperTheme.typography.body,
                                color = c.textSecondary,
                                modifier = Modifier.fillMaxWidth().padding(sp.huge),
                            )
                        }
                    }
                    items(list, key = { it.path }) { e -> EntryRow(state, e, onOpen = { scope.launch { state.load(e.path) } }, onChoose = onChoose) }
                }
            }
            if (state.loading && listing != null) {
                PaperSpinner(Modifier.align(Alignment.TopEnd).padding(sp.md), size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
            }
        }
    }
}

@Composable
private fun EntryRow(state: PickerState, e: FSBrowseEntry, onOpen: () -> Unit, onChoose: (String) -> Unit) {
    val c = PaperTheme.colors
    val selected = !e.dir && state.selected == e.path
    PaperListRow(
        e.name,
        modifier = Modifier.alpha(if (e.hidden) 0.6f else 1f),
        icon = if (e.dir) "folder" else FileIcon.builtinName(e.name),
        iconTint = if (e.dir) c.iconPrimary else c.iconSecondary,
        value = if (!e.dir) e.size?.let { Format.bytes(it) } else null,
        showChevron = e.dir,
        selected = selected,
        trailing = if (selected) ({ PaperIcon("check", size = PaperTheme.sizes.iconSmall, tint = c.accent) }) else null,
        onClick = { if (e.dir) onOpen() else state.selected = e.path },
        menu = {
            if (e.dir) {
                listOf(
                    MenuEntry.Item(tr("mobile.picker.open"), icon = "folder-open", onClick = onOpen),
                    MenuEntry.Item(if (state.files) tr("remotePicker.choose") else tr("mobile.picker.chooseThis"), icon = "check", enabled = !state.files) { onChoose(e.path) },
                )
            } else {
                listOf(MenuEntry.Item(tr("remotePicker.choose"), icon = "check") { onChoose(e.path) })
            }
        },
    )
}

/** 面包屑一行（可横向滚动，自动滚到最后一级）；Windows 服务器在最前面放盘符 */
@Composable
private fun Breadcrumbs(state: PickerState) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val scope = rememberCoroutineScope()
    val listing = state.listing ?: return
    val crumbs = breadcrumbs(listing.path, listing.home, state.path)
    val scroll = rememberScrollState()
    LaunchedEffect(listing.path) { scroll.scrollTo(scroll.maxValue) }
    Row(Modifier.fillMaxWidth().padding(start = sp.md, end = sp.md, bottom = sp.xs), verticalAlignment = Alignment.CenterVertically) {
        if (listing.roots.size > 1) {
            PaperMoreButton(
                { listing.roots.map { r -> MenuEntry.Item(r, icon = "hard-drive", checked = listing.path.startsWith(r, ignoreCase = true)) { scope.launch { state.load(r) } } } },
                icon = "hard-drive",
                contentDescription = tr("mobile.picker.drive"),
                tint = c.iconPrimary,
            )
        }
        Row(Modifier.weight(1f).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
            crumbs.forEachIndexed { i, (label, p) ->
                if (i > 0) PaperIcon("chevron-right", size = 14.dp, tint = c.iconSecondary)
                val last = i == crumbs.size - 1
                Box(
                    Modifier
                        .heightIn(min = PaperTheme.sizes.touchTarget)
                        .clip(RoundedCornerShape(PaperTheme.radius.md))
                        .then(if (last) Modifier else Modifier.clickable(role = Role.Button) { scope.launch { state.load(p) } })
                        .padding(horizontal = sp.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    if (label == "~") {
                        PaperIcon("house", size = PaperTheme.sizes.iconSmall, tint = if (last) c.textPrimary else c.textLink)
                    } else {
                        Text(
                            label,
                            style = if (last) PaperTheme.typography.bodyStrong else PaperTheme.typography.body,
                            color = if (last) c.textPrimary else c.textLink,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
    PaperDivider(subtle = true)
}

/** 新建文件夹（fs.mkdir），建好后进入它 */
private fun newFolder(state: PickerState) {
    val base = state.listing?.path ?: return
    Dialogs.input(title = tr("remotePicker.newFolder"), label = tr("common.name")) { name ->
        val n = name.trim()
        if (n.isEmpty()) return@input
        val created = AppModel.api().fsMkdir(state.path.join(base, n))
        state.load(created)
    }
}

/** 输入路径前往 */
private fun goTo(state: PickerState) {
    Dialogs.input(title = tr("mobile.picker.goTo").trimEnd('…', '.'), label = tr("remotePicker.path"), initial = state.listing?.path.orEmpty()) { p ->
        state.load(p.trim())
        state.error?.let { throw IllegalStateException(it) }
    }
}
