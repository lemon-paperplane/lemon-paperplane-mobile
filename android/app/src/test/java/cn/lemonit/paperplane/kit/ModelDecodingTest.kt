package cn.lemonit.paperplane.kit

import cn.lemonit.paperplane.kit.model.ActionLocation
import cn.lemonit.paperplane.kit.model.AppSettings
import cn.lemonit.paperplane.kit.model.Contributions
import cn.lemonit.paperplane.kit.model.DaemonEvent
import cn.lemonit.paperplane.kit.model.EventPayload
import cn.lemonit.paperplane.kit.model.EventType
import cn.lemonit.paperplane.kit.model.GoTime
import cn.lemonit.paperplane.kit.model.LenientMap
import cn.lemonit.paperplane.kit.model.ModuleAnalysis
import cn.lemonit.paperplane.kit.model.PluginInfo
import cn.lemonit.paperplane.kit.model.Project
import cn.lemonit.paperplane.kit.model.ProjectSummary
import cn.lemonit.paperplane.kit.model.Prompt
import cn.lemonit.paperplane.kit.model.Run
import cn.lemonit.paperplane.kit.model.RunBrief
import cn.lemonit.paperplane.kit.model.RunConfig
import cn.lemonit.paperplane.kit.model.SystemInfo
import cn.lemonit.paperplane.kit.model.TaskInfo
import cn.lemonit.paperplane.kit.model.VCSLog
import cn.lemonit.paperplane.kit.model.VCSStatus
import cn.lemonit.paperplane.kit.model.VCSSubmoduleUpdateArgs
import cn.lemonit.paperplane.kit.model.ViewLocation
import cn.lemonit.paperplane.kit.model.ViewRenderResult
import cn.lemonit.paperplane.kit.model.Workspace
import cn.lemonit.paperplane.kit.model.arrayValue
import cn.lemonit.paperplane.kit.model.boolValue
import cn.lemonit.paperplane.kit.model.decodeAs
import cn.lemonit.paperplane.kit.model.get
import cn.lemonit.paperplane.kit.model.intValue
import cn.lemonit.paperplane.kit.model.isEmptyValue
import cn.lemonit.paperplane.kit.model.isJsonNull
import cn.lemonit.paperplane.kit.model.jsonOf
import cn.lemonit.paperplane.kit.model.jsonString
import cn.lemonit.paperplane.kit.model.stringArray
import cn.lemonit.paperplane.kit.model.stringMap
import cn.lemonit.paperplane.kit.model.stringValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 对照 Mac ModelDecodingTests.swift */
class ModelDecodingTest {
    private fun <T> list(name: String, item: kotlinx.serialization.KSerializer<T>): List<T> =
        Fixtures.decode(name, ListSerializer(item))

    @Test
    fun systemInfo() {
        val info = Fixtures.decode("system.info.json", SystemInfo.serializer())
        assertEquals("0.1.0", info.version)
        assertEquals(2, info.pluginProcs)
        assertEquals(21.4, info.memoryMB, 0.001)
        // 纳秒精度、带时区
        val started = info.startedAt!!
        assertEquals(1_794_965_952.123, started.toEpochMilli() / 1000.0, 0.01)
    }

    @Test
    fun workspacesWithNullSliceAndZeroTime() {
        val ws = list("workspace.list.json", Workspace.serializer())
        assertEquals(2, ws.size)
        assertEquals(listOf("p1", "p2"), ws[0].projectIds)
        assertEquals(emptyList<String>(), ws[1].projectIds)
        assertTrue(GoTime.isZero(ws[1].createdAt))
        assertFalse(GoTime.isZero(ws[0].createdAt))
    }

    @Test
    fun projectSummaries() {
        val list = list("project.list.json", ProjectSummary.serializer())
        assertEquals(2, list.size)
        val p = list[0]
        assertEquals(listOf("gradle", "spring-boot"), p.facets.map { it.id })
        assertEquals(listOf("http://localhost:8080"), p.runs.first().urls)
        assertEquals(1, p.vcs?.ahead)
        assertEquals(0, p.vcs?.conflicts)
        assertEquals(listOf("jdk 17"), p.missingToolchains)
        assertTrue(p.hasActiveRun)
        val q = list[1]
        assertEquals(emptyList<Any>(), q.facets)
        assertEquals(emptyList<Any>(), q.configs)
        assertNull(q.vcs)
        assertFalse(q.hasActiveRun)
    }

