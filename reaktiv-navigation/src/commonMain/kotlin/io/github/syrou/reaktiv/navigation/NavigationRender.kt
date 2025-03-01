package io.github.syrou.reaktiv.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import io.github.syrou.reaktiv.compose.selectState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt

private const val TARGET_STIFFNESS = 400f // Approximately Spring.StiffnessMediumLow
private fun estimateSpringParametersForIntOffset(durationMillis: Int): Pair<Float, Float> {
    val stiffness = TARGET_STIFFNESS
    val dampingRatio = sqrt(stiffness / (4 * PI.pow(2) * (1000f / durationMillis).pow(2)))
    return Pair(stiffness, dampingRatio.toFloat())
}

private fun estimateSpringParametersForFloat(durationMillis: Int): Pair<Float, Float> {
    val stiffness = TARGET_STIFFNESS
    // For Float animations, we use a slightly higher damping ratio to reduce overshoot
    val dampingRatio = sqrt(stiffness / (2 * PI.pow(2) * (1000f / durationMillis).pow(2)))
    return Pair(stiffness, dampingRatio.toFloat().coerceIn(0f, 1f))
}

private fun getSpringSpecForIntOffset(durationMillis: Int) = spring(
    dampingRatio = estimateSpringParametersForIntOffset(durationMillis).second,
    stiffness = estimateSpringParametersForIntOffset(durationMillis).first,
    visibilityThreshold = IntOffset.VisibilityThreshold
)

private fun getSpringSpecForFloat(durationMillis: Int) = spring<Float>(
    dampingRatio = estimateSpringParametersForFloat(durationMillis).second,
    stiffness = estimateSpringParametersForFloat(durationMillis).first
)

/**
 * Renders a screen with animations, and handles nested navigation if the screen is a container.
 */
@Composable
fun NavigationRender(
    modifier: Modifier = Modifier,
    depth: Int = 1
) {
    //val navigationState by composeState<NavigationState>()
    val rootEntry by selectState<NavigationState>().map { it.rootEntry }.distinctUntilChanged().collectAsState(null)
    val backStack by selectState<NavigationState>().map { it.backStack }.distinctUntilChanged()
        .collectAsState(emptyList())
    val clearedBackStackWithNavigate by selectState<NavigationState>().map { it.clearedBackStackWithNavigate }
        .distinctUntilChanged().collectAsState(false)
    println("LFASKLDASD - NavigationRender - Incomming Nav Entry: $rootEntry")
    println("LFASKLDASD - NavigationRender - depth: $depth")
    // Track animation state
    var currentBackStackSize by remember { mutableStateOf(backStack.size) }
    var previousBackStackSize by remember { mutableStateOf(backStack.size) }
    var previousEntry by remember { mutableStateOf<NavigationEntry?>(null) }
    var currentEntry by remember { mutableStateOf<NavigationEntry?>(null) }

    // Update animation tracking
    LaunchedEffect(backStack.size) {
        previousBackStackSize = currentBackStackSize
        currentBackStackSize = backStack.size
    }

    LaunchedEffect(rootEntry) {
        previousEntry = currentEntry
        currentEntry = rootEntry
    }

    //println("LFASKLDASD - NavigationRender - isForward: $isForward")
    println("LFASKLDASD - NavigationRender - previousEntry: $previousEntry")
    println("LFASKLDASD - NavigationRender - currentEntry: $currentEntry")
    println("LFASKLDASD - NavigationRender - ===================================")
    key(rootEntry?.path) {
        Box(modifier = modifier.fillMaxSize()) {
            // AnimatedContent for transitions between screens
            AnimatedContent(
                modifier = Modifier.fillMaxSize().testTag("AnimatedContent"),
                targetState = rootEntry,
                transitionSpec = {
                    val isForward = clearedBackStackWithNavigate ||
                            (backStack.size > previousBackStackSize)

                    // Animation setup (unchanged)
                    val enterTransition = if (!isForward)
                        previousEntry?.screen?.popEnterTransition ?: targetState?.screen?.enterTransition
                    else
                        targetState?.screen?.enterTransition

                    val exitTransition = if (isForward)
                        targetState?.screen?.popExitTransition ?: initialState?.screen?.exitTransition
                    else
                        initialState?.screen?.exitTransition

                    getContentTransform(exitTransition, enterTransition, isForward).apply {
                        targetContentZIndex = if (clearedBackStackWithNavigate) {
                            previousBackStackSize++.toFloat()
                        } else {
                            backStack.size.toFloat()
                        }
                    }
                }
            ) { currentEntry ->
                // Render the screen content
                if (currentEntry?.screen?.isContainer == true && currentEntry.hasChild()) {
                    // For container screens with children, provide a child navigation composable
                    currentEntry.screen.Content(
                        params = currentEntry.params,
                        showDefaultContent = {
                            // Child navigation render - will show the child entry
                            NestedNavigationRender(
                                modifier = Modifier.fillMaxSize(),
                                depth = depth + 1
                            )
                            false
                        }
                    )
                } else {
                    // For regular screens or containers without children
                    currentEntry?.screen?.Content(
                        params = currentEntry.params,
                        showDefaultContent = { true }
                    )
                }
            }
        }
    }
}

