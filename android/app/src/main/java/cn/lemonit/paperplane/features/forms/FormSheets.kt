package cn.lemonit.paperplane.features.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cn.lemonit.paperplane.kit.model.FormField
import cn.lemonit.paperplane.kit.model.Option
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.PaperAlert
import cn.lemonit.paperplane.ui.components.PaperBottomSheet
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/** 通用表单面板（插件动作参数、插件提问、生成密钥等小表单）。对照 Mac Dialogs.form + Renderers/FormRenderer */
object FormSheets {
    /**
     * 弹出底部表单面板，返回面板 id（可以 [Dialogs.dismiss]）。onSubmit 收到整理过的值（[FormValues.normalized]）；
     * 抛错时在面板里显示错误、不关闭。必填没填时标红、不提交。
     *
     * @param values 初始值（字段 key → 值），没给的字段取默认值
     * @param optionValues select / multiselect 的 optionsFrom 动态选项
     * @param id 同 id 的面板会被替换（插件提问用 prompt id 去重）
     * @param onCancel 没提交就关掉（取消、下滑、返回键）时回调
     */
    fun show(
        title: String,
        fields: List<FormField>,
        values: Map<String, JsonElement> = emptyMap(),
        message: String? = null,
        confirmText: String? = null,
        danger: Boolean = false,
        optionValues: Map<String, List<Option>> = emptyMap(),
        id: String? = null,
        onCancel: (() -> Unit)? = null,
        onSubmit: suspend (Map<String, JsonElement>) -> Unit,
    ): String = Dialogs.present(id ?: UUID.randomUUID().toString()) { dismiss ->
        FormSheet(
            title = title,
            fields = fields,
            initial = values,
            message = message,
            confirmText = confirmText ?: tr("common.ok"),
            danger = danger,
            optionValues = optionValues,
            onDismiss = dismiss,
            onCancel = onCancel,
            onSubmit = onSubmit,
        )
    }
}

/** 表单面板本体（[FormSheets.show] 弹出的就是它） */
@Composable
fun FormSheet(
    title: String,
    fields: List<FormField>,
    initial: Map<String, JsonElement>,
    onDismiss: () -> Unit,
    onSubmit: suspend (Map<String, JsonElement>) -> Unit,
    message: String? = null,
    confirmText: String = tr("common.ok"),
    danger: Boolean = false,
    optionValues: Map<String, List<Option>> = emptyMap(),
    onCancel: (() -> Unit)? = null,
) {
    var submitted by remember { mutableStateOf(false) }
    PaperBottomSheet(
        onDismiss = {
            if (!submitted) onCancel?.invoke()
            onDismiss()
        },
        title = title,
        showClose = false,
    ) { dismiss ->
        FormSheetBody(
            fields = fields,
            initial = initial,
            message = message,
            confirmText = confirmText,
            danger = danger,
            optionValues = optionValues,
            onCancel = dismiss,
            onSubmit = { v ->
                onSubmit(v)
                submitted = true
                dismiss()
            },
        )
    }
}

/** 表单面板的内容（说明、字段、错误、取消 / 确定）；截图测试里放进 PaperSheetPreview */
@Composable
fun ColumnScope.FormSheetBody(
    fields: List<FormField>,
    initial: Map<String, JsonElement>,
    onCancel: () -> Unit,
    onSubmit: suspend (Map<String, JsonElement>) -> Unit,
    message: String? = null,
    confirmText: String = tr("common.ok"),
    danger: Boolean = false,
    optionValues: Map<String, List<Option>> = emptyMap(),
) {
    var values by remember { mutableStateOf(FormValues.initial(fields, initial)) }
    var showErrors by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    if (!message.isNullOrEmpty()) {
        Text(message, style = PaperTheme.typography.body, color = PaperTheme.colors.textSecondary)
    }
    FormFields(fields, values, { values = it }, optionValues = optionValues, showErrors = showErrors, enabled = !busy)
    error?.let { PaperAlert(Tone.Danger, it) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.md)) {
        PaperButton(tr("common.cancel"), onClick = onCancel, modifier = Modifier.weight(1f))
        PaperButton(
            confirmText,
            kind = if (danger) ButtonKind.Danger else ButtonKind.Primary,
            loading = busy,
            modifier = Modifier.weight(1f),
            onClick = {
                if (FormValues.missingRequired(fields, values).isNotEmpty()) {
                    showErrors = true
                    return@PaperButton
                }
                busy = true
                error = null
                scope.launch {
                    try {
                        onSubmit(FormValues.normalized(fields, values))
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
