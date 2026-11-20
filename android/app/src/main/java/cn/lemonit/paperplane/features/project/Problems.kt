package cn.lemonit.paperplane.features.project

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import cn.lemonit.paperplane.core.Actions
import cn.lemonit.paperplane.core.AppModel
import cn.lemonit.paperplane.kit.applyFix
import cn.lemonit.paperplane.kit.model.Diagnosis
import cn.lemonit.paperplane.kit.model.Fix
import cn.lemonit.paperplane.kit.model.FixKind
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.Dialogs
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.PaperInfoButton
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import cn.lemonit.paperplane.ui.theme.Tone

/**
 * 一条静态问题（project.problems）：严重度图标、标题、说明、一键修复按钮（推荐的用主按钮）。
 * 修复走 fix.apply（projectId + diagnosisId），完成后回调 [onFixed] 重新取问题。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProblemCard(d: Diagnosis, projectId: String, modifier: Modifier = Modifier, onFixed: () -> Unit = {}) {
    val c = PaperTheme.colors
    val sp = PaperTheme.spacing
    val tone = Tone.ofSeverity(d.severity)
    val tc = c.tone(tone)
    val shape = RoundedCornerShape(PaperTheme.radius.lg)
    var busy by remember(d.stableKey) { mutableStateOf<String?>(null) }
    Row(
        modifier
            .fillMaxWidth()
            .background(c.bgSurface, shape)
            .border(PaperTheme.sizes.border, c.border, shape)
            .padding(sp.lg),
        horizontalArrangement = Arrangement.spacedBy(sp.md),
        verticalAlignment = Alignment.Top,
    ) {
        PaperIcon(
            when (tone) {
                Tone.Danger -> "circle-alert"
                Tone.Warning -> "triangle-alert"
                else -> "info"
            },
            tint = tc.fg,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(sp.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.title, style = PaperTheme.typography.bodyStrong, color = c.textPrimary, modifier = Modifier.weight(1f, fill = false))
                d.detail?.takeIf { it.isNotEmpty() }?.let { PaperInfoButton(it) }
            }
            d.message?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = PaperTheme.typography.small, color = c.textSecondary, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            if (d.fixes.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(top = sp.xs),
                    horizontalArrangement = Arrangement.spacedBy(sp.sm),
                    verticalArrangement = Arrangement.spacedBy(sp.sm),
                ) {
                    for (f in d.fixes) {
                        PaperButton(
                            f.title,
                            onClick = {
                                Fixes.apply(projectId, d, f, onBusy = { busy = if (it) f.id else null }, after = onFixed)
                            },
                            kind = if (f.recommended) ButtonKind.Primary else ButtonKind.Secondary,
                            size = ButtonSize.Small,
                            loading = busy == f.id,
                            enabled = busy == null,
                        )
                    }
                }
            }
        }
    }
}

/** 一键修复（静态问题）：需要确认的先确认，结果里的提示 / 地址 / 任务 / 重跑都处理 */
internal object Fixes {
    fun apply(projectId: String, d: Diagnosis, fix: Fix, onBusy: (Boolean) -> Unit = {}, after: () -> Unit = {}) {
        val exec: suspend () -> Unit = {
            onBusy(true)
            try {
                val r = AppModel.api().applyFix(fix.id, projectId = projectId, diagnosisId = d.id)
                r.message?.takeIf { it.isNotEmpty() }?.let { Toasts.success(it) }
                r.openUrl?.takeIf { it.isNotEmpty() }?.let { Actions.openExternal(it) }
                r.task?.let { AppModel.track(it) }
                r.rerun?.let { AppModel.runs.runs[it.id] = it }
                after()
            } finally {
                onBusy(false)
            }
        }
        val confirm = fix.confirm?.takeIf { it.isNotEmpty() }
            ?: if (fix.kind == FixKind.KILL_PORT) tr("fix.killPort.confirm", fix.params["port"].orEmpty()) else null
        if (confirm != null) {
            Dialogs.confirm(fix.title, message = confirm, confirmText = tr("fix.apply")) { exec() }
        } else {
            AppModel.perform(fix.title) { exec() }
        }
    }
}
