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
fun GraphNavigationContent(
    modifier: Modifier,
    navigationState: NavigationState,
    screenContent: @Composable (Screen, StringAnyMap, Boolean) -> Unit
) {
    val activeGraphState = navigationState.activeGraphState

    var currentBackStackSize by remember { mutableStateOf(activeGraphState.backStack.size) }
    var previousBackStackSize by remember { mutableStateOf(activeGraphState.backStack.size) }
    var previousEntry by remember { mutableStateOf<NavigationEntry?>(null) }
    var currentEntry by remember {
        mutableStateOf<NavigationEntry>(
            activeGraphState.currentEntry
        )
    }

    LaunchedEffect(activeGraphState.backStack.size) {
        previousBackStackSize = currentBackStackSize
        currentBackStackSize = activeGraphState.backStack.size
    }

    LaunchedEffect(activeGraphState.currentEntry) {
        val isForward = activeGraphState.backStack.size > previousBackStackSize
        if (!isForward) {
            previousEntry = currentEntry
            currentEntry = activeGraphState.currentEntry
        }
        if (isForward) {
            previousEntry = currentEntry
            currentEntry = activeGraphState.currentEntry
        }
    }

    AnimatedContent(
        modifier = modifier.fillMaxSize().testTag("AnimatedContent"),
        targetState = currentEntry,
        transitionSpec = {
            val isForward = activeGraphState.backStack.size > previousBackStackSize
            val enterTransition = if (!isForward) {
                previousEntry?.screen?.popEnterTransition ?: targetState.screen.enterTransition
            } else {
                targetState.screen.enterTransition
            }
            val exitTransition = if (isForward) {
                targetState.screen.popExitTransition ?: initialState.screen.exitTransition
            } else {
                initialState.screen.exitTransition
            }
            getContentTransform(
                exitTransition,
                enterTransition,
                isForward
            ).apply {
                targetContentZIndex = activeGraphState.backStack.size.toFloat()
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