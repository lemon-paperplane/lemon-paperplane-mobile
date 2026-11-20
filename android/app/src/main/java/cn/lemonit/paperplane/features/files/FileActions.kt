package cn.lemonit.paperplane.features.files

import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.EditorRoute
import cn.lemonit.paperplane.features.terminal.TerminalActions
import cn.lemonit.paperplane.kit.fsCreate
import cn.lemonit.paperplane.kit.fsDelete
import cn.lemonit.paperplane.kit.fsRename
import cn.lemonit.paperplane.kit.launchAddFile
import cn.lemonit.paperplane.kit.model.FSEntry
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.tr
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 文件列表的操作：新建、重命名、删除（移到废纸篓）、复制路径、加到启动、在终端中打开、编辑。
 * 对照 Mac Features/Files/FileActions（「在访达中显示」在手机上没有意义，换成在服务器上开一个终端）。
 */
internal object FileActions {
    /** 所在目录（相对路径）；根下的条目为 "" */
    fun parent(rel: String): String = if ('/' in rel) rel.substringBeforeLast('/') else ""

    fun join(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"

    /** 相对工程根的路径 → 服务器上的绝对路径（连 Windows 服务器时是 C:\…） */
    fun absolute(rel: String, projectPath: String): String = AppModel.serverPath.absolute(rel, projectPath)

    /** 名称里不能有的东西（内核也会拒绝，这里先拦住常见的错） */
    fun invalidName(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        if (n == "." || n == ".." || n.split('/').any { it == ".." }) return tr("editor.tree.nameInvalid")
        if ('\u0000' in n || n.startsWith("/")) return tr("editor.tree.nameInvalid")
        return null
    }

    fun edit(projectId: String, projectPath: String, rel: String, line: Int? = null) {
        AppModel.nav.push(EditorRoute(absolute(rel, projectPath), projectId, line))
    }

    /** 新建文件 / 文件夹；名字可以带子目录（a/b.txt）。新建文件后直接打开编辑 */
    fun newItem(projectId: String, projectPath: String, dir: String, folder: Boolean, reload: suspend (String) -> Unit) {
        val title = tr(if (folder) "files.newFolder" else "files.newFile").trimEnd('…', '.')
        Dialogs.input(
            title = title,
            label = tr(if (folder) "editor.tree.folderName" else "editor.tree.fileName"),
            confirmText = tr("common.create"),
            validate = ::invalidName,
        ) { name ->
            val rel = join(dir, name.trim().trim('/'))
            val r = AppModel.api().fsCreate(projectId, rel, folder)
            reload(dir)
            if (!folder) edit(projectId, projectPath, r.path.ifEmpty { rel })
        }
    }

    fun rename(projectId: String, entry: FSEntry, reload: suspend (String) -> Unit) {
        Dialogs.input(
            title = tr("files.rename").trimEnd('…', '.'),
            label = tr("common.name"),
            initial = entry.name,
            confirmText = tr("common.save"),
            validate = { n -> if ('/' in n) tr("editor.tree.nameInvalid") else invalidName(n) },
        ) { name ->
            val dir = parent(entry.path)
            AppModel.api().fsRename(projectId, entry.path, join(dir, name.trim()))
            reload(dir)
        }
    }

    fun delete(projectId: String, entry: FSEntry, reload: suspend (String) -> Unit) {
        Dialogs.confirm(
            title = tr("files.delete.title", entry.name),
            message = tr("files.delete.message"),
            confirmText = tr("files.delete"),
            danger = true,
        ) {
            AppModel.api().fsDelete(projectId, listOf(entry.path))
            reload(parent(entry.path))
            Toasts.success(tr("files.deleted"))
        }
    }

    /** 加到工作区的「启动」面板（脚本、可执行程序） */
    fun launchAddFile(projectId: String, rel: String) = AppModel.perform { api ->
        val r = api.launchAddFile(projectId, rel)
        Toasts.success(if (r.created) tr("launch.fileAdded", r.name) else tr("launch.fileAlready", r.name))
    }

    /** 长按菜单 */
    fun menu(
        projectId: String,
        projectPath: String,
        entry: FSEntry,
        reload: suspend (String) -> Unit,
    ): List<MenuEntry> {
        val dirForNew = if (entry.dir) entry.path else parent(entry.path)
        val out = mutableListOf<MenuEntry>()
        if (!entry.dir) {
            out += MenuEntry.Item(tr("files.edit"), icon = "file-pen") { edit(projectId, projectPath, entry.path) }
            if (entry.isLaunchable) out += MenuEntry.Item(tr("launch.addFile"), icon = "rocket") { launchAddFile(projectId, entry.path) }
            out += MenuEntry.Divider
        }
        out += MenuEntry.Item(tr("files.newFile").trimEnd('…', '.'), icon = "file-plus") { newItem(projectId, projectPath, dirForNew, false, reload) }
        out += MenuEntry.Item(tr("files.newFolder").trimEnd('…', '.'), icon = "folder-plus") { newItem(projectId, projectPath, dirForNew, true, reload) }
        out += MenuEntry.Divider
        out += MenuEntry.Item(tr("files.rename").trimEnd('…', '.'), icon = "pencil") { rename(projectId, entry, reload) }
        out += MenuEntry.Item(tr("files.delete"), icon = "trash-2", destructive = true) { delete(projectId, entry, reload) }
        out += MenuEntry.Divider
        out += MenuEntry.Item(tr("files.openTerminal"), icon = "square-terminal") {
            TerminalActions.create(projectId = projectId, cwd = if (entry.dir) entry.path else parent(entry.path), open = true)
        }
        out += MenuEntry.Item(tr("files.copyPath"), icon = "copy") { Actions.copy(absolute(entry.path, projectPath)) }
        out += MenuEntry.Item(tr("files.copyRelativePath"), icon = "clipboard") { Actions.copy(entry.path) }
        return out
    }

    // ---------------------------------------------------------------- 显示

    private val dayTime = DateTimeFormatter.ofPattern("HH:mm")
    private val fullDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** 修改时间：今天只显示时分，否则年月日时分（RFC3339Nano，带时区偏移） */
    fun mtime(raw: String?, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String? {
        if (raw.isNullOrEmpty()) return null
        val instant = runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrElse { runCatching { Instant.parse(raw) }.getOrNull() } ?: return null
        val t = instant.atZone(zone)
        return if (t.toLocalDate() == today) t.format(dayTime) else t.format(fullDate)
    }
}
