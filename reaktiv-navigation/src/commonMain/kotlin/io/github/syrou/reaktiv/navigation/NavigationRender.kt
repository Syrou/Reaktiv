package io.github.syrou.reaktiv.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.syrou.reaktiv.compose.selectState
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import kotlinx.coroutines.Dispatchers

@Composable
fun NavigationRender(
    modifier: Modifier,
    isAuthenticated: Boolean,
    onAuthenticationRequired: () -> Unit = {},
    loadingContent: @Composable () -> Unit = { /* Default loading UI */ },
    screenContent: @Composable (Screen, StringAnyMap) -> Unit = { _, _ -> }
) {
    val navigationState by selectState<NavigationState>().collectAsState(Dispatchers.Main.immediate)
    var previousBackStackSize by remember { mutableStateOf(navigationState.backStack.size) }
    val currentBackStackSize = navigationState.backStack.size
    val isForward = currentBackStackSize > previousBackStackSize
    // Update the previous backstack size for the next recomposition
    previousBackStackSize = currentBackStackSize
    AnimatedContent(
        modifier = modifier.testTag("AnimatedContent"),
        targetState = navigationState.currentScreen,
        transitionSpec = {
            getContentTransform(initialState.exitTransition, targetState.enterTransition, isForward)
        }
    ) { screen ->
        when {
            navigationState.isLoading -> {
                loadingContent()
            }

            screen.requiresAuth && !isAuthenticated -> {
                onAuthenticationRequired()
            }

            else -> {
                screenContent.invoke(screen, navigationState.backStack.last().second)
            }
        }
    }
}

private fun getContentTransform(
    exitTransition: NavTransition,
    enterTransition: NavTransition,
    isForwardNavigation: Boolean
): ContentTransform {
    val enter = getEnterAnimation(enterTransition, isForwardNavigation)
    val exit = getExitAnimation(exitTransition, isForwardNavigation)
    return enter togetherWith exit
}

private fun getEnterAnimation(transition: NavTransition, isForwardNavigation: Boolean): EnterTransition {
    return when (transition) {
        NavTransition.Slide -> {
            if (isForwardNavigation) {
                slideInHorizontally { width -> width }
            } else {
                slideInHorizontally { width -> -width }
            } + fadeIn()
        }

        NavTransition.Fade -> fadeIn()
        NavTransition.Scale -> scaleIn()
        is NavTransition.Custom -> transition.enter
        NavTransition.None -> EnterTransition.None
    }
}

private fun getExitAnimation(transition: NavTransition, isForwardNavigation: Boolean): ExitTransition {
    return when (transition) {
        NavTransition.Slide -> {
            if (isForwardNavigation) {
                slideOutHorizontally { width -> -width }
            } else {
                slideOutHorizontally { width -> width }
            } + fadeOut()
        }

        NavTransition.Fade -> fadeOut()
        NavTransition.Scale -> scaleOut()
        is NavTransition.Custom -> transition.exit
        NavTransition.None -> ExitTransition.None
    }
}