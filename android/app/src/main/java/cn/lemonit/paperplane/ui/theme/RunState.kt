package cn.lemonit.paperplane.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import cn.lemonit.paperplane.ui.i18n.tr

/**
 * 运行状态名（内核 internal/model 的 Run.state 与 engine 汇总的工程 status，对照 Mac RunState / RunStatusStyle）：
 * - 运行：preparing（准备：编译、装依赖、下工具链）| starting | running | stopping | exited | failed
 * - 工程汇总：idle | starting | running | stopping | failed；partial = 部分配置在运行
 */
object RunStates {
    const val IDLE = "idle"
    const val PREPARING = "preparing"
    const val STARTING = "starting"
    const val RUNNING = "running"
    const val STOPPING = "stopping"
    const val EXITED = "exited"
    const val FAILED = "failed"
    const val PARTIAL = "partial"

    /** 还没结束（可以停止） */
    fun isActive(state: String): Boolean = state == PREPARING || state == STARTING || state == RUNNING || state == STOPPING

    /** 过渡中（状态点呼吸闪烁） */
    fun isBusy(state: String): Boolean = state == PREPARING || state == STARTING || state == STOPPING

    /** 界面上的状态名（status.* 文案） */
    fun title(state: String): String = when (state) {
        RUNNING -> tr("status.running")
        PARTIAL -> tr("status.partial")
        STARTING -> tr("status.starting")
        PREPARING -> tr("status.preparing")
        FAILED -> tr("status.failed")
        STOPPING -> tr("status.stopping")
        EXITED -> tr("status.exited")
        else -> tr("status.idle")
    }
}

/** 状态色：运行中绿、启动/准备中黄、失败红、停止中灰、其余（空闲、已退出）浅灰。 */
fun PaperColors.runState(state: String): Color = when (state) {
    RunStates.RUNNING, RunStates.PARTIAL -> statusRunning
    RunStates.STARTING, RunStates.PREPARING -> statusStarting
    RunStates.FAILED -> statusFailed
    RunStates.STOPPING -> statusStopping
    else -> statusIdle
}

/** 当前主题下的状态色：`runStateColor(run.state)` */
@Composable
@ReadOnlyComposable
fun runStateColor(state: String): Color = PaperTheme.colors.runState(state)
