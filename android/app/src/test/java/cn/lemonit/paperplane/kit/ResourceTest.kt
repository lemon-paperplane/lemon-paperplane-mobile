package cn.lemonit.paperplane.kit

import cn.lemonit.paperplane.kit.graph.GPoint
import cn.lemonit.paperplane.kit.graph.GRect
import cn.lemonit.paperplane.kit.graph.SparklineGeometry
import cn.lemonit.paperplane.kit.model.DaemonEvent
import cn.lemonit.paperplane.kit.model.EventPayload
import cn.lemonit.paperplane.kit.model.ProcessTree
import cn.lemonit.paperplane.kit.model.ResourceSample
import cn.lemonit.paperplane.kit.model.ResourceSeries
import cn.lemonit.paperplane.kit.model.Run
import cn.lemonit.paperplane.kit.model.RunProcess
import cn.lemonit.paperplane.kit.model.RunStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对照 Mac ResourceTests.swift */
class ResourceTest {
    @Test
    fun realRunStats() {
        val s = Fixtures.decode("real/run.stats.json", RunStats.serializer())
        assertEquals("running", s.state)
        assertEquals(4, s.samples.size)
        assertEquals("Python", s.processes.first().name)
        assertEquals(listOf(47311), s.ports)
        assertEquals(2000, s.intervalMs)
        assertEquals(24.1, s.peakRSSMB, 0.001)
        val ended = Fixtures.decode("real/run.stats.ended.json", RunStats.serializer())
        assertEquals("exited", ended.state)
        assertEquals(emptyList<RunProcess>(), ended.processes)
        assertEquals(4, ended.samples.size) // 结束后保留曲线
        val run = Fixtures.decode("real/run.get.json", Run.serializer())
        assertEquals(1, run.procs)
        assertTrue(run.peakRSSMB > 0)
    }

    @Test
    fun realStatsEvent() {
        val events = sseMessages(Fixtures.text("real/stats.event.txt")).mapNotNull { DaemonEvent.parse(it.second) }
        val briefs = (events.first().payload() as EventPayload.Stats).runs
        assertEquals("6a16998ec83a0ff4", briefs.first().projectId)
        assertEquals(1, briefs.first().procs)
        assertTrue(briefs.first().t > 0)
    }

    @Test
    fun seriesAppendMergeCapacity() {
        val s = ResourceSeries(capacity = 3)
        assertTrue(s.append(ResourceSample(10, 1.0, 10.0)))
        assertFalse(s.append(ResourceSample(10, 9.0, 99.0))) // 同一时间点忽略
        assertFalse(s.append(ResourceSample(5, 9.0, 99.0))) // 更早的忽略
        s.append(ResourceSample(20, 2.0, 20.0))
        s.append(ResourceSample(30, 3.0, 30.0))
        s.append(ResourceSample(40, 4.0, 40.0))
        assertEquals(listOf(20L, 30L, 40L), s.samples.map { it.t })
        assertTrue(s.merge(listOf(ResourceSample(15, 0.0, 0.0), ResourceSample(35, 5.0, 50.0))))
        assertEquals(listOf(30L, 35L, 40L), s.samples.map { it.t })
        assertEquals(50.0, s.maxRSS, 0.0)
        assertEquals(5.0, s.maxCPU, 0.0)
        assertFalse(s.merge(emptyList()))
        assertFalse(s.merge(listOf(ResourceSample(35, 5.0, 50.0)))) // 没有变化
    }

    @Test
    fun sparklineGeometry() {
        val samples = listOf(ResourceSample(0, 0.0, 0.0), ResourceSample(1000, 0.0, 50.0), ResourceSample(2000, 0.0, 100.0))
        val rect = GRect(0f, 0f, 100f, 20f)
        val pts = SparklineGeometry.points(samples, rect, maxValue = 100.0) { it.rssMB }
        // 最大值留 10% 余量：100 → 110
        assertEquals(GPoint(0f, 20f), pts.first())
        assertEquals(100f, pts.last().x, 0.001f)
        assertEquals((20 - 20 * (100.0 / 110)).toFloat(), pts.last().y, 0.01f)
        // 固定时间轴：5 分钟跨度时，2 秒的数据贴在右端
        val fixed = SparklineGeometry.points(samples, rect, span = 300_000, maxValue = 100.0) { it.rssMB }
        assertTrue(fixed.first().x > 99)
        assertEquals(1, SparklineGeometry.nearestIndex(pts, 52f))
        assertEquals(250.0, SparklineGeometry.niceCeiling(230.0), 0.0)
        assertEquals(1.0, SparklineGeometry.niceCeiling(0.7), 0.0)
        assertEquals(2000.0, SparklineGeometry.niceCeiling(1300.0), 0.0)
        // 单点
        assertEquals(1, SparklineGeometry.points(listOf(samples[2]), rect) { it.rssMB }.size)
        assertTrue(SparklineGeometry.points(emptyList(), rect) { it.rssMB }.isEmpty())
    }

    @Test
    fun processTree() {
        // yarn(10) → node vite(20) → esbuild(30)；另一个孤立进程 99 的父进程不在树里
        val list = listOf(
            RunProcess(10, 1, "node", rssMB = 115.9),
            RunProcess(20, 10, "node", rssMB = 93.0),
            RunProcess(30, 20, "esbuild", rssMB = 16.8),
            RunProcess(99, 7, "sh", rssMB = 1.0),
        )
        val nodes = ProcessTree.flatten(list)
        assertEquals(listOf(10, 20, 30, 99), nodes.map { it.process.pid })
        assertEquals(listOf(0, 1, 2, 0), nodes.map { it.depth })
        assertEquals(225.7, nodes[0].subtreeRSSMB, 0.001)
        assertTrue(nodes[1].hasChildren)
        assertFalse(nodes[2].hasChildren)
        assertEquals(listOf(10, 20, 99), ProcessTree.flatten(list, collapsed = setOf(20)).map { it.process.pid })
        // 自己是自己的父进程、环：不死循环
        val loop = listOf(RunProcess(1, 2, "a"), RunProcess(2, 1, "b"))
        assertEquals(setOf(1, 2), ProcessTree.flatten(loop).map { it.process.pid }.toSet())
    }
}
