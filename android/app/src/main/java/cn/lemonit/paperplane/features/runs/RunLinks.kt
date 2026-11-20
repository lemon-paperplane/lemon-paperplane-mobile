package cn.lemonit.paperplane.features.runs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.BrowserRoute
import cn.lemonit.paperplane.kit.model.RemoteLinks
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.kit.util.ServerURL
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperLongPressArea
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 运行地址（对照 Mac Features/Servers/RunURLLinks.swift）。手机总是连着远程服务器：
// 运行地址是服务器上的 localhost:端口 时，按工程里这个运行配置的 remoteLinks 给出两种打开方式：
//   转发：把服务器端口经加密隧道转到手机回环端口，在 App 内置浏览器里打开（切到后台连接会断，所以不交给系统浏览器）；
//   直连：把 localhost 换成服务器主机名（服务要监听 0.0.0.0），App 内打开，长按可交给系统浏览器。
// 其他主机名的地址原样打开。

/** 一种打开方式 */
enum class RunLinkKind { FORWARD, DIRECT, PLAIN }

/**
 * 运行地址的一种打开方式。
 * @param url 运行给出的原始地址（服务器上的）
 * @param target 手机上实际打开的地址：直连时是换成服务器主机的地址，转发时是原始地址（端口在打开时才分配）
 */
data class RunLinkTarget(val kind: RunLinkKind, val label: String, val icon: String, val url: String, val target: String)

object RunLinkActions {
    /**
     * 这个运行地址在手机上有哪些打开方式（按显示顺序）。
     * @param serverHost 当前服务器主机名（默认取当前服务器）
     * @param links 这个运行配置的远程打开方式（默认取工程摘要里的设置）
     */
    fun targets(
        url: String,
        projectId: String?,
        configId: String?,
        serverHost: String? = AppModel.currentServer?.host,
        links: RemoteLinks = remoteLinks(projectId, configId),
    ): List<RunLinkTarget> {
        val port = ServerURL.loopbackPort(url)
            ?: return listOf(RunLinkTarget(RunLinkKind.PLAIN, Format.shortURL(url), "external-link", url, url))
        val out = ArrayList<RunLinkTarget>(2)
        if (links.forward || serverHost.isNullOrEmpty()) {
            out += RunLinkTarget(RunLinkKind.FORWARD, "localhost:$port", "arrow-left-right", url, url)
        }
        if (links.direct && !serverHost.isNullOrEmpty()) {
            val hostLabel = if (':' in serverHost && !serverHost.startsWith("[")) "[$serverHost]" else serverHost
            out += RunLinkTarget(RunLinkKind.DIRECT, "$hostLabel:$port", "server", url, ServerURL.direct(url, serverHost))
        }
        return out
    }

    /** 工程摘要里这个运行配置的远程打开方式（没有记录 = 两种都要） */
    fun remoteLinks(projectId: String?, configId: String?): RemoteLinks {
        if (projectId == null || configId == null) return RemoteLinks.BOTH
        return AppModel.data.projects[projectId]?.remoteLinks(configId) ?: RemoteLinks.BOTH
    }

    /** 按选定的方式打开 */
    fun open(t: RunLinkTarget, runId: String? = null) {
        when (t.kind) {
            RunLinkKind.FORWARD -> openForwarded(t.url, runId)
            RunLinkKind.DIRECT, RunLinkKind.PLAIN -> AppModel.nav.push(BrowserRoute(url = t.target, title = Format.shortURL(t.target)))
        }
    }

    /** 首选方式打开（日志里的地址等没有单独按钮的地方）：能转发就转发，否则直连 */
    fun openDefault(url: String, projectId: String? = null, configId: String? = null, runId: String? = null) {
        targets(url, projectId, configId).firstOrNull()?.let { open(it, runId) }
    }

