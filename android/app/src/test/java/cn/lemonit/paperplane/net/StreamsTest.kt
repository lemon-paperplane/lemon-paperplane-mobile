package cn.lemonit.paperplane.net

import cn.lemonit.paperplane.kit.ApiException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

class StreamsTest {
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

    /** 第一个请求回 hello，其余按顺序回 [responses]（用完后 404） */
    private fun serve(vararg responses: MockResponse) {
        val queue = ArrayDeque(responses.toList())
        s.route { r ->
            if (r.path == "/api/v1/auth/hello") json(helloJson())
            else synchronized(queue) { queue.removeFirstOrNull() } ?: json("""{"error":{"code":"not_found","message":"gone"}}""", 404)
        }
    }

    private fun streamRequests(n: Int): List<String> {
        val out = ArrayList<String>()
        while (out.size < n) {
            val r = s.takeRequest(5, TimeUnit.SECONDS) ?: break
            if (r.path != "/api/v1/auth/hello") out += r.path!!
        }
        return out
    }

    @Test
    fun eventStreamReconnectsAndGetsHelloEachTime() {
        serve(
            sse(String(resource("events.sse.txt"))),
            sse("data: {\"type\":\"hello\",\"data\":{\"version\":\"2\"}}\n\n"),
        )
        val col = Collector<EventStream.Update>()
        val es = EventStream(c)
        es.start(col.add)
        assertEquals(EventStream.Update.Connected, col.next())
        val hello = col.next() as EventStream.Update.Event
        assertEquals("hello", hello.type)
        repeat(4) { col.nextOf<EventStream.Update.Event>() }
        val d = col.nextOf<EventStream.Update.Disconnected>()
        assertTrue(d.retrying)
        assertEquals(NetErrors.REASON_CLOSED, d.error.reason)
        col.nextOf<EventStream.Update.Connected>()
        val again = col.nextOf<EventStream.Update.Event>()
        assertEquals("hello", again.type)
        // 两条都用完：第三次 404 → 不再重连
        val last = col.nextOf<EventStream.Update.Disconnected>()
        if (last.retrying) {
            val fin = col.nextOf<EventStream.Update.Disconnected>()
            assertFalse(fin.retrying)
            assertEquals(ApiException.NOT_FOUND, fin.error.code)
        }
        es.stop()
        assertEquals(listOf("/api/v1/events", "/api/v1/events", "/api/v1/events"), streamRequests(3))
    }

    @Test
    fun consoleStreamResumesFromLastSeq() {
        serve(
            sse("data: {\"lines\":[{\"seq\":1,\"text\":\"a\"},{\"seq\":2,\"text\":\"b\"}]}\n\n"),
            sse("data: {\"lines\":[{\"seq\":2,\"text\":\"b\"},{\"seq\":3,\"text\":\"c\"}]}\n\nevent: end\ndata: {}\n\n"),
        )
        val cs = ConsoleStream(c, "run 1")
        val col = Collector<ConsoleStream.Update>()
        cs.start(col.add)
        val first = col.nextOf<ConsoleStream.Update.Lines>()
        assertEquals(listOf(1L, 2L), first.lines.map { it["seq"]!!.jsonPrimitive.long })
        assertNull(first.live) // 老内核没有 live 键：实时尾部不变
        assertTrue(col.nextOf<ConsoleStream.Update.Disconnected>().retrying)
        val second = col.nextOf<ConsoleStream.Update.Lines>()
        assertEquals(listOf(3L), second.lines.map { it["seq"]!!.jsonPrimitive.long }) // 重复的 seq 2 去掉了
        col.nextOf<ConsoleStream.Update.Ended>()
        assertEquals(3L, cs.lastSeq)
        assertFalse(cs.isRunning)
        assertEquals(listOf("/api/v1/consoles/run%201/stream?since=0", "/api/v1/consoles/run%201/stream?since=2"), streamRequests(2))
    }

    @Test
    fun consoleFlowCompletesOnEndAndRestartsFromPosition() {
        serve(
            sse("data: {\"lines\":[{\"seq\":5,\"text\":\"a\"}]}\n\nevent: end\ndata: {}\n\n"),
            sse("event: end\ndata: {}\n\n"),
        )
        val cs = ConsoleStream(c, "r", since = 4)
        val all = blocking { cs.updates().toList() }
        assertTrue(all.last() is ConsoleStream.Update.Ended)
        assertEquals(5L, cs.lastSeq)
        // 再收集一次：从 5 续传
        blocking { cs.updates().first { it is ConsoleStream.Update.Ended } }
        assertEquals(listOf("/api/v1/consoles/r/stream?since=4", "/api/v1/consoles/r/stream?since=5"), streamRequests(2))
    }

