package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.transition.resolve

@Composable
fun NavigationContent(
    modifier: Modifier,
    navigationState: NavigationState,
    screenContent: @Composable (Navigatable, StringAnyMap) -> Unit
) {
    var currentLayers by remember { mutableStateOf(navigationState.visibleLayers) }
    var previousEntry by remember { mutableStateOf<NavigationEntry?>(null) }
    var currentEntry by remember { mutableStateOf(navigationState.currentEntry) }

    LaunchedEffect(navigationState.visibleLayers) {
        currentLayers = navigationState.visibleLayers
    }

    LaunchedEffect(navigationState.currentEntry) {
        previousEntry = currentEntry
        currentEntry = navigationState.currentEntry
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWidth = maxWidth.value * LocalDensity.current.density
        val screenHeight = maxHeight.value * LocalDensity.current.density
        currentLayers.forEach { layer ->
            LayerContent(
                layer = layer,
                previousEntry = previousEntry,
                navigationState = navigationState,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                navigatableContent = screenContent
            )
        }
    }
}