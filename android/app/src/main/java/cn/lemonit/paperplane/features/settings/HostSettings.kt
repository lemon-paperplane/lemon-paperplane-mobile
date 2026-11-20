package cn.lemonit.paperplane.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.features.forms.FormSheets
import cn.lemonit.paperplane.kit.model.FormField
import cn.lemonit.paperplane.kit.model.RemoteAuditEntry
import cn.lemonit.paperplane.kit.model.RemoteAuditEvent
import cn.lemonit.paperplane.kit.model.RemoteKey
import cn.lemonit.paperplane.kit.model.RemoteKeyCreated
import cn.lemonit.paperplane.kit.model.RemoteStatus
import cn.lemonit.paperplane.kit.model.RemoteUser
import cn.lemonit.paperplane.kit.model.stringValue
import cn.lemonit.paperplane.kit.remoteAudit
import cn.lemonit.paperplane.kit.remoteConfigure
import cn.lemonit.paperplane.kit.remoteKeyCreate
import cn.lemonit.paperplane.kit.remoteKeyRevoke
import cn.lemonit.paperplane.kit.remoteKeys
import cn.lemonit.paperplane.kit.remoteStatus
import cn.lemonit.paperplane.kit.remoteUserRemove
import cn.lemonit.paperplane.kit.remoteUserSet
import cn.lemonit.paperplane.kit.remoteUsers
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.MenuEntry
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperBadge
import cn.lemonit.paperplane.ui.components.PaperBottomSheet
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperCodeText
import cn.lemonit.paperplane.ui.components.PaperGroup
import cn.lemonit.paperplane.ui.components.PaperGroupDefaults
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.PaperListRow
import cn.lemonit.paperplane.ui.components.PaperMoreButton
import cn.lemonit.paperplane.ui.components.PaperSectionHeader
import cn.lemonit.paperplane.ui.components.PaperSpinner
import cn.lemonit.paperplane.ui.components.PaperTextField
import cn.lemonit.paperplane.ui.components.PaperToggleRow
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.I18n
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.SvgImage
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 当前服务器的「允许其他设备连接」（对照 Mac HostSettingsModel）：remote.* 全走当前服务器的接口。
 * 手机就是经这个远程连接在管服务器：关掉远程连接、改端口、吊销本设备的密钥都会让本机断开，先确认。
 */
@Stable
class HostModel {
    var status by mutableStateOf<RemoteStatus?>(null)
    var keys by mutableStateOf<List<RemoteKey>>(emptyList())
    var users by mutableStateOf<List<RemoteUser>>(emptyList())
    var audit by mutableStateOf<List<RemoteAuditEntry>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)

    suspend fun load() {
        try {
            status = AppModel.api().remoteStatus()
            error = null
            loadLists()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: tr("common.failed")
        }
    }

    suspend fun loadLists() = coroutineScope {
        val api = runCatching { AppModel.api() }.getOrNull() ?: return@coroutineScope
        val k = async { runCatching { api.remoteKeys() }.getOrNull() }
        val u = async { runCatching { api.remoteUsers() }.getOrNull() }
        val a = async { runCatching { api.remoteAudit(50) }.getOrNull() }
        k.await()?.let { keys = it }
        u.await()?.let { users = it }
        a.await()?.let { audit = it }
    }

    fun configure(enabled: Boolean? = null, port: Int? = null, passwordLogin: Boolean? = null, autostart: Boolean? = null) {
        busy = true
        AppModel.scope.launch {
            try {
                status = AppModel.api().remoteConfigure(enabled = enabled, port = port, passwordLogin = passwordLogin, autostart = autostart)
                loadLists()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toasts.error(e, tr("settings.saveFailed"))
                load()
            } finally {
                busy = false
            }
        }
    }

    fun reload() = AppModel.scope.launch { load() }
}

