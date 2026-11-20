package cn.lemonit.paperplane.features.servers

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AddMethod
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.core.Route
import cn.lemonit.paperplane.core.ServerEntry
import cn.lemonit.paperplane.core.serverErrorText
import cn.lemonit.paperplane.kit.ApiException
import cn.lemonit.paperplane.kit.PaperplaneJson
import cn.lemonit.paperplane.kit.model.SystemInfo
import cn.lemonit.paperplane.net.ConnectionCode
import cn.lemonit.paperplane.net.Fingerprints
import cn.lemonit.paperplane.net.ProbeResult
import cn.lemonit.paperplane.net.RemoteAuth
import cn.lemonit.paperplane.net.RemoteClient
import cn.lemonit.paperplane.net.ServerAddress
import cn.lemonit.paperplane.net.ServerTarget
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperInfoButton
import cn.lemonit.paperplane.ui.components.PaperSegmented
import cn.lemonit.paperplane.ui.components.PaperTextArea
import cn.lemonit.paperplane.ui.components.PaperTextField
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.shell.PageScaffold
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AddMode { SCAN, CODE, PASSWORD }

/**
 * 添加服务器（也用于重新登录）。
 * 扫码 / 连接码：解码 → 依次试连接码里的地址（固定指纹）→ 保存并切到这台；
 * 账号密码：探测拿证书（不是 CA 证书时让用户核对指纹）→ 登录换访问密钥 → 连接 → 保存。
 */
data class AddServerRoute(val reloginId: String? = null, val mode: AddMode? = null, val code: String? = null) : Route {
    override val key get() = "addServer"

    @Composable
    override fun Content() = AddServerPage(this)
}

