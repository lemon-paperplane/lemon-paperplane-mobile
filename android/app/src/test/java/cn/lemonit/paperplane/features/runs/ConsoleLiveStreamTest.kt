package cn.lemonit.paperplane.features.runs

import cn.lemonit.paperplane.kit.ansi.ANSIColor
import cn.lemonit.paperplane.kit.ansi.ANSIParser
import cn.lemonit.paperplane.kit.model.LogLine
import cn.lemonit.paperplane.net.RemoteClient
import cn.lemonit.paperplane.net.blocking
import cn.lemonit.paperplane.net.client
import cn.lemonit.paperplane.net.helloJson
import cn.lemonit.paperplane.net.json
import cn.lemonit.paperplane.net.route
import cn.lemonit.paperplane.net.tlsServer
import kotlinx.coroutines.coroutineScope
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 控制台实时流（ConsoleLoader.stream）接上实时尾部：同一条消息先追加定型行再替换尾部、只有尾部的消息、键缺省不变、结束清空 */
class ConsoleLiveStreamTest {
    private lateinit var s: MockWebServer
    private lateinit var c: RemoteClient

    @Before
    fun setUp() {
        s = tlsServer()
        c = client(s)
    }

    @After
    fun tearDown() {
        c.close()
        s.shutdown()
    }

    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    /** 第一个请求回 hello，其余按顺序回 [responses]（用完后 404：流不再重连，ConsoleLoader.stream 返回） */
    private fun serve(vararg responses: MockResponse) {
        val queue = ArrayDeque(responses.toList())
        s.route { r ->
            if (r.path == "/api/v1/auth/hello") json(helloJson())
            else synchronized(queue) { queue.removeFirstOrNull() } ?: json("""{"error":{"code":"not_found","message":"gone"}}""", 404)
        }
    }

    private fun line(seq: Long, text: String) = """{"seq":$seq,"t":0,"s":"stdout","text":"$text"}"""

    private fun live(vararg texts: String) = texts.joinToString(",", "[", "]") { line(0, it) }

    private fun started(): ConsoleState = ConsoleState("r").apply {
        reset(ConsoleParse.lines((1L..2L).map { LogLine(it, 0, "stdout", "line $it") }, ANSIParser()), 2)
    }

    private fun runStream(state: ConsoleState) = blocking { coroutineScope { ConsoleLoader.stream(state, c, this) } }

    @Test
    fun liveTailFollowsFinalizedLines() {
        serve(
            sse(
                // 定型行（红色没闭合，样式要延续到下一条定型行）+ 实时尾部（绿色没闭合，只在本行）
                "data: {\"lines\":[${line(3, "\\u001b[31mred")}],\"live\":${live("\\u001b[32mlayer a 50%", "layer b waiting")}}\n\n" +
                    // 只有实时尾部
                    "data: {\"live\":${live("\\u001b[32mlayer a 60%", "layer b waiting")}}\n\n" +
                    // 没有 live 键：尾部保持不变
                    "data: {\"lines\":[${line(4, "still red")}]}\n\n",
            ),
        )
        val state = started()
        runStream(state)
        assertEquals((1L..4L).toList(), state.lines.map { it.seq })
        assertEquals(4L, state.hi)
        assertEquals(listOf("layer a 60%", "layer b waiting"), state.liveRows.map { it.text })
        assertEquals(ANSIColor.Palette(2), state.liveRows[0].spans!![0].style.fg)
        assertNull(state.liveRows[1].spans)
        // 实时行的解析没有动定型行的跨行样式
        assertEquals(ANSIColor.Palette(1), state.lines.last().spans!![0].style.fg)
        assertEquals(6, state.rowCount)
        assertFalse(state.ended)
        // 续传位置只看定型行
        val paths = generateSequence { s.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) }.mapNotNull { it.path }
            .filter { it.startsWith("/api/v1/consoles/") }.take(2).toList()
        assertEquals(listOf("/api/v1/consoles/r/stream?since=2", "/api/v1/consoles/r/stream?since=4"), paths)
    }

    @Test
    fun finalizedLineReplacesLiveAndEndClears() {
        serve(
            sse(
                "data: {\"lines\":[],\"live\":${live("Pushing 90%")}}\n\n" +
                    // 进度条定型成普通行，同一条消息里尾部清空
                    "data: {\"lines\":[${line(3, "Pushed")}],\"live\":[]}\n\n" +
                    "data: {\"live\":${live("next 0%")}}\n\n" +
                    "event: end\ndata: {}\n\n",
            ),
        )
        val state = started()
        runStream(state)
        assertEquals(listOf("line 1", "line 2", "Pushed"), state.lines.map { it.text })
        assertTrue(state.ended)
        assertTrue(state.live.isEmpty())
        assertEquals(3, state.rowCount)
    }
}
