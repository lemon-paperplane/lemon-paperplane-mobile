package cn.lemonit.paperplane.features.forms

import cn.lemonit.paperplane.kit.model.FormField
import cn.lemonit.paperplane.kit.model.Option
import cn.lemonit.paperplane.kit.model.isEmptyValue
import cn.lemonit.paperplane.kit.model.stringValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** 声明式表单的值处理（对照 Mac FormView 的 initialValues / missingRequired / normalized）。 */
object FormValues {
    /** 没给值的字段填默认值 */
    fun initial(fields: List<FormField>, values: Map<String, JsonElement>): Map<String, JsonElement> {
        val out = LinkedHashMap(values)
        for (f in fields) {
            if (out[f.key] == null) f.default?.takeIf { it !is JsonNull }?.let { out[f.key] = it }
        }
        return out
    }

    /** 必填但没填的字段 key（布尔不算） */
    fun missingRequired(fields: List<FormField>, values: Map<String, JsonElement>): List<String> =
        fields.filter { f -> f.required && f.type != "boolean" && values[f.key].isEmptyValue }.map { it.key }

    /** 提交前整理：去掉 null；数字字段的字符串转成数字（转不了的丢掉） */
    fun normalized(fields: List<FormField>, values: Map<String, JsonElement>): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in values) {
            if (v is JsonNull) continue
            val f = fields.firstOrNull { it.key == k }
            if (f?.type == "number" && v is JsonPrimitive && v.isString) {
                number(v.content)?.let { out[k] = it }
                continue
            }
            out[k] = v
        }
        return out
    }

    /** 字符串 → 数字（整数不带小数点）；空或不是数字返回 null */
    fun number(s: String): JsonPrimitive? {
        val t = s.trim()
        if (t.isEmpty()) return null
        t.toLongOrNull()?.let { return JsonPrimitive(it) }
        return t.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { JsonPrimitive(it) }
    }

    /** 字段的选项：自带的 + optionsFrom 指向的动态选项（去重） */
    fun options(field: FormField, optionValues: Map<String, List<Option>>): List<Option> {
        val out = field.options.toMutableList()
        val from = field.optionsFrom
        if (!from.isNullOrEmpty()) {
            for (o in optionValues[from].orEmpty()) if (out.none { it.value == o.value }) out += o
        }
        return out
    }

    /** 显示用的字符串值 */
    fun text(values: Map<String, JsonElement>, key: String): String = values[key]?.stringValue.orEmpty()
}