/** 「允许其他设备连接「服务器名」」整个分区 */
@Composable
fun HostSection(model: HostModel, serverName: String, modifier: Modifier = Modifier) {
    val sp = PaperTheme.spacing
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sp.lg)) {
        PaperSectionHeader(tr("host.titleRemote", serverName), info = tr("host.enabled.info"))
        model.error?.let { e ->
            PaperAlert(Tone.Danger, e) {
                PaperButton(tr("server.retry"), onClick = { model.reload() }, size = ButtonSize.Small, icon = "refresh-cw")
            }
        }
        val st = model.status
        if (st == null) {
            if (model.error == null) {
                PaperGroup { Box(Modifier.fillMaxWidth().padding(sp.lg)) { PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = PaperTheme.colors.iconSecondary) } }
            }
            return@Column
        }
        StatusGroup(model, st, serverName)
        if (st.enabled) {
            AddressesGroup(st)
            KeysGroup(model, serverName)
            UsersGroup(model, st, serverName)
            DevicesGroup(model, serverName)
        }
        AuditGroup(model)
    }
}

// ---------------------------------------------------------------- 开关、端口、自动启动

@Composable
private fun StatusGroup(model: HostModel, st: RemoteStatus, serverName: String) {
    val c = PaperTheme.colors
    var editPort by remember { mutableStateOf(false) }
    PaperGroup {
        val sub = when {
            st.enabled && st.error.isNotEmpty() -> tr("host.error", st.error)
            st.enabled && st.running -> tr("host.running", st.listen)
            else -> null
        }
        PaperListRow(
            tr("host.enabled"),
            subtitle = sub,
            subtitleMaxLines = 3,
            onClick = { setEnabled(model, !st.enabled, serverName) },
            enabled = !model.busy,
            trailing = {
                if (model.busy) PaperSpinner(size = PaperTheme.sizes.iconSmall, tint = c.iconSecondary)
                if (st.enabled && st.running && st.error.isEmpty()) Box(Modifier.size(7.dp).background(c.statusRunning, CircleShape))
                cn.lemonit.paperplane.ui.components.PaperSwitch(st.enabled, onCheckedChange = { setEnabled(model, it, serverName) }, enabled = !model.busy)
            },
        )
        SettingRow(tr("host.port"), value = st.port.toString(), showChevron = true, enabled = !model.busy, onClick = { editPort = true })
        if (st.autostartSupported) {
            PaperToggleRow(tr("host.autostart"), checked = st.autostart, onCheckedChange = { model.configure(autostart = it) }, info = tr("host.autostart.info"), enabled = !model.busy)
        }
    }
    if (editPort) {
        ValueInputSheet(tr("host.port"), st.port.toString(), onDismiss = { editPort = false }, number = true, range = 1..65535) { v ->
            val p = v.toInt()
            if (p == st.port) return@ValueInputSheet
            // 改端口后本机按旧端口连不上了：先确认
            Dialogs.confirm(tr("host.port"), message = tr("mobile.host.port.confirm", serverName, p), confirmText = tr("common.save"), danger = true) {
                model.configure(port = p)
            }
        }
    }
}

/** 关闭远程连接 = 本机和这台服务器断开：先按桌面文案确认 */
private fun setEnabled(model: HostModel, on: Boolean, serverName: String) {
    if (!on) {
        Dialogs.confirm(tr("host.disable"), message = tr("host.disable.remoteConfirm", serverName), confirmText = tr("host.disable"), danger = true) {
            model.configure(enabled = false)
        }
        return
    }
    model.configure(enabled = true)
}

// ---------------------------------------------------------------- 地址、指纹

@Composable
private fun AddressesGroup(st: RemoteStatus) {
    val sp = PaperTheme.spacing
    if (st.addresses.isNotEmpty()) {
        PaperGroup(tr("host.addresses")) {
            for (a in st.addresses) {
                PaperListRow(Format.shortURL(a), trailing = {
                    PaperIconButton("copy", tr("host.copy"), onClick = { Actions.copy(a, tr("host.copied")) }, size = ButtonSize.Small, tint = PaperTheme.colors.iconSecondary)
                })
            }
        }
    }
    val fp = st.fingerprintDisplay.ifEmpty { st.fingerprint }
    if (fp.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(sp.sm)) {
            PaperSectionHeader(tr("host.fingerprint"), info = tr("host.fingerprint.info"))
            PaperCodeText(fp, small = true)
        }
    }
}