    @Test
    fun projectDetail() {
        val p = Fixtures.decode("project.get.json", Project.serializer())
        assertEquals(1, p.modules.size)
        val m = p.modules[0]
        assertEquals(3, m.facets.size)
        assertEquals("8.12", m.facets[1].data?.get("version")?.stringValue)
        assertEquals("8.12", m.analysis["gradle"]?.info?.first()?.value)
        assertEquals(emptyList<Any>(), m.analysis["spring-boot"]?.info)
        assertEquals(listOf("dev", "prod"), m.optionValues["profiles"]?.map { it.value })
        assertEquals(1, m.problems.size)
        assertEquals(true, m.toolchains["gradle"]?.missing)
        assertEquals(2, m.runConfigs.size)
        val c1 = m.runConfigs[0]
        assertTrue(c1.default)
        assertEquals(8080, c1.options["port"]?.intValue)
        assertEquals(listOf("dev"), c1.options["profiles"]?.stringArray)
        assertEquals(emptyMap<String, Any>(), m.runConfigs[1].options)
        assertTrue(m.runConfigs[1].edited)
        assertEquals("17", p.settings?.toolchains?.get("jdk")?.constraint)
        assertEquals(2, p.allRunConfigs.size)
        assertEquals(setOf("java", "gradle", "spring-boot"), p.pluginIds)
        assertTrue(GoTime.isZero(p.analyzedAt))
        assertEquals("c2", p.runConfig("c2")?.id)
        assertEquals("m1", p.module("m1")?.id)
        assertNull(p.module(null))
    }

    @Test
    fun runs() {
        val runs = list("run.list.json", Run.serializer())
        assertEquals(2, runs.size)
        assertEquals("8080", runs[0].diagnoses.first().fixes.first().params["port"])
        assertEquals(true, runs[0].diagnoses.first().fixes.first().recommended)
        assertNotNull(runs[0].readyAt)
        assertNull(runs[0].exitCode)
        assertEquals(1, runs[1].exitCode)
        assertTrue(runs[0].isActive)
        assertFalse(runs[1].isActive)
    }

    @Test
    fun tasks() {
        val tasks = list("task.list.json", TaskInfo.serializer())
        assertEquals(22.1, tasks[0].percent, 0.001)
        assertTrue(tasks[0].isRunning)
        assertEquals("已清理", tasks[1].result?.get("message")?.stringValue)
    }

    @Test
    fun contributions() {
        val c = Fixtures.decode("ui.contributions.json", Contributions.serializer())
        assertEquals(4, c.runConfigTypes.first().options.size)
        assertEquals(8080, c.runConfigTypes.first().options[3].default?.intValue)
        assertEquals("running", c.views(ViewLocation.RUN_PANEL).first().runState)
        assertTrue(c.views(ViewLocation.SIDEBAR).first().isWebview)
        // locations 为空的动作默认挂 project.menu
        assertEquals(listOf("clean", "deps"), c.actions(ActionLocation.PROJECT_MENU).map { it.id })
        assertEquals(listOf("clean"), c.actions(ActionLocation.CARD_MENU).map { it.id })
        assertEquals(true, c.settings.first().values["daemon"]?.boolValue)
    }

    @Test
    fun plugins() {
        val p = list("plugin.list.json", PluginInfo.serializer())
        assertEquals(12, p[0].contributes?.diagnostics)
        assertEquals("engine mismatch", p[1].error)
        assertEquals(emptyList<String>(), p[1].contributes?.facets)
    }

    @Test
    fun settings() {
        val s = Fixtures.decode("settings.get.json", AppSettings.serializer())
        assertEquals("dark", s.theme)
        assertEquals(JsonPrimitive(true), s.pluginSettings["gradle"]?.get("daemon"))
        assertEquals(emptyList<String>(), s.disabledPlugins)
        assertNull(s.terminal)
    }

    @Test
    fun viewRender() {
        val r = Fixtures.decode("view.render.json", ViewRenderResult.serializer())
        assertEquals(5000, r.refreshMs)
        assertEquals(8, r.root.children.size)
        val table = r.root.children[2]
        assertTrue(table.selectable)
        assertEquals(true, table.columns[1].mono)
        assertEquals("link", table.rows[0].actions.first().style)
        assertEquals(-1.0, r.root.children[4].value, 0.0)
        assertEquals(JsonPrimitive("x"), r.root.children[5].values?.get("name"))
        assertEquals(1, r.root.children[6].rows[0].children.size)
        assertEquals(1, r.state?.get("page")?.intValue)
    }