@Composable
fun AddServerPage(route: AddServerRoute, onDone: () -> Unit = { AppModel.nav.pop() }) {
    val relogin = remember(route.reloginId) { route.reloginId?.let { id -> AppModel.serverList.firstOrNull { it.id == id } } }
    val initialMode = route.mode ?: when {
        route.code != null -> AddMode.CODE
        relogin?.addedVia == AddMethod.PASSWORD -> AddMode.PASSWORD
        else -> AddMode.SCAN
    }
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var name by rememberSaveable { mutableStateOf(relogin?.name.orEmpty()) }
    var nameEdited by rememberSaveable { mutableStateOf(relogin != null) }
    var code by rememberSaveable { mutableStateOf(route.code.orEmpty()) }
    var address by rememberSaveable { mutableStateOf(if (relogin?.addedVia == AddMethod.PASSWORD) relogin.url else "") }
    var user by rememberSaveable { mutableStateOf(relogin?.user.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingCert by remember { mutableStateOf<ProbeResult?>(null) }
    var viaScan by rememberSaveable { mutableStateOf(false) }
    var certChanged by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val decoded = remember(code) { ConnectionCode.decodeOrNull(code) }
    val decodeFailed = code.isNotBlank() && decoded == null
    // 连接码解出来后：名称和地址跟着填
    LaunchedEffect(decoded) {
        if (decoded != null) {
            if (address.isBlank() || mode == AddMode.CODE) address = decoded.urls.firstOrNull().orEmpty()
            if (!nameEdited || name.isBlank()) name = decoded.name
        }
    }

    val duplicate: ServerEntry? = if (relogin != null) null else when (mode) {
        AddMode.SCAN, AddMode.CODE -> decoded?.let { d -> AppModel.existingServer(listOf(address) + d.urls, d.fingerprint) }
        AddMode.PASSWORD -> ServerAddress.normalize(address)?.let { n ->
            AppModel.serverList.firstOrNull { s -> s.urls.any { ServerAddress.same(it, n) } }
        }
    }

    /** 保存并切到这台 */
    fun finish(urls: List<String>, url: String, fingerprint: String, trusted: Boolean, token: String, keyId: String, info: SystemInfo?, via: AddMethod, fallbackName: String) {
        val existing = relogin ?: duplicate
        val n = name.trim()
        val entryName = when {
            n.isNotEmpty() -> n
            existing != null && existing.name.isNotEmpty() -> existing.name
            fallbackName.isNotEmpty() -> fallbackName
            !info?.hostname.isNullOrEmpty() -> info!!.hostname
            else -> ServerAddress.host(url)
        }
        val base = existing ?: ServerEntry(name = entryName, urls = urls, addedAt = System.currentTimeMillis())
        val entry = base.copy(
            name = entryName,
            urls = ServerAddress.normalizeAll(urls + (existing?.urls ?: emptyList())),
            lastUrl = url,
            fingerprint = fingerprint,
            trusted = trusted,
            keyId = keyId,
            user = if (via == AddMethod.PASSWORD) user.trim() else base.user,
            addedVia = via,
            os = info?.os ?: base.os,
            hostname = info?.hostname ?: base.hostname,
        )
        try {
            val saved = AppModel.saveServer(entry, token)
            password = ""
            onDone()
            if (AppModel.currentServerId == saved.id) AppModel.reconnectServer() else AppModel.switchServer(saved.id)
        } catch (e: Exception) {
            error = tr("server.err.keychain")
        }
    }

    fun connectWithCode() {
        val d = decoded ?: return
        busy = true
        error = null
        scope.launch {
            try {
                val conn = withContext(Dispatchers.IO) { RemoteAuth().connectWithCode(d, address.trim().ifEmpty { null }) }
                val info = runCatching { PaperplaneJson.decodeFromJsonElement(SystemInfo.serializer(), conn.info) }.getOrNull()
                val urls = buildList {
                    ServerAddress.normalize(address)?.let { add(it) }
                    addAll(d.normalizedUrls)
                }
                finish(urls, conn.url, d.fingerprint, false, conn.token, conn.keyId, info, if (viaScan) AddMethod.SCAN else AddMethod.CODE, d.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = serverErrorText(e)
            } finally {
                busy = false
            }
        }
    }

    fun login(p: ProbeResult) {
        busy = true
        error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    RemoteAuth().login(p.url, p.fingerprint, p.trusted, user.trim(), password, deviceName())
                }
                val client = RemoteClient(ServerTarget.of(listOf(p.url), p.fingerprint, p.trusted), result.token)
                val info = try {
                    PaperplaneJson.decodeFromJsonElement(SystemInfo.serializer(), client.connect())
                } finally {
                    client.close()
                }
                pendingCert = null
                finish(listOf(p.url), p.url, p.fingerprint, p.trusted, result.token, result.keyId, info, AddMethod.PASSWORD, p.hello.name.ifEmpty { p.hello.hostname })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = serverErrorText(e)
                // 账号密码错、被封禁：回到表单改
                val a = e as? ApiException
                if (a?.code == ApiException.UNAUTHORIZED || a?.code == ApiException.BANNED) pendingCert = null
            } finally {
                busy = false
            }
        }
    }

    fun probe() {
        val addr = ServerAddress.normalize(address) ?: return
        busy = true
        error = null
        scope.launch {
            try {
                val p = withContext(Dispatchers.IO) { RemoteAuth().probe(addr) }
                if (!p.hello.passwordLogin) {
                    error = tr("server.err.passwordDisabled")
                    busy = false
                    return@launch
                }
                if (!p.trusted) {
                    // 重新登录且指纹没变：不用再确认
                    val known = relogin != null && relogin.fingerprint.isNotEmpty() && Fingerprints.same(relogin.fingerprint, p.fingerprint)
                    if (!known) {
                        certChanged = relogin != null && relogin.fingerprint.isNotEmpty()
                        pendingCert = p
                        busy = false
                        return@launch
                    }
                }
                login(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = serverErrorText(e)
                busy = false
            }
        }
    }

    val canSubmit = when (mode) {
        AddMode.SCAN -> false
        AddMode.CODE -> decoded != null && address.isNotBlank()
        AddMode.PASSWORD -> ServerAddress.normalize(address) != null && user.isNotBlank() && password.isNotEmpty()
    }

    fun submit() {
        if (!canSubmit || busy) return
        if (mode == AddMode.PASSWORD) probe() else connectWithCode()
    }

    val title = if (relogin == null) tr("server.add.title") else tr("server.action.relogin")
    val cert = pendingCert
    PageScaffold(
        title = title,
        subtitle = relogin?.name,
        onBack = if (cert != null) ({ pendingCert = null }) else cn.lemonit.paperplane.ui.shell.LocalPageBack.current,
        bottomBar = if (mode == AddMode.SCAN && cert == null) null else ({
            Row(Modifier.fillMaxWidth().padding(PaperTheme.spacing.lg), horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md)) {
                if (cert != null) {
                    PaperButton(tr("common.cancel"), onClick = { pendingCert = null }, modifier = Modifier.weight(1f))
                    PaperButton(tr("server.verify.trust"), onClick = { login(cert) }, kind = ButtonKind.Primary, loading = busy, modifier = Modifier.weight(1f))
                } else {
                    PaperButton(
                        if (busy) tr("server.add.connecting") else tr("server.add.connect"),
                        onClick = ::submit,
                        kind = ButtonKind.Primary,
                        loading = busy,
                        enabled = canSubmit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PaperTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.lg)) {
                if (cert != null) {
                    CertView(cert, certChanged, error)
                    return@Column
                }
                PaperSegmented(
                    listOf(
                        AddMode.SCAN to tr("server.add.mode.scan"),
                        AddMode.CODE to tr("server.add.mode.code"),
                        AddMode.PASSWORD to tr("server.add.mode.password"),
                    ),
                    selected = mode,
                    onSelect = {
                        mode = it
                        error = null
                    },
                    fill = true,
                    enabled = !busy,
                )
                when (mode) {
                    AddMode.SCAN -> {
                        QrScanner(
                            onScanned = { text ->
                                if (ConnectionCode.looksLikeCode(text) && ConnectionCode.decodeOrNull(text) != null) {
                                    code = text.trim()
                                    viaScan = true
                                    mode = AddMode.CODE
                                    // 扫到了就直接连接（地址、名称在解码后填好）
                                    scope.launch {
                                        kotlinx.coroutines.yield()
                                        val d = ConnectionCode.decodeOrNull(code) ?: return@launch
                                        if (address.isBlank()) address = d.urls.firstOrNull().orEmpty()
                                        if (!nameEdited || name.isBlank()) name = d.name
                                        connectWithCode()
                                    }
                                } else {
                                    cn.lemonit.paperplane.ui.components.Toasts.warning(tr("server.scan.invalid"))
                                }
                            },
                            paused = busy,
                        )
                    }
                    AddMode.CODE -> {
                        PaperTextField(
                            name,
                            { name = it; nameEdited = true },
                            label = tr("server.add.name"),
                            placeholder = tr("server.add.name.placeholder"),
                            clearable = true,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs)) {
                            LabelWithInfo(tr("server.add.code"), tr("server.add.code.info")) {
                                PaperButton(tr("server.add.paste").substringBefore('…'), onClick = {
                                    Actions.clipboardText()?.trim()?.let {
                                        code = it
                                        viaScan = false
                                    }
                                }, icon = "clipboard", kind = ButtonKind.Text, size = ButtonSize.Small)
                            }
                            PaperTextArea(
                                code,
                                { code = it },
                                placeholder = tr("server.add.code.placeholder"),
                                error = if (decodeFailed) tr("server.add.code.invalid") else null,
                                mono = true,
                                minLines = 3,
                                maxLines = 6,
                            )
                        }
                        if (decoded != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs)) {
                                LabelWithInfo(tr("server.add.address"), tr("server.add.address.info"))
                                PaperTextField(address, { address = it }, placeholder = tr("server.add.address.placeholder"), mono = true, keyboardType = KeyboardType.Uri)
                            }
                        }
                    }
                    AddMode.PASSWORD -> {
                        PaperTextField(
                            name,
                            { name = it; nameEdited = true },
                            label = tr("server.add.name"),
                            placeholder = tr("server.add.name.placeholder"),
                            clearable = true,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs)) {
                            LabelWithInfo(tr("server.add.address"), tr("server.add.address.info"))
                            PaperTextField(
                                address,
                                { address = it },
                                placeholder = tr("server.add.address.placeholder"),
                                mono = true,
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Next,
                            )
                        }
                        PaperTextField(user, { user = it }, label = tr("server.add.user"), imeAction = ImeAction.Next)
                        PaperTextField(password, { password = it }, label = tr("server.add.password"), password = true, onSubmit = ::submit)
                    }
                }
                if (duplicate != null) PaperAlert(Tone.Info, tr("server.add.duplicate", duplicate.name))
                error?.let { PaperAlert(Tone.Danger, it) }
            }
        }
    }
}

