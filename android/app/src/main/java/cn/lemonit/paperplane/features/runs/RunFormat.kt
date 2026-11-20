package cn.lemonit.paperplane.features.runs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.kit.model.ActionInfo
import cn.lemonit.paperplane.kit.model.ProjectSummary
import cn.lemonit.paperplane.kit.model.Run
import cn.lemonit.paperplane.kit.util.Format
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.theme.RunStates
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 运行相关的小工具：状态文字、相对时间、卡片与运行页共用的「选中的运行配置」、插件动作筛选。

/** 运行状态行：「已停止」「运行中 · 2m 5s」「失败 · 退出码 1」（对照 Mac RunConsoleArea.statusText） */
fun runStatusText(r: Run, nowMs: Long = System.currentTimeMillis()): String {
    // 用户主动停止的不显示退出码（被信号结束时退出码没有意义）
    if (!r.isActive && r.stoppedByUser) return tr("status.stopped")
    var s = RunStates.title(r.state)
    val code = r.exitCode
    if (!r.isActive && code != null) s += " · " + tr("run.exitCode", code)
    val start = r.startedAt
    if (r.isActive && start != null) s += " · " + Format.duration((nowMs - start.toEpochMilli()) / 1000.0)
    return s
}

/** 相对时间：一周内「3 小时前」，更早显示日期时间（对照 Mac VCSDate.short，文案沿用 vcs.date.*） */
object RelativeTime {
    private val absolute = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun short(t: Instant?, now: Instant = Instant.now()): String {
        if (t == null) return ""
        val sec = (now.epochSecond - t.epochSecond)
        val ahead = sec < 0
        val a = kotlin.math.abs(sec)
        if (a >= 7 * 86400) return absolute.format(t.atZone(ZoneId.systemDefault()))
        val (key, n) = when {
            a < 60 -> "Seconds" to a
            a < 3600 -> "Minutes" to a / 60
            a < 86400 -> "Hours" to a / 3600
            a / 86400 == 1L -> "Day" to 1L
            else -> "Days" to a / 86400
        }
        return tr((if (ahead) "vcs.date.in" else "vcs.date.ago") + key, n)
    }
}

/** 当前时间（毫秒），每 [periodMs] 刷新一次：运行时长、相对时间只让用到它的那一小块重组 */
@Composable
fun rememberNow(periodMs: Long = 1000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs - (System.currentTimeMillis() % periodMs))
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * 卡片和运行页共用的「选中的运行配置」（工程 id → 配置 id，对照 Mac nav.selectedConfig）。
 * 只在本次打开 App 期间记住；没选过或选的配置已不在时用默认配置。
 */
object RunSelection {
    val selected = mutableStateMapOf<String, String>()

    fun configId(p: ProjectSummary): String? {
        selected[p.id]?.let { c -> if (p.configs.any { it.id == c }) return c }
        return p.defaultConfigId?.takeIf { d -> p.configs.any { it.id == d } } ?: p.configs.firstOrNull()?.id
    }

    fun select(projectId: String, configId: String) {
        selected[projectId] = configId
    }
}

/** 插件动作是否适用于这个工程（按 facet；动作没写 facet 时看插件是否参与了工程）。对照 Mac Actions.applicable */
fun applicableActions(actions: List<ActionInfo>, facets: Set<String>, plugins: Set<String>): List<ActionInfo> =
    actions.filter { a -> if (a.facets.isEmpty()) a.plugin in plugins else a.facets.any { it in facets } }

/** 工程名（找不到时用 id） */
internal fun projectName(projectId: String): String = AppModel.data.projects[projectId]?.name ?: projectId
