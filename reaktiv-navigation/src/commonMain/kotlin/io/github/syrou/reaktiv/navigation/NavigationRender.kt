package io.github.syrou.reaktiv.navigation

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
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.model.NavigationEntry

@Composable
fun NavigationRender(
    modifier: Modifier,
    screenContent: @Composable (Screen, StringAnyMap, Boolean) -> Unit = { _, _, _ -> }
) {
    val navigationState by composeState<NavigationState>()
    var currentBackStackSize by remember { mutableStateOf(navigationState.backStack.size) }
    var previousBackStackSize by remember { mutableStateOf(navigationState.backStack.size) }

    // Remember the previous screen
    var previousEntry by remember { mutableStateOf<NavigationEntry?>(null) }
    var currentEntry by remember { mutableStateOf<NavigationEntry>(navigationState.currentEntry) }

    LaunchedEffect(navigationState.backStack.size) {
        previousBackStackSize = currentBackStackSize
        currentBackStackSize = navigationState.backStack.size
    }

    // Update the remembered previous screen when the current screen changes
    LaunchedEffect(navigationState.currentEntry) {
        val isForward = navigationState.clearedBackStackWithNavigate ||
                (navigationState.backStack.size > previousBackStackSize)
        if (!isForward) {
            previousEntry = currentEntry
            currentEntry = navigationState.currentEntry
        }

        if (isForward) {
            previousEntry = currentEntry
            currentEntry = navigationState.currentEntry
        }
    }

    AnimatedContent(
        modifier = modifier.fillMaxSize().testTag("AnimatedContent"),
        targetState = currentEntry,
        transitionSpec = {
            val isForward = navigationState.clearedBackStackWithNavigate ||
                    (navigationState.backStack.size > previousBackStackSize)
            val enterTransition = if (!isForward) previousEntry?.screen?.popEnterTransition
                ?: targetState.screen.enterTransition else targetState.screen.enterTransition
            val exitTransition = if (isForward) targetState.screen.popExitTransition
                ?: initialState.screen.exitTransition else initialState.screen.exitTransition
            getContentTransform(exitTransition, enterTransition, isForward).apply {
                targetContentZIndex =
                    if (navigationState.clearedBackStackWithNavigate) {
                        previousBackStackSize++.toFloat()
                    } else {
                        navigationState.backStack.size.toFloat()
                    }
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