    @Test
    fun errorAndNullEnvelope() {
        try {
            Fixtures.result("error.json", 400)
            fail("应当报错")
        } catch (e: ApiException) {
            assertEquals("user_error", e.code)
            assertEquals("工程不存在", e.message)
            assertEquals(400, e.status)
        }
        // 结果为 null：数组类操作给空数组
        val caller = FakeOpCaller { _, _ -> Fixtures.result("null.json") }
        assertEquals(emptyList<ProjectSummary>(), blocking { caller.projects() })
        assertEquals(emptyMap<String, Any>(), blocking { caller.liveOptions("p", null, listOf("k")) })
        blocking { caller.removeProject("p") } // send 不关心结果
        // 结果类型不对：包成 bad_response
        val bad = FakeOpCaller { _, _ -> JsonPrimitive("oops") }
        try {
            blocking { bad.getProject("p") }
            fail("应当报错")
        } catch (e: ApiException) {
            assertEquals(ApiException.BAD_RESPONSE, e.code)
            assertTrue(e.message.startsWith("project.get"))
        }
    }

    @Test
    fun sseEventsFixture() {
        val events = sseMessages(Fixtures.text("events.sse.txt")).map { DaemonEvent.parse(it.second)!! }
        assertEquals(listOf("hello", "stats", "prompt", "project.removed", "toolchains.changed"), events.map { it.type })
        val hello = events[0].decode(SystemInfo.serializer())
        assertEquals("en", hello.locale)
        val stats = events[1].decode(ListSerializer(RunBrief.serializer()))
        assertEquals(2.5, stats.first().cpu, 0.0)
        val prompt = events[2].decode(Prompt.serializer())
        assertEquals(2, prompt.fields.size)
        assertTrue(prompt.fields[1].isSecret)
        assertFalse(prompt.fields[0].isSecret)
        assertTrue(events[4].data.isJsonNull)
        // 强类型 payload
        assertTrue(events[0].payload() is EventPayload.Hello)
        assertEquals(2.5, (events[1].payload() as EventPayload.Stats).runs.first().cpu, 0.0)
        assertEquals("git", (events[2].payload() as EventPayload.PromptShown).prompt.plugin)
        assertEquals("p9", (events[3].payload() as EventPayload.ProjectRemoved).projectId)
        assertEquals(EventPayload.ToolchainsChanged, events[4].payload())
        // 不认识的事件、坏 data 不抛异常
        assertTrue(DaemonEvent(type = "future.event").payload() is EventPayload.Unknown)
        assertTrue(DaemonEvent(type = EventType.RUN, data = JsonPrimitive(3)).payload() is EventPayload.Unknown)
        assertNull(DaemonEvent.parse("not json"))
        assertNull(DaemonEvent.parse("{}"))
    }

    @Test
    fun runConfigRoundTrip() {
        val p = Fixtures.decode("project.get.json", Project.serializer())
        val c = p.modules[0].runConfigs[0]
        val text = PaperplaneJson.encodeToString(RunConfig.serializer(), c)
        val back = PaperplaneJson.decodeFromString(RunConfig.serializer(), text)
        assertEquals(c, back)
        // 平铺：没有嵌套的 RunConfiguration 对象
        val obj = PaperplaneJson.parseToJsonElement(text)
        assertNull(obj["mainClass"])
        assertEquals(JsonPrimitive("com.example.App"), obj["options"]?.get("mainClass"))
        assertEquals(JsonPrimitive(true), obj["default"])
    }

    @Test
    fun jsonValueHelpers() {
        val v = jsonObjectOf(
            "a" to 1, "b" to listOf(1, 2), "c" to "x", "d" to JsonNull, "e" to true, "f" to 2.5,
            "g" to mapOf("k" to "v", "n" to 3), "h" to "",
        )
        assertEquals(1, v["a"]?.intValue)
        assertEquals(2, v["b"]?.arrayValue?.size)
        assertEquals(true, v["d"].isJsonNull)
        assertEquals(true, v["e"]?.boolValue)
        assertEquals("1", v["a"]?.stringValue)
        assertEquals("2.5", v["f"]?.stringValue)
        assertEquals(mapOf("k" to "v", "n" to "3"), v["g"]?.stringMap)
        assertTrue(v["h"].isEmptyValue)
        assertTrue(v["d"].isEmptyValue)
        assertFalse(v["a"].isEmptyValue)
        assertEquals("3", JsonPrimitive(3).jsonString)
        assertEquals("2.5", JsonPrimitive(2.5).jsonString)
        val arr: kotlinx.serialization.json.JsonElement = JsonArray(listOf(JsonPrimitive(1)))
        assertNull(arr[3]) // JsonElement 上的下标越界给 null（JsonArray 自己的 get 会抛异常）
        assertEquals(1, arr[0]?.intValue)
        // jsonObjectOf 丢掉 null
        assertEquals(setOf("x"), jsonObjectOf("x" to "1", "y" to null).keys)

        // 参数模型平铺：null 字段不输出
        val flat = argsObject(A.serializer(), A("a.txt"), "projectId" to "p", "root" to null)
        assertEquals(JsonObject(mapOf("path" to JsonPrimitive("a.txt"), "projectId" to JsonPrimitive("p"))), flat)
        assertEquals(A("b", 3), jsonOf(A.serializer(), A("b", 3)).decodeAs(A.serializer()))
    }

