package cn.lemonit.paperplane.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * 按下反馈：按住时在控件上盖一层淡色（对照 Mac 的 bgPressed），不用 Material 水波纹。
 * 形状由调用方先 clip；PaperplaneTheme 把它设为全局 LocalIndication，所有 clickable 默认都用它。
 */
class PressIndication(private val overlay: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource, overlay)

    override fun equals(other: Any?): Boolean = other is PressIndication && other.overlay == overlay
    override fun hashCode(): Int = overlay.hashCode()

    private class Node(private val source: InteractionSource, private val overlay: Color) : Modifier.Node(), DrawModifierNode {
        private var pressed = 0

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { i ->
                    when (i) {
                        is PressInteraction.Press -> pressed++
                        is PressInteraction.Release, is PressInteraction.Cancel -> pressed = (pressed - 1).coerceAtLeast(0)
                    }
                    invalidateDraw()
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (pressed > 0) drawRect(overlay)
        }
    }
}