// ---------------------------------------------------------------- 访问密钥、已登录的设备

private fun lastUsed(k: RemoteKey): String {
    val d = k.lastUsedAt ?: return tr("host.keys.neverUsed")
    if (d.epochSecond <= 0) return tr("host.keys.neverUsed")
    return tr("host.keys.lastUsed", DateText.short(d), k.lastIP.ifEmpty { "-" })
}

@Composable
private fun KeysGroup(model: HostModel, serverName: String) {
    val c = PaperTheme.colors
    val keys = model.keys.filter { !it.isDevice }
    PaperGroup(
        tr("host.keys"),
        dividerInset = PaperGroupDefaults.IconRowInset,
        trailing = { PaperButton(tr("host.keys.add"), onClick = { createKey(model) }, kind = ButtonKind.Text, size = ButtonSize.Small, icon = "key-round") },
    ) {
        if (keys.isEmpty()) {
            EmptyRow(tr("host.keys.empty"))
        } else {
            for (k in keys) {
                PaperListRow(
                    k.name,
                    subtitle = tr("host.keys.created.at", DateText.short(k.createdAt)) + " · " + lastUsed(k),
                    subtitleMaxLines = 2,
                    icon = "key-round",
                    iconTint = c.iconSecondary,
                    trailing = {
                        if (k.current) PaperBadge(tr("host.devices.current"), tone = Tone.Info)
                        RevokeButton(model, k, serverName)
                    },
                )
            }
        }
    }
}

@Composable
private fun DevicesGroup(model: HostModel, serverName: String) {
    val c = PaperTheme.colors
    val devices = model.keys.filter { it.isDevice }
    PaperGroup(tr("host.devices"), dividerInset = PaperGroupDefaults.IconRowInset) {
        if (devices.isEmpty()) {
            EmptyRow(tr("host.devices.empty"))
        } else {
            for (k in devices) {
                PaperListRow(
                    k.name,
                    subtitle = listOf(k.user, lastUsed(k)).filter { it.isNotEmpty() }.joinToString(" · "),
                    subtitleMaxLines = 2,
                    icon = "monitor",
                    iconTint = c.iconSecondary,
                    trailing = {
                        if (k.current) PaperBadge(tr("host.devices.current"), tone = Tone.Info)
                        RevokeButton(model, k, serverName)
                    },
                )
            }
        }
    }
}

/** 吊销：本设备正在用的那条要说明吊销后本机会断开 */
@Composable
private fun RevokeButton(model: HostModel, k: RemoteKey, serverName: String) {
    PaperButton(tr("host.keys.revoke"), onClick = {
        Dialogs.confirm(
            tr("host.keys.revoke.confirm", k.name),
            message = if (k.current) tr("mobile.host.revokeSelf", serverName) else tr("host.keys.revoke.message"),
            confirmText = tr("host.keys.revoke"),
            danger = true,
        ) {
            AppModel.api().remoteKeyRevoke(k.id)
            if (!k.current) model.load()
        }
    }, kind = ButtonKind.Text, size = ButtonSize.Small)
}

/** 生成密钥：填设备名、可选的外网地址 → 显示连接码与二维码（只显示这一次） */
private fun createKey(model: HostModel) {
    val fields = listOf(
        FormField(key = "name", title = tr("host.keys.name"), type = "string", placeholder = tr("host.keys.name.placeholder"), required = true),
        FormField(key = "address", title = tr("host.keys.address"), type = "string", placeholder = tr("server.add.address.placeholder"), info = tr("host.keys.address.info")),
    )
    FormSheets.show(tr("host.keys.add"), fields, confirmText = tr("host.keys.add")) { v ->
        val r = AppModel.api().remoteKeyCreate(v["name"]?.stringValue.orEmpty().trim(), v["address"]?.stringValue?.trim())
        AppModel.scope.launch {
            delay(250)
            Dialogs.present("connection-code") { dismiss -> ConnectionCodeSheet(r, dismiss) }
            model.load()
        }
    }
}

