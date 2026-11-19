package com.aide.claudecast.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Clear focus highlight for remote-control (D-pad) use: a clay-coloured ring around
 * whatever item has focus. Only installed on TV devices, so touch feedback on the
 * phone is unchanged.
 */
object FocusRingIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        FocusRingNode(interactionSource)

    override fun equals(other: Any?) = other === FocusRingIndication
    override fun hashCode() = -7

    private class FocusRingNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
        private var focused by mutableStateOf(false)

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect {
                    when (it) {
                        is FocusInteraction.Focus -> focused = true
                        is FocusInteraction.Unfocus -> focused = false
                        else -> Unit
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (focused) {
                drawRoundRect(
                    color = Color(0xFFD97757),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                    style = Stroke(width = 4.dp.toPx())
                )
            }
        }
    }
}
