package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.NavigationLayer
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.transition.resolve
import kotlinx.coroutines.launch

@Composable
fun LayerContent(
    layer: NavigationLayer,
    previousEntry: NavigationEntry?,
    navigationState: NavigationState,
    screenWidth: Float,
    screenHeight: Float,
    navigatableContent: @Composable (Navigatable, StringAnyMap) -> Unit
) {
    val scope = rememberCoroutineScope()
    val store = rememberStore()
    val interactionSource = remember { MutableInteractionSource() }
    val entry = layer.entry
    val isTransitioning = remember(entry, previousEntry) {
        when {
            previousEntry == null -> entry.navigatable.enterTransition != NavTransition.None &&
                    entry.navigatable.enterTransition != NavTransition.Hold

            previousEntry != entry -> true
            else -> false
        }
    }

    val isForward = remember(entry, previousEntry) {
        when {
            previousEntry == null -> true
            previousEntry.stackPosition < entry.stackPosition -> true
            previousEntry.stackPosition > entry.stackPosition -> false
            else -> previousEntry.navigatable.route != entry.navigatable.route
        }
    }
    val enterTransition = when {
        !isForward -> entry.navigatable.popEnterTransition ?: entry.navigatable.enterTransition
        else -> entry.navigatable.enterTransition
    }

    val exitTransition = when {
        isForward -> previousEntry?.navigatable?.popExitTransition ?: (previousEntry?.navigatable?.exitTransition
            ?: NavTransition.None)

        else -> previousEntry?.navigatable?.exitTransition ?: NavTransition.None
    }
    val resolvedEnterTransition = remember(enterTransition, screenWidth, screenHeight, isForward) {
        enterTransition.resolve(screenWidth, screenHeight, isForward)
    }

    val resolvedExitTransition = remember(exitTransition, screenWidth, screenHeight, isForward) {
        exitTransition.resolve(screenWidth, screenHeight, isForward)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(layer.zIndex)
    ) {
        if (isTransitioning) {
            NavTransitionContainer(
                targetState = entry,
                modifier = Modifier.fillMaxSize(),
                enterTransition = resolvedEnterTransition,
                exitTransition = resolvedExitTransition,
                isForward = isForward
            ) { renderEntry ->
                navigatableContent.invoke(renderEntry.navigatable, renderEntry.params)
            }
        } else {
            navigatableContent.invoke(entry.navigatable, entry.params)
        }
        if (layer.shouldDim) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = layer.dimAlpha))
                    .let { modifier ->
                        val topModalEntry = navigationState.currentEntry.takeIf { it.isModal }
                        val isDismissible = topModalEntry?.navigatable?.let { nav ->
                            (nav as? Modal)?.onDismissTapOutside != null
                        } ?: false
                        if (isDismissible) {
                            modifier.clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) {
                                scope.launch {
                                    (topModalEntry.navigatable as? Modal)?.onDismissTapOutside?.invoke(store)
                                }
                            }
                        } else {
                            modifier
                        }
                    }
            )
        }
    }
}