/** 生成密钥后：连接码与二维码（白底、不着色，手机扫码添加服务器），可复制连接码 */
@Composable
fun ConnectionCodeSheet(created: RemoteKeyCreated, onDismiss: () -> Unit) {
    PaperBottomSheet(onDismiss = onDismiss, title = tr("host.keys.created"), showClose = false) { dismiss ->
        ConnectionCodeBody(created, onClose = dismiss)
    }
}

/** 连接码面板的内容（截图测试放进 PaperSheetPreview） */
@Composable
fun ColumnScope.ConnectionCodeBody(created: RemoteKeyCreated, onClose: () -> Unit) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    PaperAlert(Tone.Warning, tr("host.keys.created.message"))
    if (created.qrSvg.isNotEmpty()) {
        val bytes = remember(created.qrSvg) { created.qrSvg.toByteArray() }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(sp.sm)) {
            val shape = RoundedCornerShape(PaperTheme.radius.lg)
            Box(
                Modifier
                    .background(Color.White, shape)
                    .border(PaperTheme.sizes.border, c.border, shape)
                    .padding(sp.md),
            ) {
                SvgImage(bytes, size = 200.dp, tint = null, contentDescription = tr("host.keys.qr.hint"))
            }
            Text(tr("host.keys.qr.hint"), style = PaperTheme.typography.small, color = c.textSecondary, textAlign = TextAlign.Center)
        }
    }
    PaperCodeText(created.code, small = true, copyable = false)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sp.md)) {
        PaperButton(tr("common.close"), onClick = onClose, modifier = Modifier.weight(1f))
        PaperButton(tr("host.copy"), onClick = { Actions.copy(created.code, tr("host.copied")) }, kind = ButtonKind.Primary, icon = "copy", modifier = Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------- 账号密码

@Composable
private fun UsersGroup(model: HostModel, st: RemoteStatus, serverName: String) {
    val c = PaperTheme.colors
    var editing by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    PaperGroup(
        tr("host.users"),
        dividerInset = PaperGroupDefaults.IconRowInset,
        trailing = if (st.passwordLogin) ({ PaperButton(tr("host.users.add"), onClick = { adding = true }, kind = ButtonKind.Text, size = ButtonSize.Small, icon = "plus") }) else null,
    ) {
        PaperToggleRow(tr("host.passwordLogin"), checked = st.passwordLogin, onCheckedChange = { model.configure(passwordLogin = it) }, info = tr("host.passwordLogin.info"), enabled = !model.busy)
        if (st.passwordLogin || model.users.isNotEmpty()) {
            if (model.users.isEmpty()) {
                EmptyRow(tr("host.users.empty"))
            } else {
                for (u in model.users) {
                    PaperListRow(
                        u.name,
                        subtitle = DateText.short(u.updatedAt ?: u.createdAt),
                        icon = "user",
                        iconTint = c.iconSecondary,
                        trailing = {
                            PaperMoreButton({
                                listOf(
                                    MenuEntry.Item(tr("host.users.setPassword"), "key-round") { editing = u.name },
                                    MenuEntry.Divider,
                                    MenuEntry.Item(tr("common.delete"), "trash-2", destructive = true) { removeUser(model, u, serverName) },
                                )
                            }, size = ButtonSize.Small)
                        },
                    )
                }
            }
        }
    }
    if (adding) AccountPasswordSheet(null, onDismiss = { adding = false }) { model.load() }
    editing?.let { name -> AccountPasswordSheet(name, onDismiss = { editing = null }) { model.load() } }
}

private fun removeUser(model: HostModel, u: RemoteUser, serverName: String) {
    // 本机是用这个账号登录的：删掉后本机也会断开
    val self = model.keys.any { it.current && it.isDevice && it.user == u.name }
    Dialogs.confirm(
        tr("host.users.remove.confirm", u.name),
        message = if (self) tr("mobile.host.removeSelfUser", serverName) else tr("host.users.remove.message"),
        confirmText = tr("common.delete"),
        danger = true,
    ) {
        AppModel.api().remoteUserRemove(u.name)
        if (!self) model.load()
    }
}

/** 添加账号 / 设置密码：至少 10 位，输两遍 */
@Composable
private fun AccountPasswordSheet(existing: String?, onDismiss: () -> Unit, onDone: suspend () -> Unit) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }
    var showErrors by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val tooShort = password.length < 10
    val mismatch = password != password2
    PaperBottomSheet(onDismiss = onDismiss, title = if (existing == null) tr("host.users.add") else tr("host.users.setPassword"), showClose = false) { dismiss ->
        if (existing == null) {
            PaperTextField(name, { name = it }, label = tr("host.users.name"), error = if (showErrors && name.isBlank()) "" else null)
        } else {
            Text(existing, style = PaperTheme.typography.bodyStrong, color = PaperTheme.colors.textPrimary)
        }
        PaperTextField(
            password,
            { password = it },
            label = tr("host.users.password"),
            placeholder = tr("host.users.password.rule"),
            password = true,
            error = if (showErrors && tooShort) tr("host.users.password.rule") else null,
        )
        PaperTextField(
            password2,
            { password2 = it },
            label = tr("host.users.password2"),
            password = true,
            error = if (showErrors && !tooShort && mismatch) tr("host.users.password.mismatch") else null,
        )
        error?.let { PaperAlert(Tone.Danger, it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md)) {
            PaperButton(tr("common.cancel"), onClick = { dismiss() }, modifier = Modifier.weight(1f))
            PaperButton(tr("common.save"), onClick = {
                val n = existing ?: name.trim()
                if (n.isEmpty() || tooShort || mismatch) {
                    showErrors = true
                    return@PaperButton
                }
                busy = true
                error = null
                scope.launch {
                    try {
                        AppModel.api().remoteUserSet(n, password)
                        password = ""
                        password2 = ""
                        dismiss()
                        onDone()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: tr("common.failed")
                    } finally {
                        busy = false
                    }
                }
            }, kind = ButtonKind.Primary, loading = busy, modifier = Modifier.weight(1f))
        }
    }
}

