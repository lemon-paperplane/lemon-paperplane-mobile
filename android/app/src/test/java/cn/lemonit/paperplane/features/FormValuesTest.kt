package cn.lemonit.paperplane.features

import cn.lemonit.paperplane.features.forms.FormValues
import cn.lemonit.paperplane.features.project.PluginActions
import cn.lemonit.paperplane.features.toolchains.compareVersions
import cn.lemonit.paperplane.kit.model.ActionInfo
import cn.lemonit.paperplane.kit.model.FormField
import cn.lemonit.paperplane.kit.model.Option
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 表单值处理、插件动作筛选、版本排序（纯 JVM） */
class FormValuesTest {
    private val fields = listOf(
        FormField(key = "name", title = "名称", type = "string", required = true),
        FormField(key = "port", title = "端口", type = "number", default = JsonPrimitive(8080)),
        FormField(key = "debug", title = "调试", type = "boolean", required = true),
        FormField(key = "device", title = "设备", type = "select", options = listOf(Option("a", "A")), optionsFrom = "devices"),
        FormField(key = "args", title = "参数", type = "list", required = true),
    )

    @Test
    fun initialFillsDefaults() {
        val v = FormValues.initial(fields, mapOf("name" to JsonPrimitive("x")))
        assertEquals(JsonPrimitive("x"), v["name"])
        assertEquals(JsonPrimitive(8080), v["port"])
        assertTrue("debug" !in v)
    }

    @Test
    fun missingRequiredSkipsBooleans() {
        assertEquals(listOf("name", "args"), FormValues.missingRequired(fields, emptyMap()))
        assertEquals(listOf("args"), FormValues.missingRequired(fields, mapOf("name" to JsonPrimitive("x"), "args" to JsonArray(emptyList()))))
    }

    @Test
    fun normalizedConvertsNumbersAndDropsNulls() {
        val v = FormValues.normalized(
            fields,
            mapOf("port" to JsonPrimitive("3000"), "name" to JsonNull, "device" to JsonPrimitive("a"), "x" to JsonPrimitive("1.5")),
        )
        assertEquals(JsonPrimitive(3000L), v["port"])
        assertTrue("name" !in v)
        assertEquals(JsonPrimitive("a"), v["device"])
        // 非数字字段的字符串原样保留
        assertEquals(JsonPrimitive("1.5"), v["x"])
        assertTrue("port" !in FormValues.normalized(fields, mapOf("port" to JsonPrimitive("abc"))))
    }

    @Test
    fun optionsMergeDynamic() {
        val f = fields[3]
        val opts = FormValues.options(f, mapOf("devices" to listOf(Option("a", "A2"), Option("b", "B"))))
        assertEquals(listOf("a", "b"), opts.map { it.value })
        assertEquals("A", opts[0].displayLabel)
    }

    @Test
    fun actionsApplicable() {
        val acts = listOf(
            ActionInfo(id = "a", plugin = "python", facets = listOf("python")),
            ActionInfo(id = "b", plugin = "nodejs", facets = listOf("vite")),
            ActionInfo(id = "c", plugin = "git"),
        )
        assertEquals(listOf("a", "c"), PluginActions.applicable(acts, setOf("python", "uv"), setOf("python", "git")).map { it.id })
    }

    @Test
    fun versionOrder() {
        val sorted = listOf("17.0.2", "21", "17.0.10", "8u402").sortedWith { a, b -> compareVersions(b, a) }
        assertEquals(listOf("21", "17.0.10", "17.0.2", "8u402"), sorted)
    }
}
