package org.sunsetware.phocid.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import androidx.compose.ui.unit.Dp
import kotlin.math.absoluteValue
import kotlin.math.sign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.sunsetware.phocid.ui.theme.emphasizedExit

/** Yes, [androidx.compose.material3.SwipeToDismissBox] is yet another Google's useless s***. */
@Composable
inline fun <T> SwipeToDismiss(
    key: T,
    enabled: Boolean,
    velocityThreshold: Dp,
    crossinline onDismiss: (T) -> Unit,
    crossinline content: @Composable BoxScope.() -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val dispatcher = Dispatchers.Main.limitedParallelism(1)
    val updatedKey by rememberUpdatedState(key)
    val updatedVelocityThreshold by rememberUpdatedState(velocityThreshold)
    val offset = remember { Animatable(0f) }
    val velocityTracker = remember { VelocityTracker1D(true) }

    Box(
        modifier =
            if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { velocityTracker.resetTracking() },
                            onDragCancel = {
                                coroutineScope.launch(dispatcher) {
                                    offset.animateTo(0f, emphasizedExit())
                                }
                            },
                            onDragEnd = {
                                val velocity = velocityTracker.calculateVelocity()
                                val positionalThreshold = size.width / 2
                                val velocityThreshold = updatedVelocityThreshold.toPx()
                                val value = offset.value
                                if (
                                    value.absoluteValue >= positionalThreshold ||
                                        velocity.absoluteValue >= velocityThreshold
                                ) {
                                    coroutineScope.launch(dispatcher) {
                                        offset.animateTo(value.sign * size.width, emphasizedExit())
                                        onDismiss(updatedKey)
                                    }
                                } else {
                                    coroutineScope.launch(dispatcher) {
                                        offset.animateTo(0f, emphasizedExit())
                                    }
                                }
                                velocityTracker.resetTracking()
                            },
                        ) { change, dragAmount ->
                            velocityTracker.addDataPoint(change.uptimeMillis, dragAmount)
                            coroutineScope.launch(dispatcher) {
                                offset.snapTo(offset.value + dragAmount)
                            }
                        }
                    }
                } else {
                    Modifier
                }
                .graphicsLayer {
                    translationX = offset.value
                    alpha =
                        (1 - (offset.value / size.width).absoluteValue)
                            .takeIf { it.isFinite() }
                            ?.coerceIn(0f, 1f) ?: 1f
                }
    ) {
        content()
    }
}