    @Test
    fun vcsModels() {
        val json = """
        {"commits":[{"hash":"abc","short":"abc","parents":["def"],"author":"me","date":"2026-11-18T09:00:00+08:00","subject":"init","refs":[{"name":"main","kind":"head"}],"graph":{"lane":0,"lanes":2,"edges":[{"from":0,"to":0,"color":0},{"from":1,"to":0,"color":1}],"color":0}}],"hasMore":true}
        """
        val log = PaperplaneJson.decodeFromString(VCSLog.serializer(), json)
        assertEquals(2, log.commits.first().graph?.edges?.size)
        assertNotNull(log.commits.first().parsedDate)
        val args = jsonOf(VCSSubmoduleUpdateArgs.serializer(), VCSSubmoduleUpdateArgs(initialize = true))
        assertEquals(JsonPrimitive(true), args["init"])
        assertNull(args["paths"])
    }

    @Serializable
    data class A(val path: String, val context: Int? = null)

    @Serializable
    data class Box(val m: LenientMap<String> = emptyMap())

    /** 插件给的 nil 切片会变成 null（如 pom 里没写 profiles 时的 maven.profiles）：不能让整个导入结果解析失败 */
    @Test
    fun optionValuesTolerateNullEntries() {
        val json = """
        {"info": null, "problems": [], "optionValues": {"maven.profiles": null, "jvm.modules": [{"value": ":", "label": "root"}]},
         "at": "2026-11-18T15:00:00.123456789+08:00"}
        """
        val a = PaperplaneJson.decodeFromString(ModuleAnalysis.serializer(), json)
        assertEquals(emptyList<Any>(), a.optionValues["maven.profiles"])
        assertEquals(1, a.optionValues["jvm.modules"]?.size)
        assertEquals(emptyList<Any>(), a.info)
        assertEquals(1_794_985_200_123L, a.at!!.toEpochMilli())

        // 值不是数组/字典时，null 那一项直接丢掉；整个字典 null 时为空
        val b = PaperplaneJson.decodeFromString(Box.serializer(), """{"m": {"a": "x", "b": null}}""")
        assertEquals(mapOf("a" to "x"), b.m)
        assertEquals(emptyMap<String, String>(), PaperplaneJson.decodeFromString(Box.serializer(), """{"m": null}""").m)
        assertEquals(emptyMap<String, String>(), PaperplaneJson.decodeFromString(Box.serializer(), "{}").m)
        // 编码还是普通对象
        assertEquals("""{"m":{"a":"x"}}""", PaperplaneJson.encodeToString(Box.serializer(), b))
    }

    /** 内核以后加的新字段、新状态值不能让解析失败；缺字段、null 用默认值 */
    @Test
    fun unknownValuesAndMissingFields() {
        val s = PaperplaneJson.decodeFromString(
            VCSStatus.serializer(),
            """{"branch":null,"operation":"bisect","files":[{"path":"a","status":"X","newField":{"x":1}}],"futureField":[1,2]}""",
        )
        assertEquals("", s.branch)
        assertEquals("bisect", s.operation)
        assertEquals("X", s.files.first().status)
        val r = PaperplaneJson.decodeFromString(Run.serializer(), """{"id":"r","state":"hibernating","ports":null,"lines":12345678901}""")
        assertFalse(r.isActive)
        assertEquals(emptyList<Int>(), r.ports)
        assertEquals(12345678901L, r.lines)
        // 时间：Go 零值、不同精度与时区
        assertTrue(GoTime.isZero(GoTime.parse("0001-01-01T00:00:00Z")))
        assertEquals(GoTime.parse("2026-11-18T01:39:12.5Z"), GoTime.parse("2026-11-18T09:39:12.500000000+08:00"))
        assertEquals("2026-11-18T01:39:12.500Z", GoTime.format(GoTime.parse("2026-11-18T09:39:12.5+08:00")!!))
        assertNull(GoTime.parse("yesterday"))
    }
}
