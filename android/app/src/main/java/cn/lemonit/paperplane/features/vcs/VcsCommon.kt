package cn.lemonit.paperplane.features.vcs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.features.runs.TaskLog
import cn.lemonit.paperplane.kit.model.VCSRef
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperLink
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.i18n.I18n
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperColors
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 版本控制界面的公共小件：状态颜色与名称、路径、日期、状态字母、增删行数、引用徽标、带按钮的提示框、分组标题。
// 对照 Mac VCSCommon.swift。

object VcsStyle {
    fun color(status: String, c: PaperColors): Color = when (status) {
        "A" -> c.success
        "D" -> c.textTertiary
        "?" -> c.warning
        "U" -> c.danger
        "R", "C", "M", "T" -> c.info
        else -> c.textPrimary
    }

    fun title(status: String): String = when (status) {
        "A" -> tr("vcs.status.added")
        "D" -> tr("vcs.status.deleted")
        "?" -> tr("vcs.status.untracked")
        "U" -> tr("vcs.status.conflicted")
        "R" -> tr("vcs.status.renamed")
        "C" -> tr("vcs.status.copied")
        "T" -> tr("vcs.status.typeChanged")
        else -> tr("vcs.status.modified")
    }

    /** 冲突类型（插件给 UU/AA… 两字母，SDK 注释里是 both-modified…，两种都认） */
    fun conflictTitle(t: String?): String = when (t) {
        "UU", "both-modified" -> tr("vcs.conflict.bothModified")
        "AA", "both-added" -> tr("vcs.conflict.bothAdded")
        "DU", "deleted-by-us" -> tr("vcs.conflict.deletedByUs")
        "UD", "deleted-by-them" -> tr("vcs.conflict.deletedByThem")
        "AU", "added-by-us" -> tr("vcs.conflict.addedByUs")
        "UA", "added-by-them" -> tr("vcs.conflict.addedByThem")
        "DD", "both-deleted" -> tr("vcs.conflict.bothDeleted")
        else -> tr("vcs.conflict.unknown")
    }

    fun operationTitle(op: String?): String = when (op) {
        "merge" -> tr("vcs.op.merge")
        "rebase" -> tr("vcs.op.rebase")
        "cherry-pick" -> tr("vcs.op.cherryPick")
        "revert" -> tr("vcs.op.revert")
        "am" -> tr("vcs.op.am")
        else -> op.orEmpty()
    }

    fun shortHash(h: String?): String = h.orEmpty().take(8)
}

/** 仓库内相对路径（统一用 /） */
object VcsPath {
    fun name(p: String): String = p.trimEnd('/', '\\').split('/', '\\').lastOrNull().orEmpty()

    fun dir(p: String): String {
        val t = p.trimEnd('/')
        val i = t.lastIndexOf('/')
        return if (i < 0) "" else t.substring(0, i)
    }

    fun ext(p: String): String {
        val n = name(p)
        val i = n.lastIndexOf('.')
        return if (i <= 0) "" else n.substring(i + 1).lowercase()
    }

    private val images = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "ico", "heic")

    fun isImage(p: String): Boolean = ext(p) in images

    /** 仓库根（服务器上的绝对路径）+ 相对路径：按服务器的路径规则拼 */
    fun join(root: String, rel: String): String {
        var out = root
        for (part in rel.split('/').filter { it.isNotEmpty() }) out = AppModel.serverPath.join(out, part)
        return out
    }
}

/** 日期显示：一周内相对时间，否则日期时间 */
object VcsDate {
    private val absolute = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun short(d: Instant?, now: Instant = Instant.now()): String {
        if (d == null) return ""
        val secs = Duration.between(d, now).seconds
        val abs = kotlin.math.abs(secs)
        if (abs >= 7 * 86400) return full(d)
        val ago = secs >= 0
        return when {
            abs < 60 -> tr(if (ago) "vcs.date.agoSeconds" else "vcs.date.inSeconds", abs)
            abs < 3600 -> tr(if (ago) "vcs.date.agoMinutes" else "vcs.date.inMinutes", abs / 60)
            abs < 86400 -> tr(if (ago) "vcs.date.agoHours" else "vcs.date.inHours", abs / 3600)
            abs / 86400 == 1L -> tr(if (ago) "vcs.date.agoDay" else "vcs.date.inDay", 1)
            else -> tr(if (ago) "vcs.date.agoDays" else "vcs.date.inDays", abs / 86400)
        }
    }

