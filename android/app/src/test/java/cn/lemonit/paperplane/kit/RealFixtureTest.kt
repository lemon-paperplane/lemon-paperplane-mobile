package cn.lemonit.paperplane.kit

import cn.lemonit.paperplane.kit.model.ActionLocation
import cn.lemonit.paperplane.kit.model.AppSettings
import cn.lemonit.paperplane.kit.model.AvailableVersion
import cn.lemonit.paperplane.kit.model.Contributions
import cn.lemonit.paperplane.kit.model.DaemonEvent
import cn.lemonit.paperplane.kit.model.Diagnosis
import cn.lemonit.paperplane.kit.model.EventPayload
import cn.lemonit.paperplane.kit.model.EventType
import cn.lemonit.paperplane.kit.model.LogsResult
import cn.lemonit.paperplane.kit.model.MCPConfig
import cn.lemonit.paperplane.kit.model.MirrorStatus
import cn.lemonit.paperplane.kit.model.PluginInfo
import cn.lemonit.paperplane.kit.model.Project
import cn.lemonit.paperplane.kit.model.ProjectSummary
import cn.lemonit.paperplane.kit.model.Run
import cn.lemonit.paperplane.kit.model.RunConfigTypeChoice
import cn.lemonit.paperplane.kit.model.ScanEntry
import cn.lemonit.paperplane.kit.model.StoreEntry
import cn.lemonit.paperplane.kit.model.SystemInfo
import cn.lemonit.paperplane.kit.model.TaskInfo
import cn.lemonit.paperplane.kit.model.Toolchain
import cn.lemonit.paperplane.kit.model.ToolchainKind
import cn.lemonit.paperplane.kit.model.ViewRenderResult
import cn.lemonit.paperplane.kit.model.Workspace
import cn.lemonit.paperplane.kit.model.get
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用真实内核（0.1.0）返回的响应核对模型解码（Fixtures/real），对照 Mac RealFixtureTests.swift */
class RealFixtureTest {
    private fun <T> real(name: String, s: KSerializer<T>): T = Fixtures.decode("real/$name", s)
    private fun <T> realList(name: String, s: KSerializer<T>): List<T> = Fixtures.decode("real/$name", ListSerializer(s))

    @Test
    fun allRealResponsesDecode() {
        assertEquals("http://127.0.0.1:47292", real("system.info.json", SystemInfo.serializer()).apiBase)
        assertEquals("默认工作区", realList("workspace.list.json", Workspace.serializer()).first().name)
        val list = realList("project.list.json", ProjectSummary.serializer())
        assertFalse(list.isEmpty())
        assertTrue(list[0].facets.any { it.icon?.startsWith("plugin:") == true })
        assertTrue(realList("project.list.running.json", ProjectSummary.serializer()).any { it.hasActiveRun })
        val p = real("project.get.json", Project.serializer())
        assertEquals(JsonPrimitive("main.py"), p.modules.first().runConfigs.first().options["script"])
        assertEquals("3.12.12", p.modules.first().toolchains["python"]?.version)
        assertEquals(3, p.modules.first().info.size)
        val c = real("ui.contributions.json", Contributions.serializer())
        assertFalse(c.runConfigTypes.isEmpty())
        assertFalse(c.actions(ActionLocation.PROJECT_MENU).isEmpty())
        assertEquals(true, c.actions.firstOrNull { it.id == "installPackage" }?.form?.first()?.required)
        assertFalse(realList("plugin.list.json", PluginInfo.serializer()).isEmpty())
        assertEquals(47292, real("settings.get.json", AppSettings.serializer()).apiPort)
        assertFalse(realList("toolchain.list.json", Toolchain.serializer()).isEmpty())
        assertFalse(realList("toolchain.kinds.json", ToolchainKind.serializer()).isEmpty())
        val ms = real("mirrors.status.json", MirrorStatus.serializer())
        assertEquals("china", ms.effective)
        assertFalse(ms.probes.isEmpty())
        val mcp = real("mcp.config.json", MCPConfig.serializer())
        assertTrue(mcp.claudeCode.startsWith("claude mcp add"))
        assertNotNull(mcp.claudeDesktop?.get("mcpServers"))
        assertEquals(emptyList<Diagnosis>(), realList("project.problems.json", Diagnosis.serializer()))
        assertFalse(realList("runconfig.types.json", RunConfigTypeChoice.serializer()).first().type.options.isEmpty())
        assertTrue(realList("project.scan.json", ScanEntry.serializer()).any { it.imported })
        assertTrue(realList("toolchain.available.json", AvailableVersion.serializer()).any { it.recommended })
        assertEquals(emptyList<StoreEntry>(), realList("store.list.json", StoreEntry.serializer()))
        assertEquals(emptyList<TaskInfo>(), realList("task.list.json", TaskInfo.serializer()))
        val view = real("view.render.json", ViewRenderResult.serializer())
        assertEquals("column", view.root.type)
        assertEquals("primary", view.root.children.first().children.first().style)
        assertEquals(listOf(47311), real("run.get.json", Run.serializer()).ports)
        assertEquals("preparing", real("run.start.json", Run.serializer()).state)
        val logs = real("run.logs.json", LogsResult.serializer())
        assertEquals(5, logs.lines.size)
        assertTrue(logs.truncated)
        assertEquals(17L, logs.lastSeq)
    }

    @Test
    fun realEventStream() {
        val types = mutableListOf<String>()
        for ((_, data) in sseMessages(Fixtures.text("real/events.sse.txt"))) {
            val e = DaemonEvent.parse(data) ?: throw AssertionError("坏事件：$data")
            types.add(e.type)
            val payload = e.payload()
            when (e.type) {
                EventType.HELLO -> assertTrue(payload is EventPayload.Hello)
                EventType.RUN -> assertTrue(payload is EventPayload.RunChanged)
                EventType.PROJECT -> assertTrue(payload is EventPayload.ProjectChanged)
                EventType.STATS -> assertFalse((payload as EventPayload.Stats).runs.isEmpty())
            }
            assertFalse("${e.type} 解码失败", payload is EventPayload.Unknown)
        }
        assertEquals(EventType.HELLO, types.first())
        assertTrue(types.contains(EventType.STATS))
    }
}
