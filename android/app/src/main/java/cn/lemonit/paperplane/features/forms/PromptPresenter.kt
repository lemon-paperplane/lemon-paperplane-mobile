package cn.lemonit.paperplane.features.forms

import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.kit.model.Prompt
import cn.lemonit.paperplane.kit.respondPrompt
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.PaperConfirmSheet
import cn.lemonit.paperplane.ui.i18n.tr
import kotlinx.coroutines.launch

/**
 * 插件发起的提问（ui.prompt 事件：插件 host/prompt、git 凭据等）：按 Fields 弹出表单面板，回答走 ui.respond；
 * 没有字段时是确认面板。不回答就关掉 = 取消。对照 Mac Core/PromptPresenter。
 */
object PromptPresenter {
    fun dialogId(promptId: String) = "prompt-$promptId"

    fun present(prompt: Prompt) {
        val id = dialogId(prompt.id)
        if (Dialogs.contains(id)) return
        val cancel: () -> Unit = {
            AppModel.scope.launch { runCatching { AppModel.api().respondPrompt(prompt.id, cancelled = true, values = emptyMap()) } }
        }
        val ok = prompt.okText?.takeIf { it.isNotEmpty() } ?: tr("common.ok")
        if (prompt.fields.isEmpty()) {
            Dialogs.present(id) { dismiss ->
                PaperConfirmSheet(
                    title = prompt.title,
                    onDismiss = dismiss,
                    message = prompt.message,
                    confirmText = ok,
                    danger = prompt.danger,
                    onCancel = cancel,
                ) {
                    AppModel.api().respondPrompt(prompt.id, cancelled = false, values = emptyMap())
                }
            }
        } else {
            FormSheets.show(
                title = prompt.title,
                fields = prompt.fields,
                message = prompt.message,
                confirmText = ok,
                danger = prompt.danger,
                id = id,
                onCancel = cancel,
            ) { values ->
                AppModel.api().respondPrompt(prompt.id, cancelled = false, values = values)
            }
        }
    }

    /** 已在别处回答（另一台设备、超时）：直接收起，不再回答 */
    fun dismiss(promptId: String) = Dialogs.dismiss(dialogId(promptId))
}
