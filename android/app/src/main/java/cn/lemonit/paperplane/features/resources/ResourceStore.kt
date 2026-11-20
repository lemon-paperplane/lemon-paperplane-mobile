package cn.lemonit.paperplane.features.resources

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.kit.model.Run
import cn.lemonit.paperplane.kit.model.RunBrief
import cn.lemonit.paperplane.kit.model.RunState
import cn.lemonit.paperplane.kit.model.RunStats
import cn.lemonit.paperplane.kit.model.ResourceSample
import cn.lemonit.paperplane.kit.model.ResourceSeries
import cn.lemonit.paperplane.kit.runStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// 运行的资源数据（对照 Mac Features/Resources/ResourceStore.swift）。
// 每个运行一个 RunResources（快照状态），只有显示它的小块随 stats 事件（每 2 秒）重组；卡片、列表本身不动。

/** 一个运行某一时刻的资源数据 */
@Immutable
data class ResourceSnapshot(
    val samples: List<ResourceSample> = emptyList(),
    val cpu: Double = 0.0,
    val rssMB: Double = 0.0,
    val peakRSSMB: Double = 0.0,
    val procs: Int = 0,
    val state: String = "",
    /** 最后一次采样时间（毫秒）；0 表示还没有数据 */
    val t: Long = 0,
)

@Stable
class RunResources internal constructor(val runId: String) {
    var snap by mutableStateOf(ResourceSnapshot())
        private set
    private val series = ResourceSeries()
    private var historyLoaded = false
    private var historyLoading = false

    internal fun apply(b: RunBrief) {
        val t = if (b.t > 0) b.t else System.currentTimeMillis()
        val changed = series.append(ResourceSample(t, b.cpu, b.rssMB))
        val s = snap.copy(
            samples = if (changed) series.samples else snap.samples,
            cpu = b.cpu,
            rssMB = b.rssMB,
            peakRSSMB = maxOf(b.peakRSSMB, b.rssMB, snap.peakRSSMB),
            procs = b.procs,
            state = b.state.ifEmpty { snap.state },
            t = t,
        )
        if (s != snap) snap = s
    }

    internal fun apply(st: RunStats) {
        val changed = series.merge(st.samples)
        var s = snap.copy(
            samples = if (changed) series.samples else snap.samples,
            peakRSSMB = maxOf(st.peakRSSMB, snap.peakRSSMB),
            state = st.state.ifEmpty { snap.state },
        )
        val last = st.samples.lastOrNull()
        if (last != null && last.t >= s.t) {
            val active = RunState.isActive(st.state)
            s = s.copy(cpu = if (active) st.cpu else 0.0, rssMB = if (active) st.rssMB else 0.0, t = last.t)
        }
        if (st.processes.isNotEmpty()) s = s.copy(procs = st.processes.size)
        if (s != snap) snap = s
    }

    internal fun setState(state: String) {
        if (snap.state == state) return
        snap = if (RunState.isActive(state)) snap.copy(state = state) else snap.copy(state = state, cpu = 0.0, rssMB = 0.0, procs = 0)
    }

    /** 首次显示时用 run.stats 补最近 5 分钟的历史（每个运行只取一次，之后靠 stats 事件追加） */
    fun loadHistoryIfNeeded() {
        if (historyLoaded || historyLoading) return
        val c = AppModel.client ?: return
        historyLoading = true
        AppModel.scope.launch {
            try {
                val st = c.runStats(runId)
                apply(st)
                historyLoaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                historyLoading = false
            }
        }
    }

    /** 截图测试、陈列用：直接放一段数据 */
    internal fun seed(samples: List<ResourceSample>, peak: Double = 0.0, procs: Int = 1, state: String = RunState.RUNNING) {
        series.merge(samples)
        val last = samples.lastOrNull()
        snap = ResourceSnapshot(series.samples, last?.cpu ?: 0.0, last?.rssMB ?: 0.0, maxOf(peak, series.maxRSS), procs, state, last?.t ?: 0)
        historyLoaded = true
    }
}

/** 全部运行的资源数据与合计（「运行中」页顶部用）。只在主线程用。 */
object ResourceStore {
    @Immutable
    data class Totals(val projects: Int = 0, val runs: Int = 0, val rssMB: Double = 0.0, val cpu: Double = 0.0)

    var totals by mutableStateOf(Totals())
        private set

    private val byRun = HashMap<String, RunResources>()
    private var job: Job? = null
    private var serverId: String? = null

    /** 某个运行的资源数据（第一次用时创建，并开始订阅 stats 事件） */
    fun resources(runId: String): RunResources {
        ensureStarted()
        return byRun.getOrPut(runId) { RunResources(runId) }
    }

    /** 开始订阅 stats / run.changed（全局只订一次） */
    fun ensureStarted() {
        if (job != null) return
        job = AppModel.scope.launch {
            launch { AppModel.bus.stats.collect { apply(it) } }
            AppModel.bus.runChanged.collect { runChanged(it) }
        }
    }

    /** 切换了服务器：清掉上一台的数据 */
    private fun checkServer() {
        val id = AppModel.currentServerId
        if (id == serverId) return
        serverId = id
        byRun.clear()
        totals = Totals()
    }

    /** stats 事件（只含运行中的） */
    fun apply(briefs: List<RunBrief>) {
        checkServer()
        var runs = 0
        var rss = 0.0
        var cpu = 0.0
        val projects = HashSet<String>()
        for (b in briefs) {
            byRun.getOrPut(b.id) { RunResources(b.id) }.apply(b)
            if (!RunState.isActive(b.state)) continue
            runs++
            rss += b.rssMB
            cpu += b.cpu
            projects += b.projectId ?: AppModel.runs.runs[b.id]?.projectId ?: b.id
        }
        val t = Totals(projects.size, runs, rss, cpu)
        if (t != totals) totals = t
        prune()
    }

    /** run.changed：结束后当前值归零，曲线保留 */
    private fun runChanged(r: Run) {
        checkServer()
        byRun[r.id]?.setState(r.state)
        if (!r.isActive && totals.runs > 0 && AppModel.runs.runs.values.none { it.isActive }) totals = Totals()
    }

    /** 运行页资源面板轮询到的完整数据 */
    fun apply(stats: RunStats) {
        resources(stats.runId).apply(stats)
    }

    /** 只保留运行列表里还有的运行（RunStore 自己会删掉太老的） */
    private fun prune() {
        if (byRun.size < 80) return
        val keep = AppModel.runs.runs.keys
        byRun.keys.retainAll(keep)
    }

    /** 截图测试用 */
    internal fun seedTotals(t: Totals) {
        totals = t
    }
}
