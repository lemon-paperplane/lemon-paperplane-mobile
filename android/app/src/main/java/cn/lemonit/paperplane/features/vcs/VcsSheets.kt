package cn.lemonit.paperplane.features.vcs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.kit.ApiException
import cn.lemonit.paperplane.kit.model.VCSBranch
import cn.lemonit.paperplane.kit.model.VCSBranchCreateArgs
import cn.lemonit.paperplane.kit.model.VCSCheckoutArgs
import cn.lemonit.paperplane.kit.model.VCSCommit
import cn.lemonit.paperplane.kit.model.VCSConfigSetArgs
import cn.lemonit.paperplane.kit.model.VCSFileChange
import cn.lemonit.paperplane.kit.model.VCSOp
import cn.lemonit.paperplane.kit.model.VCSPullArgs
import cn.lemonit.paperplane.kit.model.VCSPushArgs
import cn.lemonit.paperplane.kit.model.VCSPushPreview
import cn.lemonit.paperplane.kit.model.VCSReason
import cn.lemonit.paperplane.kit.model.VCSRemote
import cn.lemonit.paperplane.kit.model.VCSSetUpstreamArgs
import cn.lemonit.paperplane.kit.model.VCSStashSaveArgs
import cn.lemonit.paperplane.kit.model.VCSTagCreateArgs
import cn.lemonit.paperplane.kit.vcsBranchCreate
import cn.lemonit.paperplane.kit.vcsCheckout
import cn.lemonit.paperplane.kit.vcsConfigGet
import cn.lemonit.paperplane.kit.vcsConfigSet
import cn.lemonit.paperplane.kit.vcsDiscard
import cn.lemonit.paperplane.kit.vcsPushPreview
import cn.lemonit.paperplane.kit.vcsRemoteSet
import cn.lemonit.paperplane.kit.vcsReset
import cn.lemonit.paperplane.kit.vcsReword
import cn.lemonit.paperplane.kit.vcsSetUpstream
import cn.lemonit.paperplane.kit.vcsShow
import cn.lemonit.paperplane.kit.vcsStashSave
import cn.lemonit.paperplane.kit.vcsTagCreate
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperBadge
import cn.lemonit.paperplane.ui.components.PaperBottomSheet
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperCheckbox
import cn.lemonit.paperplane.ui.components.PaperDivider
import cn.lemonit.paperplane.ui.components.PaperInfoButton
import cn.lemonit.paperplane.ui.components.PaperLongPressArea
import cn.lemonit.paperplane.ui.components.PaperRadio
import cn.lemonit.paperplane.ui.components.PaperSearchField
import cn.lemonit.paperplane.ui.components.PaperSegmented
import cn.lemonit.paperplane.ui.components.PaperSelect
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.components.PaperTextArea
import cn.lemonit.paperplane.ui.components.PaperTextField
import cn.lemonit.paperplane.ui.components.SelectOption
import cn.lemonit.paperplane.ui.components.normalizeMenu
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// 版本控制的底部面板（手机上代替桌面的对话框）：身份、丢弃、拉取、推送（含推送预览）、设置上游、远程、储藏、
// 新建分支 / 标签、重置、修改提交信息、快速切换分支、菜单面板。对照 Mac 各 *Dialog。

/**
 * 表单面板外壳：内容 + 出错提示 + 取消 / 确定。onSubmit 执行中确定按钮转圈；返回后收起；抛错时在面板里显示错误。
 */
@Composable
internal fun VcsFormSheet(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    danger: Boolean = false,
    enabled: Boolean = true,
    icon: String? = null,
    onSubmit: suspend () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    PaperBottomSheet(onDismiss = onDismiss, title = title, showClose = false) { dismiss ->
        content()
        error?.let { PaperAlert(Tone.Danger, it) }
        Row(Modifier.fillMaxWidth().padding(top = PaperTheme.spacing.xs), horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md)) {
            PaperButton(tr("common.cancel"), onClick = { dismiss() }, modifier = Modifier.weight(1f))
            PaperButton(
                confirmText,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            onSubmit()
                            dismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: tr("common.failed")
                        } finally {
                            busy = false
                        }
                    }
                },
                kind = if (danger) ButtonKind.Danger else ButtonKind.Primary,
                icon = icon,
                enabled = enabled,
                loading = busy,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 字段名 + 可选 i 说明（面板里的小标题） */
@Composable
internal fun VcsFieldLabel(text: String, info: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = PaperTheme.typography.label, color = PaperTheme.colors.textSecondary)
        if (info != null) PaperInfoButton(info)
    }
}