@Composable
private fun LabelWithInfo(label: String, info: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = PaperTheme.typography.label, color = PaperTheme.colors.textSecondary)
        PaperInfoButton(info)
        Box(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** 证书确认（和 SSH 第一次连接一样） */
@Composable
private fun CertView(p: ProbeResult, changed: Boolean, error: String?) {
    val c = PaperTheme.colors
    val s = PaperTheme.spacing
    Text(tr("server.verify.title"), style = PaperTheme.typography.title)
    Text(tr("server.verify.message"), style = PaperTheme.typography.body, color = c.textSecondary)
    if (changed) PaperAlert(Tone.Warning, tr("server.err.fingerprint"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        PaperIcon(osIcon(p.hello.os), size = PaperTheme.sizes.icon, tint = c.iconSecondary)
        Text(
            listOf(p.hello.hostname, osName(p.hello.os), ServerAddress.display(p.url)).filter { it.isNotEmpty() }.joinToString(" · "),
            style = PaperTheme.typography.body,
            color = c.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = s.md),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(s.xs)) {
        Text(tr("server.verify.fingerprint"), style = PaperTheme.typography.label, color = c.textSecondary)
        SelectionContainer {
            Text(
                groupedFingerprint(p.fingerprintDisplay),
                style = PaperTheme.typography.mono,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.bgCode, RoundedCornerShape(PaperTheme.radius.lg))
                    .padding(s.lg),
            )
        }
    }
    error?.let { PaperAlert(Tone.Danger, it) }
}

/** 32 组指纹分四行，方便逐组核对（手机屏窄） */
private fun groupedFingerprint(s: String): String {
    val groups = s.split(' ').filter { it.isNotEmpty() }
    if (groups.size <= 8) return s
    return groups.chunked(8).joinToString("\n") { it.joinToString(" ") }
}

/** 登录时给服务器看的设备名（显示在服务器的「已登录的设备」里） */
private fun deviceName(): String {
    val ctx = AppModel.context
    val n = runCatching { Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
    return n?.takeIf { it.isNotBlank() } ?: (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL)
}
