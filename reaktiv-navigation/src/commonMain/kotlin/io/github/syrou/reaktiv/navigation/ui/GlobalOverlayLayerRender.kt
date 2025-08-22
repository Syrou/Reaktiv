@file:OptIn(ExperimentalTime::class)

package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.model.ModalAnimationState
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Composable
fun GlobalOverlayLayerRender(
    entries: List<NavigationEntry>,
    screenContent: @Composable (Navigatable, Params) -> Unit
) {
    val animationStates = remember { mutableStateMapOf<String, ModalAnimationState>() }
    val previousEntries = remember { mutableStateOf<Set<String>>(emptySet()) }

    val currentEntryIds = entries.map { "${it.navigatable.route}_${it.stackPosition}" }.toSet()

    LaunchedEffect(currentEntryIds) {
        val added = currentEntryIds - previousEntries.value
        val removed = previousEntries.value - currentEntryIds

        added.forEach { id ->
            val entry = entries.find { "${it.navigatable.route}_${it.stackPosition}" == id }
            entry?.let {
                animationStates[id] = ModalAnimationState(
                    entry = it,
                    isEntering = true,
                    isExiting = false,
                    animationId = Clock.System.now().toEpochMilliseconds()
                )
            }
        }

        removed.forEach { id ->
            animationStates[id]?.let { state ->
                animationStates[id] = state.copy(
                    isExiting = true,
                    isEntering = false,
                    animationId = Clock.System.now().toEpochMilliseconds()
                )
            }
        }

        previousEntries.value = currentEntryIds
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidth = maxWidth.value * LocalDensity.current.density
        val screenHeight = maxHeight.value * LocalDensity.current.density

        animationStates
            .toList()
            .sortedBy { it.second.entry.navigatable.elevation }
            .forEach { (id, animationState) ->
                key(id) {
                    AnimatedGlobalOverlay(
                        animationState = animationState,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        screenContent = screenContent,
                        onAnimationComplete = {
                            if (animationState.isExiting) {
                                animationStates.remove(id)
                            } else {
                                animationStates[id] = animationState.copy(isEntering = false)
                            }
                        }
                    )
                }
            }
    }
}