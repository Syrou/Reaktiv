package io.github.syrou.reaktiv.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import kotlinx.coroutines.Dispatchers

@Composable
fun NavigationRender(
    modifier: Modifier,
    store: Store,
    isAuthenticated: Boolean,
    onAuthenticationRequired: () -> Unit = {},
    loadingContent: @Composable () -> Unit = { /* Default loading UI */ },
    screenContent: @Composable (Screen, StringAnyMap) -> Unit = { _, _ ->}
) {
    val navigationState by store.selectState<NavigationState>().collectAsState(Dispatchers.Main)

    AnimatedContent(
        modifier = modifier.testTag("AnimatedContent"),
        targetState = navigationState.currentScreen,
        transitionSpec = {
            getTransitionAnimation(
                initialState.enterTransition,
                targetState.enterTransition
            )
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

private fun getTransitionAnimation(
    exitTransition: NavTransition,
    enterTransition: NavTransition
): ContentTransform {
    val enter = when (enterTransition) {
        is NavTransition.Slide -> slideInHorizontally { fullWidth -> fullWidth } + fadeIn()
        is NavTransition.Fade -> fadeIn()
        is NavTransition.Scale -> scaleIn() + fadeIn()
        is NavTransition.Custom -> enterTransition.enter
        NavTransition.None -> fadeIn() // Default to fade for None
    }

    val exit = when (exitTransition) {
        is NavTransition.Slide -> slideOutHorizontally { fullWidth -> -fullWidth } + fadeOut()
        is NavTransition.Fade -> fadeOut()
        is NavTransition.Scale -> scaleOut() + fadeOut()
        is NavTransition.Custom -> exitTransition.exit
        NavTransition.None -> fadeOut() // Default to fade for None
    }

    return enter togetherWith exit
}