/** 复选框 + 可选 i 说明 */
@Composable
internal fun VcsCheckRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String, info: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PaperCheckbox(checked, onCheckedChange = onChange, label = label)
        if (info != null) PaperInfoButton(info)
    }
}

object VcsSheets {
    private fun ensureOk(r: cn.lemonit.paperplane.kit.model.VCSOpResult) {
        if (!r.ok) throw ApiException(ApiException.USER_ERROR, r.message ?: tr("common.failed"))
    }

    // ---------------------------------------------------------------- 菜单面板

    /** 把一组菜单项放进底部面板（点按打开的操作列表、二级菜单） */
    fun menu(title: String?, entries: List<MenuEntry>) {
        Dialogs.present { dismiss ->
            PaperBottomSheet(onDismiss = dismiss, title = title, edgeToEdge = true) { close ->
                for (e in normalizeMenu(entries)) {
                    when (e) {
                        is MenuEntry.Divider -> PaperDivider(subtle = true, modifier = Modifier.padding(vertical = PaperTheme.spacing.xs))
                        is MenuEntry.Header -> Text(
                            e.title,
                            style = PaperTheme.typography.caption,
                            color = PaperTheme.colors.textTertiary,
                            modifier = Modifier.padding(horizontal = PaperTheme.spacing.xl, vertical = PaperTheme.spacing.sm),
                        )
                        is MenuEntry.Item -> MenuSheetRow(e) {
                            close()
                            e.onClick()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MenuSheetRow(item: MenuEntry.Item, onClick: () -> Unit) {
        val c = PaperTheme.colors
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = PaperTheme.sizes.rowHeight)
                .clickable(enabled = item.enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = PaperTheme.spacing.xl),
            horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(PaperTheme.sizes.icon), contentAlignment = Alignment.Center) {
                when {
                    item.checked -> PaperIcon("check", size = PaperTheme.sizes.iconSmall, tint = c.accent)
                    item.icon != null -> PaperIcon(item.icon, tint = if (item.destructive) c.danger else c.iconPrimary)
                }
            }
            Text(
                item.title,
                style = PaperTheme.typography.body,
                color = when {
                    !item.enabled -> c.textDisabled
                    item.destructive -> c.danger
                    else -> c.textPrimary
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    // ---------------------------------------------------------------- 身份

    /** 设置 user.name / user.email（本仓库或全局） */
    fun identity(repo: VcsRepo, missing: List<String>, then: () -> Unit) {
        Dialogs.present("vcs-identity") { dismiss ->
            var name by remember { mutableStateOf("") }
            var email by remember { mutableStateOf("") }
            var global by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                runCatching { repo.api().vcsConfigGet(repo.projectId, repo.root, listOf("user.name", "user.email")) }.getOrNull()?.let {
                    if (name.isEmpty()) name = it.values["user.name"].orEmpty()
                    if (email.isEmpty()) email = it.values["user.email"].orEmpty()
                }
            }
            VcsFormSheet(
                tr("vcs.identity.title"),
                dismiss,
                confirmText = tr("vcs.identity.setAndContinue"),
                enabled = name.isNotBlank() && email.contains('@'),
                onSubmit = {
                    val api = repo.api()
                    for ((k, v) in listOf("user.name" to name.trim(), "user.email" to email.trim())) {
                        ensureOk(api.vcsConfigSet(repo.projectId, repo.root, VCSConfigSetArgs(k, v, global.takeIf { it })))
                    }
                    then()
                },
            ) {
                VcsFieldLabel(tr("vcs.identity.missing"), tr("vcs.identity.info"))
                PaperTextField(name, { name = it }, label = tr("vcs.identity.name"), error = if (missing.contains("user.name") && name.isBlank()) "" else null)
                PaperTextField(email, { email = it }, label = tr("vcs.identity.email"), keyboardType = KeyboardType.Email, mono = true)
                PaperSegmented(listOf(true to tr("vcs.identity.global"), false to tr("vcs.identity.local")), global, { global = it }, fill = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    // ---------------------------------------------------------------- 丢弃

    /** mode：null 恢复到 HEAD；"unstaged" 只丢弃未暂存部分 */
    fun discard(repo: VcsRepo, files: List<VCSFileChange>, mode: String?, onDone: (() -> Unit)? = null) {
        if (files.isEmpty()) return
        Dialogs.present("vcs-discard") { dismiss ->
            var keepNew by remember { mutableStateOf(false) }
            val newFiles = files.filter { it.isUntracked || it.status == "A" }
            VcsFormSheet(
                tr("vcs.discard.title", files.size),
                dismiss,
                confirmText = tr("vcs.discard"),
                danger = true,
                onSubmit = {
                    repo.runThrowing(tr("vcs.discard"), quiet = true) { api ->
                        api.vcsDiscard(repo.projectId, repo.root, VcsActions.discardArgs(files.map { it.path }, mode, keepNew))
                    }
                    onDone?.invoke()
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (f in files.take(8)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            VcsStatusLetter(f.status)
                            Text(f.path, style = PaperTheme.typography.small, color = PaperTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        }
                    }
                    if (files.size > 8) Text("…", color = PaperTheme.colors.textTertiary)
                }
                if (newFiles.isNotEmpty() && mode != "unstaged") {
                    PaperAlert(
                        if (keepNew) Tone.Info else Tone.Warning,
                        if (keepNew) tr("vcs.discard.keepNewNote", newFiles.size) else tr("vcs.discard.deleteNew", newFiles.size),
                    )
                    PaperCheckbox(keepNew, { keepNew = it }, label = tr("vcs.discard.keepNew"))
                }
            }
        }
    }

    /** 加入 .gitignore：精确路径 / 同扩展名 / 所在目录 / 只在本机忽略 */
    fun ignore(repo: VcsRepo, files: List<VCSFileChange>) {
        val exact = files.map { "/" + it.path }
        val entries = buildList<MenuEntry> {
            add(MenuEntry.Item(if (exact.size == 1) exact[0] else tr("vcs.ignore.files", exact.size), "file-text") { VcsActions.ignore(repo, exact, null) })
            val exts = files.map { VcsPath.ext(it.path) }.filter { it.isNotEmpty() }.toSet()
            if (exts.size == 1) add(MenuEntry.Item("*." + exts.first(), "file-text") { VcsActions.ignore(repo, listOf("*." + exts.first()), null) })
            val dirs = files.map { VcsPath.dir(it.path) }.filter { it.isNotEmpty() }.toSet()
            if (dirs.size == 1) add(MenuEntry.Item("/" + dirs.first() + "/", "folder") { VcsActions.ignore(repo, listOf("/" + dirs.first() + "/"), null) })
            add(MenuEntry.Divider)
            add(MenuEntry.Item(tr("vcs.ignore.exclude"), "eye-off") { VcsActions.ignore(repo, exact, "exclude") })
        }
        menu(tr("vcs.ignore"), entries)
    }

    // ---------------------------------------------------------------- 拉取

    fun pull(repo: VcsRepo) {
        Dialogs.present("vcs-pull") { dismiss ->
            var target by remember { mutableStateOf("") }
            var mode by remember { mutableStateOf(VcsPrefs.pullMode) }
            var autostash by remember { mutableStateOf(VcsPrefs.pullAutostash) }
            LaunchedEffect(Unit) {
                if (repo.branches == null) repo.loadBranches()
                if (target.isEmpty()) target = defaultPullTarget(repo)
            }
            val remotes = repo.branches?.remote.orEmpty()
            VcsFormSheet(
                tr("vcs.pull.title", repo.headTitle),
                dismiss,
                confirmText = tr("vcs.pull"),
                icon = "arrow-down-to-line",
                enabled = target.isNotEmpty(),
                onSubmit = {
                    VcsPrefs.pullMode = mode
                    VcsPrefs.pullAutostash = autostash
                    var args = VCSPullArgs(mode = mode, autostash = autostash.takeIf { it })
                    val b = remotes.firstOrNull { it.name == target }
                    if (target != repo.status?.upstream && b != null) {
                        val remote = b.remote ?: target.substringBefore('/')
                        args = args.copy(remote = remote, branch = target.removePrefix("$remote/"))
                    }
                    VcsActions.pull(repo, args)
                },
            ) {
                if (repo.branches == null) {
                    PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = PaperTheme.colors.iconSecondary)
                } else if (remotes.isEmpty()) {
                    VcsToneBox(Tone.Warning, tr("vcs.pull.noRemoteBranches"), actions = {
                        PaperButton(tr("vcs.remote.add").plain(), onClick = {
                            dismiss()
                            remoteEdit(repo, null)
                        }, size = ButtonSize.Small)
                    })
                } else {
                    PaperSelect(
                        options = remotes.map { SelectOption(it.name, it.name, "cloud") },
                        selected = target,
                        onSelect = { target = it },
                        label = tr("vcs.pull.from"),
                        title = tr("vcs.pull.chooseBranch"),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                VcsFieldLabel(tr("vcs.pull.mode"), tr("vcs.pull.modeInfo"))
                PaperSegmented(
                    listOf("merge" to tr("vcs.pull.merge"), "rebase" to tr("vcs.pull.rebase"), "ff-only" to tr("vcs.pull.ffOnly")),
                    mode, { mode = it }, fill = true, modifier = Modifier.fillMaxWidth(),
                )
                VcsCheckRow(autostash, { autostash = it }, tr("vcs.pull.autostash"), tr("vcs.pull.autostashInfo"))
            }
        }
    }

    private fun defaultPullTarget(repo: VcsRepo): String {
        val remotes = repo.branches?.remote.orEmpty()
        val s = repo.status
        val up = s?.upstream
        if (up != null && !s.upstreamGone && remotes.any { it.name == up }) return up
        val cur = repo.currentBranch
        if (cur != null) remotes.firstOrNull { it.name.endsWith("/$cur") }?.let { return it.name }
        return remotes.firstOrNull()?.name.orEmpty()
    }

    // ---------------------------------------------------------------- 推送

    fun push(repo: VcsRepo, branch: String? = null) {
        Dialogs.present("vcs-push") { dismiss -> PushSheet(repo, branch, dismiss) }
    }

    @Composable
    private fun PushSheet(repo: VcsRepo, branch: String?, onDismiss: () -> Unit) {
        val c = PaperTheme.colors
        val sp = PaperTheme.spacing
        var remote by remember { mutableStateOf("") }
        var preview by remember { mutableStateOf<VCSPushPreview?>(null) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var force by remember { mutableStateOf(false) }
        var tags by remember { mutableStateOf(false) }
        var tagsMode by remember { mutableStateOf("follow") }
        var setUpstream by remember { mutableStateOf(true) }
        var noVerify by remember { mutableStateOf(false) }
        var reloadKey by remember { mutableStateOf(0) }

        LaunchedEffect(reloadKey) {
            repo.loadRemotes()
            if (remote.isEmpty()) {
                val up = repo.status?.upstream?.let { u -> repo.remotes.firstOrNull { u.startsWith(it.name + "/") }?.name }
                remote = up ?: repo.remotes.firstOrNull { it.name == "origin" }?.name ?: repo.remotes.firstOrNull()?.name.orEmpty()
            }
        }
        LaunchedEffect(remote, reloadKey) {
            loading = true
            try {
                val p = repo.api().vcsPushPreview(repo.projectId, repo.root, VCSPushArgs(remote = remote.ifEmpty { null }, branch = branch))
                preview = p
                if (remote.isEmpty()) remote = p.remote
                setUpstream = p.setUpstream || p.newBranch
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: tr("common.failed")
                preview = null
            }
            loading = false
        }
        val p = preview
        val offerUpstream = p?.newBranch == true || p?.setUpstream == true
        VcsFormSheet(
            tr("vcs.push.title", branch ?: repo.headTitle),
            onDismiss,
            confirmText = if (force) tr("vcs.push.force") else tr("vcs.push"),
            danger = force,
            icon = "arrow-up",
            enabled = p != null && remote.isNotEmpty(),
            onSubmit = {
                var args = VCSPushArgs(remote = remote.ifEmpty { null }, branch = branch)
                if (offerUpstream && setUpstream) args = args.copy(setUpstream = true)
                if (tags) args = args.copy(tags = true, tagsMode = tagsMode)
                if (noVerify) args = args.copy(noVerify = true)
                if (force) VcsActions.confirmForce(repo, args.copy(force = true)) else VcsActions.push(repo, args)
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(sp.sm), verticalAlignment = Alignment.CenterVertically) {
                PaperIcon("git-branch", size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
                Text(p?.branch?.ifEmpty { null } ?: branch ?: repo.headTitle, style = PaperTheme.typography.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
                PaperIcon("arrow-right", size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
                Text(
                    remote + (p?.remoteRef?.removePrefix("refs/heads/")?.let { ":$it" } ?: ""),
                    style = PaperTheme.typography.bodyStrong,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (p?.newBranch == true) PaperBadge(tr("vcs.push.newBranch"), tone = Tone.Success)
                if (loading) PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
            }
            if (repo.remotes.size > 1) {
                PaperSelect(
                    options = repo.remotes.map { SelectOption(it.name, it.name, "cloud") },
                    selected = remote,
                    onSelect = { remote = it },
                    title = tr("vcs.remote.name"),
                    size = ButtonSize.Small,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val err = error
            if (err != null) {
                VcsToneBox(Tone.Danger, err, actions = if (repo.remotesLoaded && repo.remotes.isEmpty()) {
                    {
                        PaperButton(tr("vcs.remote.add").plain(), onClick = { remoteEdit(repo, null) { reloadKey++ } }, icon = "plus", size = ButtonSize.Small)
                    }
                } else null)
            } else if (p != null) {
                if (p.behind > 0) {
                    VcsToneBox(Tone.Warning, tr("vcs.push.behind", p.behind), actions = {
                        PaperButton(tr("vcs.pull.ellipsis").plain(), onClick = {
                            onDismiss()
                            pull(repo)
                        }, size = ButtonSize.Small)
                    })
                }
                if (p.commits.isEmpty()) {
                    Text(if (p.newBranch) tr("vcs.push.newBranchNoCommits") else tr("vcs.push.nothing"), style = PaperTheme.typography.body, color = c.textSecondary)
                } else {
                    Text(tr("vcs.push.commits", p.commits.size) + if (p.truncated) "+" else "", style = PaperTheme.typography.label, color = c.textSecondary)
                    Column(Modifier.fillMaxWidth().background(c.bgSurface, androidx.compose.foundation.shape.RoundedCornerShape(PaperTheme.radius.lg))) {
                        for (cm in p.commits.take(30)) {
                            Row(
                                Modifier.fillMaxWidth().height(40.dp).padding(horizontal = sp.md),
                                horizontalArrangement = Arrangement.spacedBy(sp.md),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(cm.short, style = PaperTheme.typography.monoSmall, color = c.textTertiary)
                                Text(cm.subject, style = PaperTheme.typography.small, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(VcsDate.short(cm.parsedDate), style = PaperTheme.typography.caption, color = c.textTertiary, maxLines = 1)
                            }
                        }
                        if (p.commits.size > 30) Text("…", color = c.textTertiary, modifier = Modifier.padding(horizontal = sp.md, vertical = sp.xs))
                    }
                }
            }
            PaperDivider(subtle = true)
            VcsCheckRow(tags, { tags = it }, tr("vcs.push.tags"))
            if (tags) {
                PaperSegmented(listOf("follow" to tr("vcs.push.tagsFollow"), "all" to tr("vcs.push.tagsAll")), tagsMode, { tagsMode = it }, fill = true, modifier = Modifier.fillMaxWidth())
            }
            if (offerUpstream) VcsCheckRow(setUpstream, { setUpstream = it }, tr("vcs.push.setUpstream"))
            VcsCheckRow(force, { force = it }, tr("vcs.push.forceLease"), tr("vcs.push.forceInfo"))
            VcsCheckRow(noVerify, { noVerify = it }, tr("vcs.commit.noVerify"))
        }
    }

    // ---------------------------------------------------------------- 上游与远程

    fun setUpstream(repo: VcsRepo, branch: String) {
        if (branch.isEmpty()) return
        Dialogs.present("vcs-upstream") { dismiss ->
            var upstream by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                if (repo.branches == null) repo.loadBranches()
                val remotes = repo.branches?.remote.orEmpty().map { it.name }
                val current = repo.branches?.local?.firstOrNull { it.name == branch }?.upstream.orEmpty()
                upstream = current.ifEmpty { remotes.firstOrNull { it.endsWith("/$branch") }.orEmpty() }
            }
            val options = listOf(SelectOption("", tr("vcs.branch.noUpstream"))) + repo.branches?.remote.orEmpty().map { SelectOption(it.name, it.name, "cloud") }
            VcsFormSheet(
                tr("vcs.branch.setUpstreamTitle", branch),
                dismiss,
                confirmText = tr("common.save"),
                enabled = upstream != null,
                onSubmit = {
                    repo.runThrowing(tr("vcs.branch.setUpstream").plain()) { api ->
                        api.vcsSetUpstream(repo.projectId, repo.root, VCSSetUpstreamArgs(branch, upstream.orEmpty()))
                    }
                    repo.loadBranches()
                },
            ) {
                PaperSelect(options, upstream, { upstream = it }, label = tr("vcs.branch.upstream"), title = tr("vcs.branch.upstream"), modifier = Modifier.fillMaxWidth())
            }
        }
    }

    /** 新增（remote 为空）或修改远程地址 */
    fun remoteEdit(repo: VcsRepo, remote: VCSRemote?, then: (() -> Unit)? = null) {
        Dialogs.present("vcs-remote") { dismiss ->
            var name by remember { mutableStateOf(remote?.name ?: "origin") }
            var url by remember { mutableStateOf(remote?.fetchUrl.orEmpty()) }
            var pushUrl by remember { mutableStateOf(remote?.let { r -> r.pushUrl?.takeIf { it != r.fetchUrl } }.orEmpty()) }
            VcsFormSheet(
                if (remote == null) tr("vcs.remote.add").plain() else tr("vcs.mobile.remote.edit"),
                dismiss,
                confirmText = if (remote == null) tr("common.create") else tr("common.save"),
                enabled = name.isNotBlank() && url.isNotBlank(),
                onSubmit = {
                    val r = VCSRemote(name.trim(), url.trim(), pushUrl.trim().ifEmpty { null })
                    repo.runThrowing(if (remote == null) tr("vcs.remote.add").plain() else tr("vcs.mobile.remote.edit"), quiet = true) { api ->
                        api.vcsRemoteSet(repo.projectId, repo.root, r)
                    }
                    repo.loadRemotes()
                    repo.loadBranches()
                    then?.invoke()
                },
            ) {
                PaperTextField(name, { name = it }, label = tr("vcs.remote.name"), enabled = remote == null, mono = true)
                PaperTextField(url, { url = it }, label = tr("vcs.remote.url"), placeholder = "https://… / git@…", mono = true, keyboardType = KeyboardType.Uri)
                PaperTextField(pushUrl, { pushUrl = it }, label = tr("vcs.mobile.remote.pushUrl"), placeholder = tr("vcs.mobile.remote.pushUrlSame"), mono = true, keyboardType = KeyboardType.Uri)
            }
        }
    }

    // ---------------------------------------------------------------- 储藏

    /** 新建储藏；paths 非空时只储藏这些文件 */
    fun stashNew(repo: VcsRepo, paths: List<String>?) {
        Dialogs.present("vcs-stash") { dismiss ->
            var message by remember { mutableStateOf("") }
            var untracked by remember { mutableStateOf(false) }
            var keepIndex by remember { mutableStateOf(false) }
            VcsFormSheet(
                paths?.let { tr("vcs.stash.newFiles", it.size) } ?: tr("vcs.stash.new").plain(),
                dismiss,
                confirmText = tr("vcs.stash.save"),
                icon = "archive",
                onSubmit = {
                    val args = VCSStashSaveArgs(
                        message = message.trim().ifEmpty { null },
                        includeUntracked = untracked.takeIf { it && paths == null },
                        paths = paths,
                        keepIndex = keepIndex.takeIf { it },
                    )
                    repo.runThrowing(tr("vcs.stash.new").plain()) { api -> api.vcsStashSave(repo.projectId, repo.root, args) }
                    repo.loadStashes()
                },
            ) {
                PaperTextField(message, { message = it }, label = tr("vcs.stash.message"))
                if (paths == null) PaperCheckbox(untracked, { untracked = it }, label = tr("vcs.stash.includeUntracked"))
                VcsCheckRow(keepIndex, { keepIndex = it }, tr("vcs.stash.keepIndex"), tr("vcs.stash.keepIndexInfo"))
            }
        }
    }

    // ---------------------------------------------------------------- 分支与标签

    fun newBranch(repo: VcsRepo, start: String?, startTitle: String?) {
        Dialogs.present("vcs-branch") { dismiss ->
            var name by remember { mutableStateOf("") }
            var checkout by remember { mutableStateOf(true) }
            val title = startTitle?.let { tr("vcs.branch.newFromTitle", it) } ?: tr("vcs.branch.new").plain()
            VcsFormSheet(
                title,
                dismiss,
                confirmText = tr("common.create"),
                enabled = name.isNotBlank() && !name.contains(' '),
                onSubmit = {
                    val n = name.trim()
                    if (checkout) {
                        repo.runThrowing(tr("vcs.branch.new").plain(), extra = { r ->
                            if (r.reason != VCSReason.LOCAL_CHANGES) emptyList() else listOf(
                                VcsNotice.Action(tr("vcs.checkout.smart"), primary = true) {
                                    repo.notice = null
                                    repo.perform(tr("vcs.branch.new").plain()) { api ->
                                        api.vcsCheckout(repo.projectId, repo.root, VCSCheckoutArgs(n, create = true, start = start, smart = true))
                                    }
                                },
                            )
                        }) { api -> api.vcsCheckout(repo.projectId, repo.root, VCSCheckoutArgs(n, create = true, start = start)) }
                    } else {
                        repo.runThrowing(tr("vcs.branch.new").plain()) { api -> api.vcsBranchCreate(repo.projectId, repo.root, VCSBranchCreateArgs(n, start)) }
                    }
                    repo.loadBranches()
                },
            ) {
                PaperTextField(name, { name = it }, label = tr("vcs.branch.name"), mono = true)
                PaperCheckbox(checkout, { checkout = it }, label = tr("vcs.branch.checkoutAfter"))
            }
        }
    }

    fun newTag(repo: VcsRepo, ref: String) {
        Dialogs.present("vcs-tag") { dismiss ->
            var name by remember { mutableStateOf("") }
            var message by remember { mutableStateOf("") }
            var push by remember { mutableStateOf(false) }
            VcsFormSheet(
                tr("vcs.tag.new").plain(),
                dismiss,
                confirmText = tr("common.create"),
                enabled = name.isNotBlank() && !name.contains(' '),
                onSubmit = {
                    val args = VCSTagCreateArgs(name.trim(), ref, message.trim().ifEmpty { null }, push = push.takeIf { it })
                    repo.runThrowing(tr("vcs.tag.new").plain(), VCSOp.TAG_CREATE, long = push) { api -> api.vcsTagCreate(repo.projectId, repo.root, args) }
                    repo.loadTags()
                },
            ) {
                PaperTextField(name, { name = it }, label = tr("vcs.tag.name"), mono = true)
                VcsFieldLabel(tr("vcs.tag.message"), tr("vcs.tag.messageInfo"))
                PaperTextArea(message, { message = it }, minLines = 2, maxLines = 6)
                PaperCheckbox(push, { push = it }, label = tr("vcs.tag.push"))
            }
        }
    }

    // ---------------------------------------------------------------- 历史改写

    fun reset(repo: VcsRepo, commit: VCSCommit) {
        Dialogs.present("vcs-reset") { dismiss ->
            var mode by remember { mutableStateOf("mixed") }
            VcsFormSheet(
                tr("vcs.reset.title", repo.headTitle, commit.short),
                dismiss,
                confirmText = tr("vcs.reset"),
                danger = mode == "hard",
                onSubmit = {
                    if (mode == "hard") {
                        // 二次确认：硬重置会丢掉未提交的修改
                        Dialogs.confirm(tr("vcs.reset.hardConfirm"), tr("vcs.reset.hardConfirmMessage", commit.short), confirmText = tr("vcs.reset"), danger = true) {
                            repo.runThrowing(tr("vcs.reset")) { api -> api.vcsReset(repo.projectId, repo.root, commit.hash, "hard") }
                        }
                    } else {
                        repo.runThrowing(tr("vcs.reset")) { api -> api.vcsReset(repo.projectId, repo.root, commit.hash, mode) }
                    }
                },
            ) {
                Text(commit.subject, style = PaperTheme.typography.body, color = PaperTheme.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Column {
                    for (m in listOf("soft", "mixed", "keep", "hard")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PaperRadio(mode == m, onClick = { mode = m }, label = tr("vcs.reset.$m"))
                            PaperInfoButton(tr("vcs.reset.$m.info"))
                        }
                    }
                }
                if (mode == "hard") PaperAlert(Tone.Danger, tr("vcs.reset.hardWarning"))
            }
        }
    }

    fun reword(repo: VcsRepo, commit: VCSCommit) {
        Dialogs.present("vcs-reword") { dismiss ->
            var text by remember { mutableStateOf("") }
            var loaded by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                val d = runCatching { repo.api().vcsShow(repo.projectId, repo.root, commit.hash) }.getOrNull()
                text = d?.let { fullMessage(it.message, it.commit.subject, it.body) } ?: commit.subject
                loaded = true
            }
            VcsFormSheet(
                tr("vcs.reword.title", commit.short),
                dismiss,
                confirmText = tr("common.save"),
                enabled = loaded && text.isNotBlank(),
                onSubmit = { repo.runThrowing(tr("vcs.reword")) { api -> api.vcsReword(repo.projectId, repo.root, commit.hash, text) } },
            ) {
                PaperTextArea(text, { text = it }, minLines = 5, maxLines = 14, enabled = loaded)
            }
        }
    }

    /** 完整提交信息（没有 message 时用主题 + 正文拼） */
    fun fullMessage(message: String?, subject: String, body: String?): String =
        message?.takeIf { it.isNotEmpty() } ?: listOfNotNull(subject, body?.takeIf { it.isNotEmpty() }?.let { "\n" + it }).joinToString("\n")

    // ---------------------------------------------------------------- 提交选项

    fun author(repo: VcsRepo) {
        Dialogs.input(
            tr("vcs.commit.author").plain(),
            label = tr("vcs.commit.authorLabel"),
            initial = repo.draft.author,
            placeholder = "Name <name@example.com>",
            validate = { v -> if (v.contains('<') && v.contains('>')) null else "" },
        ) { v -> repo.draft.author = v }
    }

    fun messageHistory(repo: VcsRepo) {
        val list = VcsPrefs.messageHistory(repo.root)
        if (list.isEmpty()) {
            menu(tr("vcs.commit.history"), listOf(MenuEntry.Header(tr("vcs.commit.historyEmpty"))))
            return
        }
        menu(tr("vcs.commit.history"), list.map { m ->
            val first = m.lineSequence().firstOrNull().orEmpty()
            MenuEntry.Item(if (first.length > 60) first.take(60) + "…" else first) { repo.draft.message = m }
        })
    }

    // ---------------------------------------------------------------- 快速切换分支

    /** 标题栏分支按钮：搜索、新建、签出修订、最近 / 本地 / 远程分支（点按出操作） */
    fun branchPicker(repo: VcsRepo) {
        Dialogs.present("vcs-branch-picker") { dismiss ->
            var query by remember { mutableStateOf("") }
            LaunchedEffect(Unit) { repo.loadBranches() }
            PaperBottomSheet(onDismiss = dismiss, title = repo.headTitle.ifEmpty { tr("vcs.page.branches") }, scrollable = false, edgeToEdge = true) { close ->
                PaperSearchField(query, { query = it }, placeholder = tr("vcs.branch.search"), modifier = Modifier.padding(horizontal = PaperTheme.spacing.xl, vertical = PaperTheme.spacing.xs))
                val b = repo.branches
                fun match(n: String) = query.isBlank() || n.contains(query.trim(), ignoreCase = true)
                fun wrap(entries: List<MenuEntry>): List<MenuEntry> = entries.map { e ->
                    if (e is MenuEntry.Item) e.copy(onClick = {
                        close()
                        e.onClick()
                    }) else e
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                    if (query.isBlank()) {
                        item {
                            MenuSheetRow(MenuEntry.Item(tr("vcs.branch.new").plain(), "plus") {}) {
                                close()
                                newBranch(repo, null, null)
                            }
                        }
                        item {
                            MenuSheetRow(MenuEntry.Item(tr("vcs.checkoutRef").plain(), "circle-dot") {}) {
                                close()
                                VcsActions.checkoutRef(repo)
                            }
                        }
                    }
                    if (b == null) {
                        item { Box(Modifier.fillMaxWidth().padding(PaperTheme.spacing.xl), contentAlignment = Alignment.Center) { PaperSpinner(tint = PaperTheme.colors.iconSecondary) } }
                    } else {
                        val recent = if (query.isBlank()) b.recent.filter { it != b.current }.mapNotNull { n -> b.local.firstOrNull { it.name == n } }.take(5) else emptyList()
                        val local = b.local.filter { match(it.name) }
                        val remote = b.remote.filter { match(it.name) }
                        fun section(title: String, list: List<VCSBranch>, isRemote: Boolean) {
                            if (list.isEmpty()) return
                            item { PickerHeader(title) }
                            items(list, key = { (if (isRemote) "r:" else "l:") + title + it.name }) { br ->
                                BranchPickerRow(br, isRemote) { wrap(VcsActions.branchEntries(repo, br, isRemote)) }
                            }
                        }
                        section(tr("vcs.branch.recent"), recent, false)
                        section(tr("vcs.branch.local"), local, false)
                        section(tr("vcs.branch.remote"), remote, true)
                        if (local.isEmpty() && remote.isEmpty()) {
                            item { Text(tr("vcs.branch.noMatch"), color = PaperTheme.colors.textTertiary, modifier = Modifier.padding(PaperTheme.spacing.xl)) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PickerHeader(title: String) {
        Text(
            title,
            style = PaperTheme.typography.caption,
            color = PaperTheme.colors.textTertiary,
            modifier = Modifier.padding(start = PaperTheme.spacing.xl, top = PaperTheme.spacing.md, bottom = PaperTheme.spacing.xs),
        )
    }

    @Composable
    private fun BranchPickerRow(br: VCSBranch, remote: Boolean, menu: () -> List<MenuEntry>) {
        val c = PaperTheme.colors
        PaperLongPressArea(onClick = null, menu = menu) {
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = PaperTheme.sizes.rowHeight).padding(horizontal = PaperTheme.spacing.xl),
                horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PaperIcon(if (br.current) "check" else if (remote) "cloud" else "git-branch", size = PaperTheme.sizes.iconSmall, tint = if (br.current) c.accent else c.iconSecondary)
                Text(br.name, style = if (br.current) PaperTheme.typography.bodyStrong else PaperTheme.typography.body, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
                VcsAheadBehind(br.ahead, br.behind)
                if (br.gone) PaperIcon("triangle-alert", size = 14.dp, tint = c.warning, contentDescription = tr("vcs.branch.upstreamGone"))
                if (br.worktree != null) PaperIcon("git-fork", size = 14.dp, tint = c.iconSecondary, contentDescription = tr("vcs.branch.inWorktree", br.worktree.orEmpty()))
                PaperIcon("chevron-right", size = 16.dp, tint = c.iconSecondary)
            }
        }
    }
}