// ---------------------------------------------------------------- 安全日志

private const val AUDIT_PREVIEW = 15

@Composable
private fun AuditGroup(model: HostModel) {
    val c = PaperTheme.colors
    var all by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = if (all) model.audit else model.audit.take(AUDIT_PREVIEW)
    PaperGroup(
        tr("host.audit"),
        trailing = {
            PaperIconButton("refresh-cw", tr("files.refresh"), onClick = { scope.launch { model.loadLists() } }, size = ButtonSize.Small, tint = c.iconSecondary)
        },
    ) {
        if (list.isEmpty()) {
            EmptyRow(tr("host.audit.empty"))
        } else {
            for (e in list) {
                val key = "host.audit.event." + e.event
                val title = if (I18n.has(key)) tr(key) else e.event
                val danger = e.event == RemoteAuditEvent.LOGIN_FAIL || e.event == RemoteAuditEvent.AUTH_FAIL || e.event == RemoteAuditEvent.BAN
                PaperListRow(
                    title,
                    subtitle = listOf(DateText.full(e.time), e.who, e.ip, e.detail).filter { it.isNotEmpty() }.joinToString(" · "),
                    subtitleMaxLines = 2,
                    destructive = danger,
                )
            }
            if (!all && model.audit.size > AUDIT_PREVIEW) {
                PaperListRow(tr("mobile.host.audit.showAll", model.audit.size), onClick = { all = true }, showChevron = true)
            }
        }
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(
        text,
        style = PaperTheme.typography.body,
        color = PaperTheme.colors.textTertiary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = PaperTheme.spacing.xl, vertical = PaperTheme.spacing.lg),
    )
}

/** 打开设置页时加载一次；切换服务器后重新加载 */
@Composable
internal fun rememberHostModel(serverId: String?): HostModel {
    val m = remember(serverId) { HostModel() }
    LaunchedEffect(m) { m.load() }
    return m
}
