package cn.lemonit.paperplane.features.runs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.kit.applyFix
import cn.lemonit.paperplane.kit.model.Diagnosis
import cn.lemonit.paperplane.kit.model.Fix
import cn.lemonit.paperplane.kit.model.FixKind
import cn.lemonit.paperplane.kit.model.Severity
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperIconButton
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 一键修复（对照 Mac Actions.applyFix）：带 confirm（或结束端口进程）的先确认 */
object Fixes {
    /**
     * @param runId 运行中的诊断；taskId 安装 / 同步任务的诊断；都没有时按工程的静态问题（projectId）
     * @param onDone 修复成功后（例如关掉问题面板）
     */
    fun apply(
        fix: Fix,
        diagnosis: Diagnosis,
        runId: String? = null,
        projectId: String? = null,
        taskId: String? = null,
        onDone: (() -> Unit)? = null,
    ) {
        if (fix.kind == FixKind.OPEN_URL) {
            fix.params["url"]?.let { RunLinkActions.openDefault(it) }
            return
        }
        val exec: suspend () -> Unit = {
            val api = AppModel.api()
            val res = api.applyFix(
                fixId = fix.id,
                runId = runId,
                taskId = if (runId == null) taskId else null,
                projectId = if (runId == null && taskId == null) projectId else null,
                diagnosisId = diagnosis.id,
            )
            res.task?.let { AppModel.track(it) }
            res.rerun?.let { AppModel.runs.runs[it.id] = it }
            res.openUrl?.takeIf { it.isNotEmpty() }?.let { RunLinkActions.openDefault(it) }
            res.message?.takeIf { it.isNotEmpty() }?.let { Toasts.success(fix.title, it) }
            onDone?.invoke()
        }
        val confirm = fix.confirm?.takeIf { it.isNotEmpty() }
            ?: if (fix.kind == FixKind.KILL_PORT) tr("fix.killPort.confirm", fix.params["port"].orEmpty()) else null
        if (confirm != null) {
            Dialogs.confirm(title = fix.title, message = confirm, confirmText = tr("fix.apply"), danger = fix.kind == FixKind.KILL_PORT, onConfirm = exec)
        } else {
            AppModel.scope.launch {
                try {
                    exec()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toasts.error(e, fix.title)
                }
            }
        }
    }
}

/** 诊断严重度对应的图标 */
internal fun severityIcon(s: String?): String = when (s) {
    Severity.ERROR -> "circle-alert"
    Severity.WARNING -> "triangle-alert"
    else -> "info"
}

/**
 * 一条诊断：严重度、标题、说明、可展开的详情、修复按钮（推荐的在前）。对照 Mac DiagnosisRow。
 *
 * @param framed 带语气色底框（运行页）；问题面板里用 false
 * @param onDismiss 给了就在右上角显示关闭按钮
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosisRow(
    diagnosis: Diagnosis,
    modifier: Modifier = Modifier,
    runId: String? = null,
    projectId: String? = null,
    taskId: String? = null,
    framed: Boolean = true,
    onDismiss: (() -> Unit)? = null,
    onFixed: (() -> Unit)? = null,
) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val tone = c.tone(Tone.ofSeverity(diagnosis.severity))
    var expanded by rememberSaveable(diagnosis.stableKey) { mutableStateOf(false) }
    val detail = listOfNotNull(diagnosis.detail, diagnosis.line).filter { it.isNotEmpty() }.joinToString("\n\n")
    val shape = RoundedCornerShape(PaperTheme.radius.md)
    Column(
        modifier
            .fillMaxWidth()
            .then(if (framed) Modifier.background(tone.bg, shape).border(PaperTheme.sizes.border, tone.border, shape) else Modifier)
            .padding(start = sp.md, top = sp.xs, bottom = sp.md, end = sp.xxs),
        verticalArrangement = Arrangement.spacedBy(sp.sm),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(sp.sm)) {
            PaperIcon(severityIcon(diagnosis.severity), tint = tone.fg, modifier = Modifier.padding(top = sp.md))
            Column(Modifier.weight(1f).padding(top = sp.md), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(diagnosis.title, style = PaperTheme.typography.bodyStrong, color = c.textPrimary)
                val m = diagnosis.message
                if (!m.isNullOrEmpty()) Text(m, style = PaperTheme.typography.small, color = c.textSecondary)
            }
            if (detail.isNotEmpty()) {
                PaperIconButton(if (expanded) "chevron-up" else "chevron-down", tr("diagnosis.detail"), onClick = { expanded = !expanded }, tint = c.iconSecondary)
            }
            if (onDismiss != null) PaperIconButton("x", tr("common.close"), onClick = onDismiss, tint = c.iconSecondary)
        }
        if (expanded && detail.isNotEmpty()) {
            SelectionContainer {
                Text(
                    detail,
                    style = PaperTheme.typography.monoSmall,
                    color = c.textSecondary,
                    modifier = Modifier
                        .padding(end = sp.md)
                        .fillMaxWidth()
                        .background(if (framed) c.bgCode else Color.Transparent, RoundedCornerShape(PaperTheme.radius.sm))
                        .padding(sp.sm),
                )
            }
        }
        if (diagnosis.fixes.isNotEmpty()) {
            FlowRow(
                Modifier.padding(start = PaperTheme.sizes.icon + sp.sm, end = sp.md),
                horizontalArrangement = Arrangement.spacedBy(sp.sm),
                verticalArrangement = Arrangement.spacedBy(sp.sm),
            ) {
                for (f in diagnosis.fixes.sortedByDescending { it.recommended }) {
                    PaperButton(
                        f.title,
                        onClick = { Fixes.apply(f, diagnosis, runId = runId, projectId = projectId, taskId = taskId, onDone = onFixed) },
                        kind = if (f.recommended) ButtonKind.Primary else ButtonKind.Secondary,
                        size = ButtonSize.Small,
                    )
                }
            }
        }
    }
}