    fun full(d: Instant?): String = d?.atZone(ZoneId.systemDefault())?.format(absolute).orEmpty()

    /** 当前界面语言是否中文（少数拼接处用） */
    val zh: Boolean get() = I18n.locale.value == I18n.ZH
}

/** 状态字母（M/A/D…），等宽着色；未跟踪显示 U */
@Composable
fun VcsStatusLetter(status: String, modifier: Modifier = Modifier) {
    Text(
        if (status == "?") "U" else status.take(1),
        style = PaperTheme.typography.mono,
        color = VcsStyle.color(status, PaperTheme.colors),
        modifier = modifier.width(14.dp),
        maxLines = 1,
    )
}

/** 增删行数（+3 −1） */
@Composable
fun VcsLineStats(additions: Int, deletions: Int) {
    val c = PaperTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs)) {
        if (additions > 0) Text("+$additions", style = PaperTheme.typography.monoSmall, color = c.diffAddedText, maxLines = 1)
        if (deletions > 0) Text("−$deletions", style = PaperTheme.typography.monoSmall, color = c.diffRemovedText, maxLines = 1)
    }
}

/** 领先 / 落后（↑2 ↓1） */
@Composable
fun VcsAheadBehind(ahead: Int, behind: Int) {
    val c = PaperTheme.colors
    if (ahead > 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaperIcon("arrow-up", size = 14.dp, tint = c.success)
            Text("$ahead", style = PaperTheme.typography.monoSmall, color = c.success)
        }
    }
    if (behind > 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaperIcon("arrow-down", size = 14.dp, tint = c.info)
            Text("$behind", style = PaperTheme.typography.monoSmall, color = c.info)
        }
    }
}

