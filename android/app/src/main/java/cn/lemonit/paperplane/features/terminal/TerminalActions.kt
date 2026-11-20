package cn.lemonit.paperplane.features.terminal

import android.content.Context
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.TerminalRoute
import cn.lemonit.paperplane.kit.model.ProjectSummary
import cn.lemonit.paperplane.kit.model.TerminalInfo
import cn.lemonit.paperplane.kit.terminalBusy
import cn.lemonit.paperplane.kit.terminalClose
import cn.lemonit.paperplane.kit.terminalCreate
import cn.lemonit.paperplane.kit.terminalRename
import cn.lemonit.paperplane.kit.terminalRestart
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.i18n.tr

/** 终端的操作（对照 Mac TerminalPage.swift 里的 Actions 扩展） */
internal object TerminalActions {
    private val terminals get() = AppModel.terminals

    /**
     * 新建终端：起始目录是工程根目录（或相对它的 [cwd]），名字默认是工程名；建好后切过去。
     * 行列数先按屏幕估一个，终端页显示出来后会按实际区域改。
     */
    fun create(projectId: String, cwd: String? = null, open: Boolean = true) = AppModel.perform(tr("terminal.createFailed")) { api ->
        val (cols, rows) = estimateSize()
        val t = api.terminalCreate(cols, rows, projectId = projectId, cwd = cwd?.takeIf { it.isNotEmpty() })
        terminals.upsert(t)
        terminals.selected[t.workspaceId] = t.id
        if (open) open(t)
    }

    fun open(t: TerminalInfo) {
        terminals.selected[t.workspaceId] = t.id
        AppModel.nav.push(TerminalRoute(t.id))
    }

    fun rename(t: TerminalInfo) {
        Dialogs.input(title = tr("terminal.rename"), label = tr("terminal.name"), initial = t.name, confirmText = tr("common.save")) { name ->
            AppModel.api().terminalRename(t.id, name)
            terminals.list.firstOrNull { it.id == t.id }?.let { terminals.upsert(it.copy(name = name)) }
        }
    }

    fun restart(t: TerminalInfo) = AppModel.perform(tr("terminal.restartFailed")) { api ->
        terminals.upsert(api.terminalRestart(t.id))
    }

    /** 关闭终端：先确认；里面还有命令在跑（开发服务器等）时说明会一起结束 */
    fun close(t: TerminalInfo) = AppModel.perform { api ->
        val busy = if (t.exited) null else runCatching { api.terminalBusy(t.id) }.getOrNull()?.takeIf { it.busy }
        Dialogs.confirm(
            title = tr("terminal.close.title", t.name),
            message = busy?.let { tr("terminal.close.busy", it.process.ifEmpty { tr("terminal.aProcess") }) },
            confirmText = tr("terminal.close"),
            danger = true,
        ) {
            AppModel.api().terminalClose(t.id)
            removed(t.id)
        }
    }

    /** 终端已经关闭（内核里没了）：从列表去掉，关掉它的页面 */
    fun removed(id: String) {
        terminals.list = terminals.list.filter { it.id != id }
        AppModel.nav.removeWhere { (it is TerminalRoute && it.terminalId == id) || (it is TerminalHistoryRoute && it.terminalId == id) }
    }

    /** 长按菜单 */
    fun menu(t: TerminalInfo, includeOpen: Boolean = false): List<MenuEntry> = buildList {
        if (includeOpen) add(MenuEntry.Item(tr("mobile.terminal.open"), icon = "square-terminal") { open(t) })
        add(MenuEntry.Item(tr("terminal.rename"), icon = "pencil") { rename(t) })
        if (t.exited) add(MenuEntry.Item(tr("terminal.restart"), icon = "rotate-ccw") { restart(t) })
        add(MenuEntry.Item(tr("mobile.terminal.history"), icon = "scroll-text") { AppModel.nav.push(TerminalHistoryRoute(t.id)) })
        add(MenuEntry.Divider)
        add(MenuEntry.Item(tr("terminal.close"), icon = "trash-2", destructive = true) { close(t) })
    }

    /** 列表里的第二行：已退出时显示退出码；名字就是工程名时显示目录，否则显示所在工程 */
    fun subtitle(t: TerminalInfo, project: ProjectSummary?): String = when {
        t.exited -> tr("terminal.exited", t.exitCode)
        project != null && project.name != t.name -> project.name
        else -> Format.abbreviatePath(t.cwd)
    }

    // ---------------------------------------------------------------- 字号与尺寸

    private const val PREFS = "paperplane_terminal"
    private const val KEY_FONT = "fontSize"
    const val DEFAULT_FONT_SIZE = 13
    val FONT_SIZES = 9..22

    private fun prefs() = runCatching { AppModel.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()

    var fontSize: Int
        get() = (prefs()?.getInt(KEY_FONT, DEFAULT_FONT_SIZE) ?: DEFAULT_FONT_SIZE).coerceIn(FONT_SIZES)
        set(v) {
            prefs()?.edit()?.putInt(KEY_FONT, v.coerceIn(FONT_SIZES))?.apply()
        }

    /** 按屏幕估终端行列（网页显示后按实际区域重新算）：字宽约 0.6 字号，行高 1.2 字号 */
    fun estimateSize(): Pair<Int, Int> {
        val m = runCatching { AppModel.context.resources.displayMetrics }.getOrNull() ?: return 80 to 24
        val fs = fontSize.toFloat()
        val widthDp = m.widthPixels / m.density
        val heightDp = m.heightPixels / m.density
        val cols = ((widthDp - 24) / (fs * 0.6f)).toInt().coerceIn(20, 300)
        val rows = ((heightDp * 0.55f) / (fs * 1.2f)).toInt().coerceIn(5, 200)
        return cols to rows
    }
}
