package io.github.syrou.reaktiv.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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

@Composable
fun NavigationRender(
    modifier: Modifier,
    screenContent: @Composable (Screen, StringAnyMap, Boolean) -> Unit = { _, _, _ -> }
) {
    val navigationState by composeState<NavigationState>()
    var previousBackStackSize by remember { mutableStateOf(navigationState.backStack.size) }
    val currentBackStackSize = navigationState.backStack.size
    previousBackStackSize = currentBackStackSize
    // Remember the previous screen
    var previousScreen by remember { mutableStateOf<Screen?>(null) }
    var currentScreen by remember { mutableStateOf<Screen>(navigationState.currentScreen) }

    // Update the remembered previous screen when the current screen changes
    LaunchedEffect(navigationState.currentScreen) {
        previousScreen = currentScreen
        currentScreen = navigationState.currentScreen
    }
    AnimatedContent(
        modifier = modifier.fillMaxSize().testTag("AnimatedContent"),
        targetState = currentScreen,
        transitionSpec = {
            val enterTransition = previousScreen?.popEnterTransition ?: targetState.enterTransition
            val exitTransition = targetState.popExitTransition ?: initialState.exitTransition
            getContentTransform(exitTransition, enterTransition).apply {
                targetContentZIndex = navigationState.backStack.size.toFloat()
            }
        }
    ) { screen ->
        val params by remember(screen.route) {
            mutableStateOf(navigationState.backStack.firstOrNull() { it.screen == screen }?.params ?: emptyMap())
        }
        screenContent.invoke(
            screen,
            params,
            navigationState.isLoading
        )
    }
}

private fun getContentTransform(
    exitTransition: NavTransition,
    enterTransition: NavTransition
): ContentTransform {
    val enter = getEnterAnimation(enterTransition)
    val exit = getExitAnimation(exitTransition)
    return enter togetherWith exit
}

private fun getEnterAnimation(transition: NavTransition): EnterTransition {
    return when (transition) {
        NavTransition.SlideInRight -> slideInHorizontally { width -> width }
        NavTransition.SlideInLeft -> slideInHorizontally { width -> -width }
        NavTransition.SlideUpBottom -> slideInVertically { height -> height }
        NavTransition.Hold -> fadeIn(tween(500),initialAlpha = 0.99f)
        NavTransition.Fade -> fadeIn()
        NavTransition.Scale -> scaleIn()
        is NavTransition.CustomEnterTransition -> transition.enter
        else -> EnterTransition.None
    }
}

private fun getExitAnimation(transition: NavTransition): ExitTransition {
    return when (transition) {
        NavTransition.SlideOutRight -> slideOutHorizontally { width -> width }
        NavTransition.SlideOutLeft -> slideOutHorizontally { width -> -width }
        NavTransition.SlideOutBottom -> slideOutVertically { height -> height }
        NavTransition.Hold -> fadeOut(tween(500),targetAlpha = 0.99f)
        NavTransition.Fade -> fadeOut()
        NavTransition.Scale -> scaleOut()
        is NavTransition.CustomExitTransition -> transition.exit
        else -> ExitTransition.None
    }
}