    @Test
    fun consoleStreamLiveTail() {
        fun live(vararg texts: String) = texts.joinToString(",", "[", "]") { """{"seq":0,"t":0,"s":"stdout","text":"$it"}""" }
        serve(
            sse(
                "data: {\"lines\":[{\"seq\":1,\"text\":\"a\"}],\"live\":${live("50%")}}\n\n" + // 定型行 + 实时尾部
                    "data: {\"lines\":[{\"seq\":2,\"text\":\"b\"}]}\n\n" + // 没有 live 键：保持不变
                    "data: {\"live\":${live("60%", "layer 2")}}\n\n" + // 只有 live
                    "data: {\"lines\":[],\"live\":[]}\n\n", // 清空
            ),
            sse(
                // 重连后的第一条：重复的定型行去掉，live 照样交出
                "data: {\"lines\":[{\"seq\":2,\"text\":\"b\"}],\"live\":${live("70%")}}\n\n" +
                    "data: {\"lines\":[],\"live\":null}\n\n" + // live 键的值是 null：按清空
                    "data: {\"lines\":[]}\n\n" + // 什么都没有：不交出
                    "event: end\ndata: {}\n\n",
            ),
        )
        val cs = ConsoleStream(c, "r")
        val all = blocking { cs.updates().toList() }
        assertTrue(all.last() is ConsoleStream.Update.Ended)
        assertTrue(all.any { it is ConsoleStream.Update.Disconnected && it.retrying })
        val got = all.filterIsInstance<ConsoleStream.Update.Lines>().map { u ->
            u.lines.map { it["seq"]!!.jsonPrimitive.long } to u.live?.map { it["text"]!!.jsonPrimitive.content }
        }
        assertEquals(
            listOf(
                listOf(1L) to listOf("50%"),
                listOf(2L) to null,
                emptyList<Long>() to listOf("60%", "layer 2"),
                emptyList<Long>() to emptyList(),
                emptyList<Long>() to listOf("70%"),
                emptyList<Long>() to emptyList(),
            ),
            got,
        )
        // 实时行不推进续传位置
        assertEquals(2L, cs.lastSeq)
        assertEquals(listOf("/api/v1/consoles/r/stream?since=0", "/api/v1/consoles/r/stream?since=2"), streamRequests(2))
    }

    @Test
    fun terminalStreamResumesByOffsetAndTrimsOverlap() {
        fun data(o: Long, s: String) = "data: {\"o\":$o,\"d\":\"${Base64.getEncoder().encodeToString(s.toByteArray())}\"}\n\n"
        val state = "event: state\ndata: {\"id\":\"t1\",\"output\":6}\n\n"
        serve(
            sse(state + data(0, "hello ")),
            sse(state + data(3, "lo world") + data(11, "!") + "event: closed\ndata: {}\n\n"),
        )
        val ts = TerminalStream(c, "t1", full = true)
        val col = Collector<TerminalStream.Update>()
        ts.start(col.add)
        assertEquals("t1", col.nextOf<TerminalStream.Update.State>().terminal["id"]!!.jsonPrimitive.content)
        val out = StringBuilder()
        var o = col.nextOf<TerminalStream.Update.Output>()
        assertEquals(0L, o.offset)
        out.append(String(o.data))
        col.nextOf<TerminalStream.Update.Disconnected>()
        o = col.nextOf<TerminalStream.Update.Output>()
        assertEquals(6L, o.offset)
        out.append(String(o.data))
        out.append(String(col.nextOf<TerminalStream.Update.Output>().data))
        col.nextOf<TerminalStream.Update.Closed>()
        assertEquals("hello world!", out.toString())
        assertEquals(12L, ts.offset)
        assertEquals(listOf("/api/v1/terminals/t1/stream?since=0&full=1", "/api/v1/terminals/t1/stream?since=6&full=1"), streamRequests(2))
    }

    @Test
    fun unauthorizedStopsWithoutRetry() {
        serve(json("""{"error":{"code":"unauthorized","message":"invalid or revoked access key"}}""", 401))
        val l = RecordingListener()
        val c2 = client(s, listener = l)
        val col = Collector<TerminalStream.Update>()
        val ts = TerminalStream(c2, "t1", since = 42)
        ts.start(col.add)
        val d = col.nextOf<TerminalStream.Update.Disconnected>()
        assertFalse(d.retrying)
        assertEquals(ApiException.UNAUTHORIZED, d.error.code)
        assertEquals(ApiException.UNAUTHORIZED, l.problems.poll(2, TimeUnit.SECONDS)?.code)
        Thread.sleep(1500)
        assertEquals(1, streamRequests(5).size) // 没有重连（重连会让服务器把这个 IP 封掉）
        assertEquals(42L, ts.offset)
    }

    @Test
    fun serverErrorRetriesAndStopIsQuiet() {
        serve(
            json("""{"error":{"code":"internal","message":"boom"}}""", 500),
            sse("data: {\"type\":\"hello\",\"data\":{}}\n\n").setBodyDelay(0, TimeUnit.MILLISECONDS),
        )
        val col = Collector<EventStream.Update>()
        val es = EventStream(c)
        es.start(col.add)
        val d = col.nextOf<EventStream.Update.Disconnected>()
        assertTrue(d.retrying)
        assertEquals("internal", d.error.code)
        col.nextOf<EventStream.Update.Connected>()
        col.nextOf<EventStream.Update.Event>()
        es.stop()
        assertFalse(es.isRunning)
        Thread.sleep(800)
        // stop 之后不再有回调
        val rest = generateSequence { col.queue.poll() }.toList()
        assertTrue("callbacks after stop: $rest", rest.all { it is EventStream.Update.Disconnected } && rest.size <= 1)
    }
}
