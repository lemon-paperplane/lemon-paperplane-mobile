package cn.lemonit.paperplane.features.forms

import cn.lemonit.paperplane.kit.model.FormField
import kotlinx.serialization.json.JsonElement

/** 通用表单面板（插件动作参数、运行配置之外的小表单、删除工作区等）。对照 Mac Dialogs.form + Renderers/FormRenderer */
object FormSheets {
    /**
     * 弹出底部表单面板。onSubmit 抛错时在面板里显示错误、不关闭。
     * @param values 初始值（字段 key → 值）
     */
    fun show(
        title: String,
        fields: List<FormField>,
        values: Map<String, JsonElement> = emptyMap(),
        message: String? = null,
        confirmText: String? = null,
        danger: Boolean = false,
        onSubmit: suspend (Map<String, JsonElement>) -> Unit,
    ) {}
}
