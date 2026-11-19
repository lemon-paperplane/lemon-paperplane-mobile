package cn.lemonit.paperplane.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 确认面板（底部弹出）：标题、说明、取消 / 确定。onConfirm 是挂起函数：执行中确定按钮转圈，
 * 成功后收起，抛错时在面板里显示错误（不关面板，可以重试）。
 *
 * `if (ask) PaperConfirmSheet(tr("files.delete.title"), message = …, danger = true, onDismiss = { ask = false }) { api.delete(path) }`
 */
@Composable
fun PaperConfirmSheet(
    title: String,
    onDismiss: () -> Unit,
    message: String? = null,
    confirmText: String = tr("common.ok"),
    danger: Boolean = false,
    onCancel: (() -> Unit)? = null,
    onConfirm: suspend () -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PaperBottomSheet(
        onDismiss = {
            if (!confirmed) onCancel?.invoke()
            onDismiss()
        },
        title = title,
        showClose = false,
    ) { dismiss ->
        if (!message.isNullOrEmpty()) {
            Text(message, style = PaperTheme.typography.body, color = PaperTheme.colors.textSecondary)
        }
        error?.let { PaperAlert(Tone.Danger, it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md)) {
            PaperButton(tr("common.cancel"), onClick = { dismiss() }, modifier = Modifier.weight(1f))
            PaperButton(
                confirmText,
                kind = if (danger) ButtonKind.Danger else ButtonKind.Primary,
                loading = busy,
                modifier = Modifier.weight(1f),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            onConfirm()
                            confirmed = true
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
            )
        }
    }
}

/**
 * 单行输入面板（新建、重命名）：自动聚焦、键盘上的完成直接提交；validate 返回错误文字时不能提交。
 * onSubmit 抛错时显示在面板里。
 *
 * `if (rename) PaperInputSheet(tr("workspace.rename"), initial = ws.name, onDismiss = { rename = false }) { api.renameWorkspace(ws.id, it) }`
 */
@Composable
fun PaperInputSheet(
    title: String,
    onDismiss: () -> Unit,
    label: String? = null,
    initial: String = "",
    placeholder: String? = null,
    confirmText: String = tr("common.ok"),
    password: Boolean = false,
    mono: Boolean = false,
    validate: ((String) -> String?)? = null,
    onSubmit: suspend (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val invalid = validate?.invoke(text.trim())
    PaperBottomSheet(onDismiss = onDismiss, title = title, showClose = false) { dismiss ->
        fun submit() {
            val v = text.trim()
            if (v.isEmpty() || invalid != null || busy) return
            busy = true
            error = null
            scope.launch {
                try {
                    onSubmit(v)
                    dismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: tr("common.failed")
                } finally {
                    busy = false
                }
            }
        }
        PaperTextField(
            text,
            { text = it },
            label = label,
            placeholder = placeholder,
            password = password,
            mono = mono,
            clearable = !password,
            error = if (text.isNotEmpty()) invalid else null,
            focusRequester = focus,
            onSubmit = { submit() },
        )
        error?.let { PaperAlert(Tone.Danger, it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md)) {
            PaperButton(tr("common.cancel"), onClick = { dismiss() }, modifier = Modifier.weight(1f))
            PaperButton(
                confirmText,
                kind = ButtonKind.Primary,
                loading = busy,
                enabled = text.isNotBlank() && invalid == null,
                modifier = Modifier.weight(1f),
                onClick = { submit() },
            )
        }
    }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { focus.requestFocus() }
    }
}

/**
 * 全局对话框入口（对照 Mac Dialogs）：任何地方（包括非 Composable 代码）都能弹，
 * 由界面根上的 [PaperDialogHost] 显示。
 *
 * ```
 * Dialogs.confirm(tr("card.stopAll"), danger = true) { api.stopAll(p.id) }
 * Dialogs.input(tr("workspace.new"), label = tr("common.name")) { name -> api.createWorkspace(name) }
 * Dialogs.present { dismiss -> PaperBottomSheet(onDismiss = dismiss, title = …) { … } }
 * ```
 */
object Dialogs {
    class Entry internal constructor(val id: String, internal val content: @Composable (dismiss: () -> Unit) -> Unit)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** 弹出任意面板；同 id 的会被替换。content 收到 dismiss，关闭时调用。 */
    fun present(id: String = UUID.randomUUID().toString(), content: @Composable (dismiss: () -> Unit) -> Unit): String {
        _entries.update { list -> list.filterNot { it.id == id } + Entry(id, content) }
        return id
    }

    fun dismiss(id: String) = _entries.update { list -> list.filterNot { it.id == id } }

    /** 切换服务器等场景：全部关掉 */
    fun dismissAll() = _entries.update { emptyList() }

    fun contains(id: String): Boolean = _entries.value.any { it.id == id }

    fun confirm(
        title: String,
        message: String? = null,
        confirmText: String? = null,
        danger: Boolean = false,
        onCancel: (() -> Unit)? = null,
        onConfirm: suspend () -> Unit,
    ): String = present { dismiss ->
        PaperConfirmSheet(
            title = title,
            onDismiss = dismiss,
            message = message,
            confirmText = confirmText ?: tr("common.ok"),
            danger = danger,
            onCancel = onCancel,
            onConfirm = onConfirm,
        )
    }

    fun input(
        title: String,
        label: String? = null,
        initial: String = "",
        placeholder: String? = null,
        confirmText: String? = null,
        validate: ((String) -> String?)? = null,
        onSubmit: suspend (String) -> Unit,
    ): String = present { dismiss ->
        PaperInputSheet(
            title = title,
            onDismiss = dismiss,
            label = label,
            initial = initial,
            placeholder = placeholder,
            confirmText = confirmText ?: tr("common.ok"),
            validate = validate,
            onSubmit = onSubmit,
        )
    }
}

/** 显示 [Dialogs] 弹出的面板。放在界面根上一次（在 PaperplaneTheme 里面）。 */
@Composable
fun PaperDialogHost() {
    val list by Dialogs.entries.collectAsState()
    for (e in list) {
        key(e.id) { e.content { Dialogs.dismiss(e.id) } }
    }
}