@Composable
fun NestedNavigationRender(modifier: Modifier, depth: Int) {
    println("MERKADERKA - DEPTH: $depth")
    val childEntry by selectState<NavigationState>().map { it.rootEntry.childEntry }.distinctUntilChanged()
        .collectAsState(null)
    childEntry?.screen?.Content(emptyMap()) {
        false
    }
}

private fun getContentTransform(
    exitTransition: NavTransition?,
    enterTransition: NavTransition?,
    isForward: Boolean
): ContentTransform {
    val enter = getEnterAnimation(enterTransition, isForward)
    val exit = getExitAnimation(exitTransition, isForward)
    return enter togetherWith exit
}

private fun getEnterAnimation(transition: NavTransition?, isForward: Boolean): EnterTransition {
    return when (transition) {
        is NavTransition.SlideInRight -> {
            val spec = getSpringSpecForIntOffset(transition.durationMillis)
            if (isForward)
                slideInHorizontally(animationSpec = spec) { fullWidth -> fullWidth }
            else
                slideInHorizontally(animationSpec = spec) { fullWidth -> -fullWidth }
        }

        is NavTransition.SlideInLeft -> {
            val spec = getSpringSpecForIntOffset(transition.durationMillis)
            if (isForward)
                slideInHorizontally(animationSpec = spec) { fullWidth -> -fullWidth }
            else
                slideInHorizontally(animationSpec = spec) { fullWidth -> fullWidth }
        }

        is NavTransition.SlideUpBottom -> {
            val spec = getSpringSpecForIntOffset(transition.durationMillis)
            slideInVertically(animationSpec = spec) { fullHeight -> fullHeight }
        }

        is NavTransition.Hold -> {
            val spec = getSpringSpecForFloat(transition.durationMillis)
            fadeIn(animationSpec = spec, initialAlpha = 0.99f)
        }

        is NavTransition.Fade -> {
            val spec = getSpringSpecForFloat(transition.durationMillis)
            fadeIn(animationSpec = spec)
        }

        is NavTransition.Scale -> {
            val spec = getSpringSpecForFloat(transition.durationMillis)
            scaleIn(animationSpec = spec)
        }

        is NavTransition.CustomEnterTransition ->
            transition.enter

        else -> EnterTransition.None
    }
}

private fun getExitAnimation(transition: NavTransition?, isForward: Boolean): ExitTransition {
    return when (transition) {
        is NavTransition.SlideOutRight -> {
            val spec = getSpringSpecForIntOffset(transition.durationMillis)
            if (isForward)
                slideOutHorizontally(animationSpec = spec) { fullWidth -> fullWidth }
            else
                slideOutHorizontally(animationSpec = spec) { fullWidth -> -fullWidth }
        }

        is NavTransition.SlideOutLeft -> {
            val spec = getSpringSpecForIntOffset(transition.durationMillis)
            if (isForward)
                slideOutHorizontally(animationSpec = spec) { fullWidth -> -fullWidth }
            else
                slideOutHorizontally(animationSpec = spec) { fullWidth -> fullWidth }
        }

        is NavTransition.SlideOutBottom -> {
            val spec = getSpringSpecForIntOffset(transition.durationMillis)
            slideOutVertically(animationSpec = spec) { fullHeight -> fullHeight }
        }

        is NavTransition.Hold -> {
            val spec = getSpringSpecForFloat(transition.durationMillis)
            fadeOut(animationSpec = spec, targetAlpha = 0.99f)
        }

        is NavTransition.Fade -> {
            val spec = getSpringSpecForFloat(transition.durationMillis)
            fadeOut(animationSpec = spec)
        }

        is NavTransition.Scale -> {
            val spec = getSpringSpecForFloat(transition.durationMillis)
            scaleOut(animationSpec = spec)
        }

        is NavTransition.CustomExitTransition ->
            transition.exit

        else -> ExitTransition.None
    }
}