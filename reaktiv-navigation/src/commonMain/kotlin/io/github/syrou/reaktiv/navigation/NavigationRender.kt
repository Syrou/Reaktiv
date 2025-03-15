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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
 * Main navigation render component that efficiently handles nested navigation.
 */
@Composable
fun NavigationRender(
    modifier: Modifier = Modifier
) {
    val navigationState by composeState<NavigationState>()

    // Track backstack size for animation direction
    var currentBackStackSize by remember { mutableIntStateOf(navigationState.backStack.size) }
    var previousBackStackSize by remember { mutableIntStateOf(navigationState.backStack.size) }
    var hasNested by remember {
        mutableStateOf(false)
    }

    // Update animation tracking
    LaunchedEffect(navigationState.backStack.size) {
        previousBackStackSize = currentBackStackSize
        currentBackStackSize = navigationState.backStack.size
    }

    LaunchedEffect(navigationState.nestedBackStack.size){
        hasNested = navigationState.nestedBackStack.isNotEmpty()
    }



    // Get root entry
    val rootEntry = navigationState.rootEntry
    println("HERPADERPA - NavigationRender - rootEntry: $rootEntry")

    // Set up the root entry provider
    Box(modifier = modifier.fillMaxSize()) {
        // Start the navigation tree with the root entry
        if(rootEntry.screen.isContainer && !hasNested) {
            rootEntry.screen.Content(rootEntry.params){
                true
            }
        }else{
            rootEntry.screen.Content(rootEntry.params){
                NavigationEntryRenderer(
                    entry = rootEntry,
                    hasNested = hasNested,
                    isForward = navigationState.clearedBackStackWithNavigate ||
                            (navigationState.backStack.size > previousBackStackSize)
                )
                false
            }
        }
    }
}

/**
 * Renderer for a navigation entry that handles animations and nesting.
 * This component uses CompositionLocal for efficient recomposition.
 */
@Composable
private fun NavigationEntryRenderer(
    entry: NavigationEntry?,
    previousEntry: NavigationEntry? = null,
    isForward: Boolean = true,
    depth: Int = 0,
    hasNested: Boolean = false
) {
    // Create or reuse navigation entry state
    val store = rememberStore()
    var nestedEntry by remember { mutableStateOf<NavigationEntry?>(null) }
    var previousNestedEntry by remember { mutableStateOf<NavigationEntry?>(null) }

    LaunchedEffect(depth, entry?.path, hasNested) {
        launch {
            while(true){
                val nestedBackStack = store.selectState<NavigationState>().map { it.nestedBackStack }.first()
                //println("MERKADERKA - nestedBackStack size: ${nestedBackStack.size}")
                if (nestedBackStack.size > depth) {
                    previousNestedEntry = nestedEntry
                    nestedEntry = nestedBackStack.get(depth)
                } else {
                    previousNestedEntry = nestedEntry
                    nestedEntry = nestedBackStack.getOrNull(depth)
                }
                delay(2000)
            }
        }
    }
    println("MERKADERKA - NavigationEntryRenderer - depth: $depth isForward: $isForward")
    println("MERKADERKA - NavigationEntryRenderer - entry: $nestedEntry, previousEntry: $previousNestedEntry")

    // Provide the navigation entry to descendants

    if (nestedEntry?.screen?.isContainer == true && nestedEntry != null) {
        // For container screens with children, render the parent with the child composable
        println("MERKADERKA - WE HAVE A CONTAINER")
        nestedEntry?.screen?.Content(
            params = nestedEntry?.params ?: emptyMap(),
            showDefaultContent = {
                // Wrap the child navigation in AnimatedContent
                AnimatedContent(
                    targetState = nestedEntry,
                    transitionSpec = {
                        val enterTransition = targetState?.screen?.enterTransition
                        val exitTransition = initialState?.screen?.exitTransition
                        getContentTransform(exitTransition, enterTransition, isForward)
                    }
                ) { childEntry ->
                    NavigationEntryRenderer(
                        entry = null,
                        previousEntry = null,
                        isForward = isForward,
                        depth = depth + 1,
                        hasNested = hasNested
                    )
                }
                true
            }
        )
    } else {
        println("MERKADERKA - WE HAVE NORMAL SCREEN")
        // For regular screens or containers without children
        AnimatedContent(
            targetState = nestedEntry,
            transitionSpec = {
                val enterTransition = if (!isForward) {
                    previousEntry?.screen?.popEnterTransition ?: targetState?.screen?.enterTransition
                } else {
                    targetState?.screen?.enterTransition
                }

                val exitTransition = if (isForward) {
                    targetState?.screen?.popExitTransition ?: initialState?.screen?.exitTransition
                } else {
                    initialState?.screen?.exitTransition
                }

                getContentTransform(exitTransition, enterTransition, isForward)
            }
        ) { currentEntry ->
            currentEntry?.screen?.Content(
                params = currentEntry.params,
                showDefaultContent = { false }
            )
        }
    }
}

/**
 * A Composable that can be used within screen content to access the current navigation entry.
 * This allows screens to be aware of their navigation context without triggering recompositions.
 */
@Composable
fun NavigationEntryContent(
    content: @Composable (entry: NavigationEntry) -> Unit
) {
    val entry = rememberNavigationEntry()
    if (entry != null) {
        content(entry)
    }
}

/**
 * Creates a content transform based on the navigation transitions.
 */
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