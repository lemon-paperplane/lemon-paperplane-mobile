package cn.lemonit.paperplane.kit.model

import kotlinx.serialization.Serializable

// 运行的资源监控：run.stats 与 stats 事件（RunBrief.t/cpu/rssMB）。对照 Mac PaperplaneKit/Models/ResourceModels.swift。
// 内存是整个进程树的常驻内存之和，CPU 是进程树 CPU% 之和（可超过 100%）。趋势线几何见 kit.graph.SparklineGeometry。

/** 一次资源采样 */
@Serializable
data class ResourceSample(
    /** Unix 毫秒 */
    val t: Long = 0,
    val cpu: Double = 0.0,
    val rssMB: Double = 0.0,
)

/** 进程树里的一个进程（Go 的 model.ProcessInfo） */
@Serializable
data class RunProcess(
    val pid: Int = 0,
    val ppid: Int = 0,
    val name: String = "",
    val cpu: Double = 0.0,
    val rssMB: Double = 0.0,
) {
    val id: Int get() = pid
}

/** run.stats 输出：最近 5 分钟的趋势（2 秒一点，最多 150 点）与进程明细（按内存降序） */
@Serializable
data class RunStats(
    val runId: String = "",
    val state: String = "",
    val samples: List<ResourceSample> = emptyList(),
    val processes: List<RunProcess> = emptyList(),
    val cpu: Double = 0.0,
    val rssMB: Double = 0.0,
    val peakRSSMB: Double = 0.0,
    val ports: List<Int> = emptyList(),
    val uptimeSec: Long = 0,
    val intervalMs: Int = 0,
)

/** 客户端的趋势序列：按时间追加、去重、最多保留 capacity 点（与内核一致 150 点）。不是线程安全的。 */
class ResourceSeries(val capacity: Int = 150) {
    private val list = ArrayList<ResourceSample>()

    val samples: List<ResourceSample> get() = list.toList()
    val last: ResourceSample? get() = list.lastOrNull()
    val isEmpty: Boolean get() = list.isEmpty()

    /** 追加一点；时间不晚于最后一点的忽略。返回是否有变化 */
    fun append(s: ResourceSample): Boolean {
        val l = list.lastOrNull()
        if (l != null && s.t <= l.t) return false
        list.add(s)
        if (list.size > capacity) list.subList(0, list.size - capacity).clear()
        return true
    }

    /** 合并一段历史（run.stats），保留两边的点、按时间排序去重（同一时间以历史为准） */
    fun merge(history: List<ResourceSample>): Boolean {
        if (history.isEmpty()) return false
        val byT = HashMap<Long, ResourceSample>()
        for (s in list) byT[s.t] = s
        for (s in history) byT[s.t] = s
        val merged = byT.values.sortedBy { it.t }.takeLast(capacity)
        val changed = merged != list
        list.clear()
        list.addAll(merged)
        return changed
    }

    val maxRSS: Double get() = list.maxOfOrNull { it.rssMB } ?: 0.0
    val maxCPU: Double get() = list.maxOfOrNull { it.cpu } ?: 0.0
}

/** 进程明细的树形排列（按父子关系，同级按子树内存降序） */
object ProcessTree {
    data class Node(
        val process: RunProcess,
        val depth: Int,
        val hasChildren: Boolean,
        /** 自己加全部后代的内存 */
        val subtreeRSSMB: Double,
    ) {
        val id: Int get() = process.pid
    }

    /** collapsed 里的 pid 不展开其子进程 */
    fun flatten(list: List<RunProcess>, collapsed: Set<Int> = emptySet()): List<Node> {
        val pids = list.map { it.pid }.toSet()
        val children = HashMap<Int, MutableList<RunProcess>>()
        val roots = mutableListOf<RunProcess>()
        for (p in list) {
            if (p.ppid != p.pid && p.ppid in pids) children.getOrPut(p.ppid) { mutableListOf() }.add(p) else roots.add(p)
        }
        val subtree = HashMap<Int, Double>()
        val seen = HashSet<Int>()
        fun total(p: RunProcess): Double {
            subtree[p.pid]?.let { return it }
            if (!seen.add(p.pid)) return 0.0
            val v = p.rssMB + children[p.pid].orEmpty().sumOf { total(it) }
            subtree[p.pid] = v
            return v
        }
        for (p in list) total(p)

        val out = mutableListOf<Node>()
        val visited = HashSet<Int>()
        fun mark(q: RunProcess) {
            if (!visited.add(q.pid)) return
            for (c in children[q.pid].orEmpty()) mark(c)
        }
        fun walk(p: RunProcess, depth: Int) {
            if (!visited.add(p.pid)) return
            val kids = children[p.pid].orEmpty().sortedByDescending { subtree[it.pid] ?: 0.0 }
            out.add(Node(p, depth, kids.isNotEmpty(), subtree[p.pid] ?: p.rssMB))
            if (p.pid in collapsed) {
                // 收起的子树不输出，但记为已访问，免得后面被当成孤立进程列出来
                for (k in kids) mark(k)
            } else {
                for (k in kids) walk(k, depth + 1)
            }
        }
        for (r in roots.sortedByDescending { subtree[it.pid] ?: 0.0 }) walk(r, 0)
        // 父子关系成环等异常数据：没走到的也要列出来
        for (p in list) if (p.pid !in visited) walk(p, 0)
        return out
    }
}
