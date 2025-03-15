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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import io.github.syrou.reaktiv.compose.composeState
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt

private const val TAG = "NavigationRender"
private const val TARGET_STIFFNESS = 400f

private fun estimateSpringParametersForIntOffset(durationMillis: Int): Pair<Float, Float> {
    val stiffness = TARGET_STIFFNESS
    val dampingRatio = sqrt(stiffness / (4 * PI.pow(2) * (1000f / durationMillis).pow(2)))
    return Pair(stiffness, dampingRatio.toFloat())
}

private fun estimateSpringParametersForFloat(durationMillis: Int): Pair<Float, Float> {
    val stiffness = TARGET_STIFFNESS
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
 * Main navigation renderer that manages the root screen and its nested navigation
 */
@Composable
fun NavigationRender(
    modifier: Modifier = Modifier,
) {
    val navigationState by composeState<NavigationState>()

    // Log state
    SideEffect {
        println("$TAG - STATE: BackStackSize=${navigationState.backStack.size}")
        println("$TAG - HIERARCHY: Root=${navigationState.rootEntry.path}")
        var entry = navigationState.rootEntry
        var level = 0
        while (entry.hasChild()) {
            level++
            entry = entry.childEntry!!
            println("$TAG - HIERARCHY: Level $level = ${entry.path}")
        }
    }

    // Render the root screen with its navigation hierarchy
    Box(modifier = modifier) {
        // First render the root screen itself
        navigationState.rootEntry.screen.Content(
            params = navigationState.rootEntry.params,
            showDefaultContent = {
                // If root has a child, render the nested navigation
                if (navigationState.rootEntry.hasChild()) {
                    // Use the more stable LevelNavigator for nested levels
                    LevelNavigator(
                        level = 1,
                        parentPath = navigationState.rootEntry.path
                    )
                    false
                }else{
                    true
                }
            }
        )
    }
}

/**
 * Navigates and animates transitions at a specific level of nesting
 */
@Composable
private fun LevelNavigator(
    level: Int,
    parentPath: String
) {
    val navigationState by composeState<NavigationState>()

    // Get the entry for this level by traversing from root
    val currentEntry = remember(navigationState.rootEntry, level) {
        derivedStateOf {
            // Navigate down from root to this level
            var entry = navigationState.rootEntry
            var currentLevel = 0

            while (currentLevel < level && entry.hasChild()) {
                entry = entry.childEntry!!
                currentLevel++
            }

            // Return the entry at this level, or null if we couldn't reach it
            if (currentLevel == level) entry else null
        }
    }.value

    // Keep track of the previous entry at this level
    var previousEntryState by remember { mutableStateOf<NavigationEntry?>(null) }

    if (currentEntry == null) {
        // No entry at this level - this can happen during navigation changes
        println("$TAG - LEVEL($level): No entry found at this level")
        return
    }

    // Prepare the animation state in a thread-safe way
    val currentPath = currentEntry.path
    val previousPath = previousEntryState?.path

    // Determine if we're going forward or backward
    val isForward = if (previousPath == null) {
        true
    } else if (currentPath != previousPath) {
        currentPath.length > previousPath.length || currentPath > previousPath
    } else {
        false
    }

    println("$TAG - LEVEL($level): Current=$currentPath")
    println("$TAG - LEVEL($level): Previous=$previousPath")
    println("$TAG - LEVEL($level): Forward=$isForward")

    // Animate content changes at this level
    AnimatedContent(
        modifier = Modifier.fillMaxSize(),
        targetState = currentEntry,
        label = "Level $level Navigation",
        transitionSpec = {
            val prevEntry = previousEntryState

            // Get transitions from screen definitions
            val enterTransition = if (!isForward) {
                targetState.screen.popEnterTransition ?: targetState.screen.enterTransition
            } else {
                targetState.screen.enterTransition
            }

            val exitTransition = if (isForward && prevEntry != null) {
                prevEntry.screen.popExitTransition ?: prevEntry.screen.exitTransition ?: NavTransition.None
            } else if (!isForward && prevEntry != null) {
                prevEntry.screen.exitTransition ?: NavTransition.None
            } else {
                NavTransition.None
            }

            val enterTransName = enterTransition::class.simpleName
            val exitTransName = exitTransition::class.simpleName

            println("$TAG - LEVEL($level): ANIMATING: Enter=$enterTransName, Exit=$exitTransName")

            // Create direction-specific animations
            val enterAnim = createDirectionalEnterAnimation(enterTransition, isForward)
            val exitAnim = createDirectionalExitAnimation(exitTransition, isForward)

            enterAnim togetherWith exitAnim
        }
    ) { entry ->
        // Render the screen content
        // Box wrapper ensures the entire content animates as one
        Box(modifier = Modifier.fillMaxSize()) {
            entry.screen.Content(
                params = entry.params,
                showDefaultContent =  {
                    // Check if this entry has a child that needs to be rendered
                    if (entry.hasChild()) {
                        // Recursively render the next level
                        LevelNavigator(
                            level = level + 1,
                            parentPath = entry.path
                        )
                        false
                    } else {
                        println("$TAG - LEVEL($level): No children for ${entry.path}")
                        true
                    }
                }
            )
        }
    }

    // Update previous entry for next render
    SideEffect {
        if (previousEntryState?.path != currentEntry.path) {
            previousEntryState = currentEntry
        }
    }
}

/**
 * Creates an appropriate enter animation based on transition type and direction
 */
private fun createDirectionalEnterAnimation(transition: NavTransition, isForward: Boolean): EnterTransition {
    return when (transition) {
        is NavTransition.SlideInRight -> {
            val spec = getSpringSpecForIntOffset(transition.durationMillis)
            if (isForward) {
                slideInHorizontally(animationSpec = spec) { fullWidth -> fullWidth }
            } else {
                slideInHorizontally(animationSpec = spec) { fullWidth -> -fullWidth }
            }
        }

        is NavTransition.SlideInLeft -> {
            val spec = getSpringSpecForIntOffset(transition.durationMillis)
            if (isForward) {
                slideInHorizontally(animationSpec = spec) { fullWidth -> -fullWidth }
            } else {
                slideInHorizontally(animationSpec = spec) { fullWidth -> fullWidth }
            }
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

        is NavTransition.CustomEnterTransition -> transition.enter

        else -> EnterTransition.None
    }
}

/**
 * Creates an appropriate exit animation based on transition type and direction
 */
private fun createDirectionalExitAnimation(transition: NavTransition, isForward: Boolean): ExitTransition {
    return when (transition) {
        is NavTransition.SlideOutRight -> {
            val spec = getSpringSpecForIntOffset(transition.durationMillis)
            if (isForward) {
                slideOutHorizontally(animationSpec = spec) { fullWidth -> -fullWidth }
            } else {
                slideOutHorizontally(animationSpec = spec) { fullWidth -> fullWidth }
            }
        }

        is NavTransition.SlideOutLeft -> {
            val spec = getSpringSpecForIntOffset(transition.durationMillis)
            if (isForward) {
                slideOutHorizontally(animationSpec = spec) { fullWidth -> fullWidth }
            } else {
                slideOutHorizontally(animationSpec = spec) { fullWidth -> -fullWidth }
            }
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

        is NavTransition.CustomExitTransition -> transition.exit

        else -> ExitTransition.None
    }
}