/** 引用徽标：HEAD / 本地 / 远程 / 标签 不同颜色 */
@Composable
fun VcsRefBadge(ref: VCSRef, current: Boolean = false) {
    val c = PaperTheme.colors
    val (icon, fg, bg) = when (ref.kind) {
        "head" -> Triple("circle-dot", c.accent, c.accentSubtle)
        "local" -> if (current) Triple("git-branch", c.accent, c.accentSubtle) else Triple("git-branch", c.success, c.successSubtle)
        "remote" -> Triple("cloud", c.info, c.infoSubtle)
        "tag" -> Triple("tag", c.warning, c.warningSubtle)
        else -> Triple("git-commit-horizontal", c.textSecondary, c.neutralSubtle)
    }
    Row(
        Modifier
            .height(20.dp)
            .background(bg, RoundedCornerShape(PaperTheme.radius.sm))
            .padding(horizontal = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaperIcon(icon, size = 12.dp, tint = fg)
        Text(ref.name, style = PaperTheme.typography.caption, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 带按钮的提示框（进行中操作横幅、失败提示）：语气色浅底 + 描边，标题、说明，按钮排在下面（手机上放不下一行时换行）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VcsToneBox(
    tone: Tone,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    onClose: (() -> Unit)? = null,
    actions: (@Composable FlowRowScope.() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val tc = PaperTheme.colors.tone(tone)
    val sp = PaperTheme.spacing
    val shape = RoundedCornerShape(PaperTheme.radius.lg)
    Column(
        modifier
            .fillMaxWidth()
            .background(tc.bg, shape)
            .border(PaperTheme.sizes.border, tc.border, shape)
            .padding(start = sp.lg, end = if (onClose != null) sp.xs else sp.lg, top = sp.md, bottom = sp.md),
        verticalArrangement = Arrangement.spacedBy(sp.sm),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(sp.md), verticalAlignment = Alignment.Top) {
            PaperIcon(
                when (tone) {
                    Tone.Danger -> "circle-alert"
                    Tone.Warning -> "triangle-alert"
                    Tone.Success -> "circle-check"
                    else -> "info"
                },
                size = PaperTheme.sizes.icon,
                tint = tc.fg,
                modifier = Modifier.padding(top = 1.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = PaperTheme.typography.bodyStrong, color = PaperTheme.colors.textPrimary)
                if (!message.isNullOrEmpty()) {
                    Text(message, style = PaperTheme.typography.small, color = PaperTheme.colors.textSecondary, maxLines = 10, overflow = TextOverflow.Ellipsis)
                }
            }
            if (onClose != null) {
                PaperIconButton("x", tr("common.close"), onClick = onClose, size = ButtonSize.Small, tint = PaperTheme.colors.iconSecondary)
            }
        }
        if (actions != null) {
            FlowRow(
                Modifier.padding(start = PaperTheme.sizes.icon + sp.md),
                horizontalArrangement = Arrangement.spacedBy(sp.sm),
                verticalArrangement = Arrangement.spacedBy(sp.sm),
                content = actions,
            )
        }
        footer?.invoke()
    }
}

/** 页内提示条（[VcsNotice]）：结论 + 下一步按钮 + 查看输出 */
@Composable
fun VcsNoticeBar(repo: VcsRepo, notice: VcsNotice, modifier: Modifier = Modifier) {
    var showOutput by remember(notice) { mutableStateOf(false) }
    VcsToneBox(
        notice.tone,
        notice.title,
        modifier,
        message = notice.message,
        onClose = { repo.notice = null },
        actions = if (notice.actions.isEmpty() && notice.taskId == null && notice.output.isNullOrEmpty()) null else {
            {
                for (a in notice.actions) {
                    PaperButton(
                        a.title,
                        onClick = a.run,
                        kind = when {
                            a.danger -> ButtonKind.Danger
                            a.primary -> ButtonKind.Primary
                            else -> ButtonKind.Secondary
                        },
                        size = ButtonSize.Small,
                    )
                }
                val tid = notice.taskId
                if (tid != null) {
                    PaperButton(tr("vcs.output"), onClick = { TaskLog.show(tid) }, kind = ButtonKind.Text, size = ButtonSize.Small)
                } else if (!notice.output.isNullOrEmpty()) {
                    PaperButton(
                        if (showOutput) tr("vcs.hideOutput") else tr("vcs.output"),
                        onClick = { showOutput = !showOutput },
                        kind = ButtonKind.Text,
                        size = ButtonSize.Small,
                    )
                }
            }
        },
        footer = if (!showOutput || notice.output.isNullOrEmpty()) null else {
            {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .background(PaperTheme.colors.bgCode, RoundedCornerShape(PaperTheme.radius.md))
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(PaperTheme.spacing.md),
                ) {
                    SelectionContainer { Text(notice.output.orEmpty(), style = PaperTheme.typography.monoSmall, color = PaperTheme.colors.textPrimary, softWrap = false) }
                }
            }
        },
    )
}

/** 正在进行的远程操作：转圈 + 标题 + 查看输出 */
@Composable
fun VcsActivityRow(repo: VcsRepo, modifier: Modifier = Modifier) {
    val a = repo.activity ?: return
    val c = PaperTheme.colors
    Row(
        modifier.fillMaxWidth().height(36.dp),
        horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = c.accent)
        Text(a.title, style = PaperTheme.typography.small, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        val t = repo.runningTask
        if (t != null) PaperLink(tr("vcs.output"), onClick = { TaskLog.show(t.id) })
    }
}

/** 页面顶部的提示区：进行中的远程操作 + 提示条（压栈的子页面也放一份，失败提示不会被盖住） */
@Composable
fun VcsRepoMessages(repo: VcsRepo, modifier: Modifier = Modifier) {
    val n = repo.notice
    if (repo.activity == null && n == null) return
    Column(modifier.fillMaxWidth().padding(horizontal = PaperTheme.spacing.lg, vertical = PaperTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm)) {
        VcsActivityRow(repo)
        if (n != null) VcsNoticeBar(repo, n)
    }
}

/** 列表里的分组标题：折叠箭头、标题、数量、右侧操作 */
@Composable
fun VcsGroupHeader(
    title: String,
    count: Int? = null,
    collapsed: Boolean? = null,
    onToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .background(c.bgSubtle)
            .then(if (onToggle != null) Modifier.clickable(role = Role.Button, onClick = onToggle) else Modifier)
            .padding(start = sp.lg, end = sp.xs),
        horizontalArrangement = Arrangement.spacedBy(sp.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (collapsed != null) PaperIcon(if (collapsed) "chevron-right" else "chevron-down", size = 16.dp, tint = c.iconSecondary)
        Text(title, style = PaperTheme.typography.label, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) Text("$count", style = PaperTheme.typography.caption, color = c.textTertiary)
        Box(Modifier.weight(1f))
        if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** 删除线文字（已删除文件名） */
fun strike(deleted: Boolean): TextDecoration? = if (deleted) TextDecoration.LineThrough else null
