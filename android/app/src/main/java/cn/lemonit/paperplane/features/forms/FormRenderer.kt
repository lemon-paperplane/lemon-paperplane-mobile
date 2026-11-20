package cn.lemonit.paperplane.features.forms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import cn.lemonit.paperplane.features.importflow.ServerPathPicker
import cn.lemonit.paperplane.kit.model.FormField
import cn.lemonit.paperplane.kit.model.Option
import cn.lemonit.paperplane.kit.model.boolValue
import cn.lemonit.paperplane.kit.model.objectValue
import cn.lemonit.paperplane.kit.model.stringArray
import cn.lemonit.paperplane.kit.model.stringValue
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperInfoButton
import cn.lemonit.paperplane.ui.components.PaperSelect
import cn.lemonit.paperplane.ui.components.PaperSwitch
import cn.lemonit.paperplane.ui.components.PaperTextArea
import cn.lemonit.paperplane.ui.components.PaperTextField
import cn.lemonit.paperplane.ui.components.SelectOption
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 声明式表单渲染（sdk.FormField，对照 Mac Renderers/FormRenderer）。
 * 字段类型：string | text | code | number | boolean | select | multiselect | keyvalue | list | path，外加密码框（password / secret 或
 * 名字像密码的 string）。手机上字段名在控件上方；info 放进 i 气泡；advanced 字段折叠进「更多」。
 *
 * 值是不可变 Map：每改一个字段回调一份新的。提交前用 [FormValues.missingRequired] / [FormValues.normalized]。
 *
 * ```
 * var values by remember { mutableStateOf(FormValues.initial(fields, emptyMap())) }
 * FormFields(fields, values, { values = it }, optionValues = module.optionValues, showErrors = tried)
 * ```
 *
 * @param optionValues select / multiselect 的 optionsFrom 动态选项（模块分析结果、live 选项）
 * @param showErrors 显示必填未填的错误（点过提交后再打开）
 */
@Composable
fun FormFields(
    fields: List<FormField>,
    values: Map<String, JsonElement>,
    onValuesChange: (Map<String, JsonElement>) -> Unit,
    modifier: Modifier = Modifier,
    optionValues: Map<String, List<Option>> = emptyMap(),
    showErrors: Boolean = false,
    enabled: Boolean = true,
) {
    val basic = fields.filter { !it.advanced }
    val advanced = fields.filter { it.advanced }
    val missing = if (showErrors) FormValues.missingRequired(fields, values).toSet() else emptySet()
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val set: (String, JsonElement) -> Unit = { k, v -> onValuesChange(values + (k to v)) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.lg)) {
        for (f in basic) {
            androidx.compose.runtime.key(f.key) {
                FormFieldRow(f, values, set, FormValues.options(f, optionValues), f.key in missing, enabled)
            }
        }
        if (advanced.isNotEmpty()) {
            Row(
                Modifier
                    .defaultMinSize(minHeight = PaperTheme.sizes.touchTarget)
                    .clickable(role = Role.Button) { showAdvanced = !showAdvanced },
                horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PaperIcon(if (showAdvanced) "chevron-down" else "chevron-right", size = PaperTheme.sizes.iconSmall, tint = PaperTheme.colors.iconSecondary)
                Text(tr("form.more"), style = PaperTheme.typography.body, color = PaperTheme.colors.textSecondary)
            }
            if (showAdvanced || advanced.any { it.key in missing }) {
                for (f in advanced) {
                    androidx.compose.runtime.key(f.key) {
                        FormFieldRow(f, values, set, FormValues.options(f, optionValues), f.key in missing, enabled)
                    }
                }
            }
        }
    }
}

