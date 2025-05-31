package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.transition.getContentTransform

@Composable
fun SimpleNavigationContent(
    modifier: Modifier,
    navigationState: NavigationState,
    screenContent: @Composable (Screen, StringAnyMap, Boolean) -> Unit
) {
    var previousBackStackSize by remember { mutableStateOf(navigationState.backStack.size) }
    var currentBackStackSize by remember { mutableStateOf(navigationState.backStack.size) }
    var previousEntry by remember { mutableStateOf<NavigationEntry?>(null) }
    var currentEntry by remember { mutableStateOf(navigationState.currentEntry) }

    // Track back stack size changes for transition direction
    LaunchedEffect(navigationState.backStack.size) {
        previousBackStackSize = currentBackStackSize
        currentBackStackSize = navigationState.backStack.size
    }

    // Track entry changes
    LaunchedEffect(navigationState.currentEntry) {
        previousEntry = currentEntry
        currentEntry = navigationState.currentEntry
    }

    AnimatedContent(
        modifier = modifier.fillMaxSize(),
        targetState = currentEntry,
        transitionSpec = {
            val isForward = currentBackStackSize > previousBackStackSize
            
            val enterTransition = if (!isForward) {
                // Going back - use pop transitions if available
                previousEntry?.screen?.popEnterTransition ?: targetState.screen.enterTransition
            } else {
                // Going forward - use normal enter transition
                targetState.screen.enterTransition
            }
            
            val exitTransition = if (isForward) {
                // Going forward - use pop transitions if available for current screen
                targetState.screen.popExitTransition ?: initialState.screen.exitTransition
            } else {
                // Going back - use normal exit transition
                initialState.screen.exitTransition
            }
            
            getContentTransform(exitTransition, enterTransition, isForward).apply {
                targetContentZIndex = currentBackStackSize.toFloat()
            }
        }
    ) { entry ->
        screenContent.invoke(
            entry.screen,
            entry.params,
            navigationState.isLoading
        )
    }
}