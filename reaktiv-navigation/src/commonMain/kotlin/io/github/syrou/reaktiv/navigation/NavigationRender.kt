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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
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

private val LocalHandledPaths = compositionLocalOf { mutableSetOf<String>() }

@Composable
fun NavigationRender(
    modifier: Modifier = Modifier,
    basePath: String = "",
    exclusive: Boolean = false,
    screenContent: @Composable (Screen, StringAnyMap, Boolean) -> Unit = { _, _, _ -> }
) {
    val navigationState by composeState<NavigationState>()
    println("DEBUG [NavigationRender] for basePath='$basePath', exclusive=$exclusive")
    println("DEBUG [NavigationRender] Current entry: ${navigationState.currentEntry.path}")

    // Build tree structure
    val treeState = remember(navigationState) { navigationState.buildNavigationTree() }
    val handledPaths = LocalHandledPaths.current
    println("DEBUG [NavigationRender] Currently handled paths: $handledPaths")

    // Track animation state
    var currentBackStackSize by remember { mutableStateOf(treeState.backStack.size) }
    var previousBackStackSize by remember { mutableStateOf(treeState.backStack.size) }
    var previousEntry by remember { mutableStateOf<NavigationEntry?>(null) }
    var currentEntry by remember { mutableStateOf<NavigationEntry>(treeState.currentEntry) }

    LaunchedEffect(treeState.backStack.size) {
        previousBackStackSize = currentBackStackSize
        currentBackStackSize = treeState.backStack.size
    }

    // Register exclusive paths
    DisposableEffect(basePath) {
        val pathsToAdd = mutableSetOf<String>()
        if (exclusive && basePath.isNotEmpty()) {
            pathsToAdd.add(basePath)
            treeState.availableScreens.keys.forEach { route ->
                if (route.startsWith("$basePath/")) {
                    pathsToAdd.add(route)
                }
            }
            handledPaths.addAll(pathsToAdd)
        }
        onDispose {
            handledPaths.removeAll(pathsToAdd)
        }
    }

    // KEY FIX: Check if current entry is handled by another NavigationRender
    val isCurrentEntryHandled = handledPaths.any { path ->
        navigationState.currentEntry.path.startsWith(path)
    }

    // Determine what to show
    val entryToDisplay = when {
        // If we're not at root level, show children as normal
        basePath.isNotEmpty() -> {
            val directChildren = treeState.backStack.filter { entry ->
                entry.parentPath == basePath
            }
            directChildren.lastOrNull()
        }

        // For root level, respect handled paths
        isCurrentEntryHandled -> {
            // Find the top-level entry that should handle the current path
            val handledPath = handledPaths.first { path ->
                navigationState.currentEntry.path.startsWith(path)
            }
            // Get the entry for this handled path
            treeState.backStack.find { it.path == handledPath }
        }

        // Default root behavior - show current entry
        else -> {
            treeState.currentEntry
        }
    }

    println("DEBUG [NavigationRender] Ready to render, entryToDisplay=${entryToDisplay?.path}")

    // Always provide composition local
    CompositionLocalProvider(LocalHandledPaths provides handledPaths) {
        Box(modifier = modifier.fillMaxSize()) {
            if (entryToDisplay != null) {
                // Update entry tracking for animations
                LaunchedEffect(entryToDisplay) {
                    val isForward = treeState.clearedBackStackWithNavigate ||
                            (treeState.backStack.size > previousBackStackSize)

                    previousEntry = currentEntry
                    currentEntry = entryToDisplay
                }

                // Restored AnimatedContent with animations
                AnimatedContent(
                    modifier = Modifier.fillMaxSize().testTag("AnimatedContent"),
                    targetState = entryToDisplay,
                    transitionSpec = {
                        val isForward = treeState.clearedBackStackWithNavigate ||
                                (treeState.backStack.size > previousBackStackSize)
                        val enterTransition = if (!isForward) previousEntry?.screen?.popEnterTransition
                            ?: targetState.screen.enterTransition else targetState.screen.enterTransition
                        val exitTransition = if (isForward) targetState.screen.popExitTransition
                            ?: initialState.screen.exitTransition else initialState.screen.exitTransition
                        getContentTransform(exitTransition, enterTransition, isForward).apply {
                            targetContentZIndex =
                                if (treeState.clearedBackStackWithNavigate) {
                                    previousBackStackSize++.toFloat()
                                } else {
                                    treeState.backStack.size.toFloat()
                                }
                        }
                    }
                ) { entry ->
                    screenContent.invoke(
                        entry.screen,
                        entry.params,
                        treeState.isLoading
                    )
                }
            }
        }
    }
}

private fun getContentTransform(
    exitTransition: NavTransition,
    enterTransition: NavTransition,
    isForward: Boolean
): ContentTransform {
    val enter = getEnterAnimation(enterTransition, isForward)
    val exit = getExitAnimation(exitTransition, isForward)
    return enter togetherWith exit
}

private fun getEnterAnimation(transition: NavTransition, isForward: Boolean): EnterTransition {
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

private fun getExitAnimation(transition: NavTransition, isForward: Boolean): ExitTransition {
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