/** 一个字段：标题在上、控件在下；布尔字段是一行「标题 … 开关」 */
@Composable
private fun FormFieldRow(
    field: FormField,
    values: Map<String, JsonElement>,
    set: (String, JsonElement) -> Unit,
    options: List<Option>,
    invalid: Boolean,
    enabled: Boolean,
) {
    val key = field.key
    if (field.type == "boolean") {
        val on = values[key]?.boolValue ?: false
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = PaperTheme.sizes.touchTarget)
                .clickable(enabled = enabled, role = Role.Switch) { set(key, JsonPrimitive(!on)) },
            horizontalArrangement = Arrangement.spacedBy(PaperTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(field.title, style = PaperTheme.typography.body, color = PaperTheme.colors.textPrimary, modifier = Modifier.weight(1f, fill = false))
                if (!field.info.isNullOrEmpty()) PaperInfoButton(field.info)
            }
            PaperSwitch(on, onCheckedChange = { set(key, JsonPrimitive(it)) }, enabled = enabled)
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PaperTheme.spacing.xs)) {
        FieldLabel(field.title, required = field.required, info = field.info, invalid = invalid)
        FieldControl(field, values, set, options, invalid, enabled)
    }
}

@Composable
private fun FieldControl(
    field: FormField,
    values: Map<String, JsonElement>,
    set: (String, JsonElement) -> Unit,
    options: List<Option>,
    invalid: Boolean,
    enabled: Boolean,
) {
    val key = field.key
    val text = values[key]?.stringValue.orEmpty()
    val err = if (invalid) "" else null
    val placeholder = field.placeholder?.takeIf { it.isNotEmpty() }
    when (field.type) {
        "text" -> PaperTextArea(text, { set(key, JsonPrimitive(it)) }, placeholder = placeholder, error = err, enabled = enabled)
        "code" -> PaperTextArea(text, { set(key, JsonPrimitive(it)) }, placeholder = placeholder, error = err, enabled = enabled, mono = true, minLines = 4)
        "number" -> PaperTextField(
            text,
            { set(key, if (it.isBlank()) JsonNull else JsonPrimitive(it.trim())) },
            placeholder = placeholder,
            error = err,
            enabled = enabled,
            mono = true,
            keyboardType = KeyboardType.Decimal,
        )
        "select" -> {
            // 非必填时首项为「无」；必填时没有空选项，未选显示占位
            val opts = (if (field.required) emptyList() else listOf(SelectOption("", tr("form.none")))) +
                options.map { SelectOption(it.value, it.displayLabel) }
            PaperSelect(
                opts,
                selected = text.takeIf { it.isNotEmpty() || !field.required },
                onSelect = { set(key, JsonPrimitive(it)) },
                title = field.title,
                placeholder = placeholder ?: tr("form.choose"),
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        "multiselect" -> MultiSelectEditor(options, arrayOf(values[key]), { set(key, JsonArray(it.map { s -> JsonPrimitive(s) })) }, enabled = enabled)
        "keyvalue" -> KeyValueEditor(
            pairs = values[key]?.objectValue.orEmpty().map { (k, v) -> k to (v.stringValue ?: "") }.sortedBy { it.first },
            onChange = { pairs -> set(key, JsonObject(pairs.filter { it.first.isNotEmpty() }.associate { it.first to JsonPrimitive(it.second) })) },
            enabled = enabled,
        )
        "list" -> StringListEditor(arrayOf(values[key]), { set(key, JsonArray(it.map { s -> JsonPrimitive(s) })) }, placeholder = placeholder.orEmpty(), enabled = enabled)
        "path" -> PaperTextField(
            text,
            { set(key, JsonPrimitive(it)) },
            placeholder = placeholder,
            error = err,
            enabled = enabled,
            mono = true,
            trailing = {
                PaperButton(tr("form.browse"), onClick = {
                    ServerPathPicker.pick(field.title, start = text.takeIf { it.isNotEmpty() }) { set(key, JsonPrimitive(it)) }
                }, size = ButtonSize.Small, enabled = enabled, modifier = Modifier.padding(end = PaperTheme.spacing.xs))
            },
        )
        else -> PaperTextField(
            text,
            { set(key, JsonPrimitive(it)) },
            placeholder = placeholder,
            error = err,
            enabled = enabled,
            password = field.isSecret,
        )
    }
}

/** 数组值；兼容逗号或换行分隔的字符串 */
private fun arrayOf(v: JsonElement?): List<String> {
    v?.stringArray?.let { return it }
    val s = v?.stringValue.orEmpty()
    if (s.isEmpty()) return emptyList()
    return s.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
}