    /**
     * 转发后在 App 内置浏览器打开：先把服务器端口转到手机回环端口（优先同一个端口号），
     * 再打开 BrowserRoute(url = 手机上的地址, forwardPort = 服务器端口)；离开浏览器页后由浏览器页决定是否关掉转发。
     */
    fun openForwarded(url: String, runId: String? = null) {
        val port = ServerURL.loopbackPort(url)
        if (port == null) {
            AppModel.nav.push(BrowserRoute(url = url, title = Format.shortURL(url)))
            return
        }
        AppModel.scope.launch {
            try {
                // 取转发器在主线程（AppModel 的状态只在主线程改），绑定本机端口放到 IO 线程
                val forwarder = AppModel.forwarder()
                val f = withContext(Dispatchers.IO) { forwarder.forward(port) }
                AppModel.nav.push(
                    BrowserRoute(url = ServerURL.forwarded(url, f.localPort), title = Format.shortURL(url), forwardPort = port, runId = runId),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toasts.error(e, tr("remote.forward.failed"))
            }
        }
    }

    /** 长按菜单：各种打开方式、用系统浏览器打开（转发地址除外）、复制地址 */
    fun menuEntries(url: String, projectId: String?, configId: String?, runId: String? = null): List<MenuEntry> {
        val ts = targets(url, projectId, configId)
        val out = ArrayList<MenuEntry>()
        for (t in ts) {
            val title = when (t.kind) {
                RunLinkKind.FORWARD -> tr("remote.link.forward")
                RunLinkKind.DIRECT -> tr("remote.link.direct")
                RunLinkKind.PLAIN -> tr("mobile.links.openInApp")
            }
            out += MenuEntry.Item(title, icon = t.icon) { open(t, runId) }
        }
        val external = ts.firstOrNull { it.kind != RunLinkKind.FORWARD }
        if (external != null) {
            out += MenuEntry.Item(tr("mobile.links.openExternal"), icon = "square-arrow-out-up-right") { Actions.openExternal(external.target) }
        }
        out += MenuEntry.Divider
        val copyUrl = external?.target ?: url
        out += MenuEntry.Item(tr("mobile.links.copy"), icon = "copy") { Actions.copy(copyUrl) }
        return out
    }
}

/**
 * 运行地址的链接（工程卡片、运行中、运行页、工程详情共用）：按工程设置显示「⇄ localhost:端口」（转发到手机）和 / 或
 * 「服务器主机:端口」（直连）；不是 localhost 的地址显示一个普通链接。点按打开，长按出菜单（各种打开方式、系统浏览器、复制）。
 *
 * ```
 * RunLinks(run.urls.first(), run.projectId, run.configId, runId = run.id)
 * RunLinks(url, p.id, cfgId, single = true)   // 地方小时只显示首选的一个
 * ```
 *
 * @param single 只显示首选的一种（其余在长按菜单里）
 * @param maxLabelWidth 每个链接文字的最大宽度（超出中间省略）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunLinks(
    url: String,
    projectId: String?,
    configId: String?,
    modifier: Modifier = Modifier,
    runId: String? = null,
    single: Boolean = false,
    maxLabelWidth: Dp = 200.dp,
) {
    val targets = RunLinkActions.targets(url, projectId, configId).let { if (single) it.take(1) else it }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs), verticalArrangement = Arrangement.Center) {
        for (t in targets) {
            RunLinkChip(t, maxLabelWidth, onClick = { RunLinkActions.open(t, runId) }, menu = { RunLinkActions.menuEntries(url, projectId, configId, runId) })
        }
    }
}

/** 一个链接：图标 + 等宽地址，44dp 高的点按区域 */
@Composable
internal fun RunLinkChip(t: RunLinkTarget, maxLabelWidth: Dp, onClick: () -> Unit, menu: (() -> List<MenuEntry>)?) {
    val c = PaperTheme.colors
    PaperLongPressArea(
        onClick = onClick,
        menu = menu,
        modifier = Modifier.clip(RoundedCornerShape(PaperTheme.radius.md)),
    ) {
        Row(
            Modifier.heightIn(min = PaperTheme.sizes.touchTarget).padding(horizontal = PaperTheme.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaperIcon(t.icon, size = 14.dp, tint = c.textLink)
            Text(
                t.label,
                style = PaperTheme.typography.monoSmall,
                color = c.textLink,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.widthIn(max = maxLabelWidth),
            )
        }
    }
}
