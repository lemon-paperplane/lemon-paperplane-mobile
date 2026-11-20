package cn.lemonit.paperplane.features.forms

import cn.lemonit.paperplane.kit.model.Prompt

/** 插件发起的表单提问（ui.prompt 事件）：弹出表单面板，提交后 ui.respond。对照 Mac Core/PromptPresenter + Renderers/FormRenderer */
object PromptPresenter {
    fun present(prompt: Prompt) {}

    fun dismiss(promptId: String) {}
}
