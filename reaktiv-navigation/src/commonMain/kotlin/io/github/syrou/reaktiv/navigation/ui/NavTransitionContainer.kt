package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.transition.resolve

@Composable
fun NavTransitionContainer(
    currentEntry: NavigationEntry,
    previousEntry: NavigationEntry,
    screenWidth: Float,
    screenHeight: Float,
    animationId: Long,
    onAnimationComplete: () -> Unit,
    content: @Composable (Navigatable, StringAnyMap) -> Unit
) {
    val isForward = remember(currentEntry, previousEntry) {
        when {
            currentEntry.stackPosition > previousEntry.stackPosition -> true
            currentEntry.stackPosition < previousEntry.stackPosition -> false
            else -> {
                currentEntry.navigatable.route != previousEntry.navigatable.route
            }
        }
    }

    val (enterTransition, exitTransition) = remember(currentEntry, previousEntry, isForward) {
        getTransitionPair(currentEntry, previousEntry, isForward)
    }

    if (ReaktivDebug.isEnabled) {
        LaunchedEffect(enterTransition, exitTransition) {
            ReaktivDebug.nav("🎭 Transition pair:")
            ReaktivDebug.nav("  Enter: $enterTransition")
            ReaktivDebug.nav("  Exit: $exitTransition")
            ReaktivDebug.nav("  Is forward: $isForward")
        }
    }

    val resolvedEnter = remember(enterTransition, screenWidth, screenHeight, isForward) {
        enterTransition.resolve(screenWidth, screenHeight, isForward)
    }

    val resolvedExit = remember(exitTransition, screenWidth, screenHeight, isForward) {
        exitTransition.resolve(screenWidth, screenHeight, isForward)
    }

    val maxDuration = remember(resolvedEnter.durationMillis, resolvedExit.durationMillis) {
        maxOf(resolvedEnter.durationMillis, resolvedExit.durationMillis).coerceAtLeast(1)
    }

    if (ReaktivDebug.isEnabled) {
        LaunchedEffect(maxDuration) {
            ReaktivDebug.nav("⏱️ Animation duration: ${maxDuration}ms")
        }
    }

    val animationTrigger = remember(animationId) { mutableStateOf(false) }

    LaunchedEffect(animationId) {
        if (animationId > 0L) {
            animationTrigger.value = true
        }
    }

    val animationProgress by animateFloatAsState(
        targetValue = if (animationTrigger.value) 1f else 0f,
        animationSpec = tween(
            durationMillis = maxDuration,
            easing = LinearOutSlowInEasing
        ),
        finishedListener = { progress ->
            if (progress == 1f) {
                if (ReaktivDebug.isEnabled) {
                    ReaktivDebug.nav("🏁 Animation finished")
                }
                onAnimationComplete()
            }
        },
        label = "nav_transition_$animationId"
    )

    if (ReaktivDebug.isEnabled) {
        LaunchedEffect(animationProgress) {
            if (animationProgress == 0f || animationProgress == 1f) {
                ReaktivDebug.nav("📊 Animation progress: $animationProgress")
            }
        }
    }

    val enterProgress = remember(animationProgress, resolvedEnter.durationMillis, maxDuration, animationTrigger.value) {
        when {
            !animationTrigger.value -> 0f
            resolvedEnter.durationMillis == 0 -> 1f
            else -> {
                val progress = (animationProgress * maxDuration / resolvedEnter.durationMillis).coerceIn(0f, 1f)
                progress
            }
        }
    }

    val exitProgress = remember(animationProgress, resolvedExit.durationMillis, maxDuration, animationTrigger.value) {
        when {
            !animationTrigger.value -> 0f
            resolvedExit.durationMillis == 0 -> 1f
            else -> {
                val progress = (animationProgress * maxDuration / resolvedExit.durationMillis).coerceIn(0f, 1f)
                progress
            }
        }
    }

    val enterAlpha = remember(enterProgress) { resolvedEnter.alpha(enterProgress) }
    val exitAlpha = remember(exitProgress) { resolvedExit.alpha(exitProgress) }

    if (ReaktivDebug.isEnabled && (animationProgress == 0f || animationProgress == 1f)) {
        LaunchedEffect(enterAlpha, exitAlpha) {
            ReaktivDebug.nav("🎨 Alpha values - Enter: $enterAlpha, Exit: $exitAlpha")
        }
    }

    val exitTransforms = remember(exitProgress) {
        Triple(
            resolvedExit.scaleX(exitProgress),
            resolvedExit.scaleY(exitProgress),
            Pair(resolvedExit.translationX(exitProgress), resolvedExit.translationY(exitProgress))
        )
    }

    val enterTransforms = remember(enterProgress) {
        Triple(
            resolvedEnter.scaleX(enterProgress),
            resolvedEnter.scaleY(enterProgress),
            Pair(resolvedEnter.translationX(enterProgress), resolvedEnter.translationY(enterProgress))
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isForward) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = exitAlpha
                        scaleX = exitTransforms.first
                        scaleY = exitTransforms.second
                        translationX = exitTransforms.third.first
                        translationY = exitTransforms.third.second
                        rotationZ = resolvedExit.rotationZ(exitProgress)
                        transformOrigin = TransformOrigin.Center
                    }
            ) {
                content(previousEntry.navigatable, previousEntry.params)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = enterAlpha
                        scaleX = enterTransforms.first
                        scaleY = enterTransforms.second
                        translationX = enterTransforms.third.first
                        translationY = enterTransforms.third.second
                        rotationZ = resolvedEnter.rotationZ(enterProgress)
                        transformOrigin = TransformOrigin.Center
                    }
            ) {
                content(currentEntry.navigatable, currentEntry.params)
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = enterAlpha
                        scaleX = enterTransforms.first
                        scaleY = enterTransforms.second
                        translationX = enterTransforms.third.first
                        translationY = enterTransforms.third.second
                        rotationZ = resolvedEnter.rotationZ(enterProgress)
                        transformOrigin = TransformOrigin.Center
                    }
            ) {
                content(currentEntry.navigatable, currentEntry.params)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = exitAlpha
                        scaleX = exitTransforms.first
                        scaleY = exitTransforms.second
                        translationX = exitTransforms.third.first
                        translationY = exitTransforms.third.second
                        rotationZ = resolvedExit.rotationZ(exitProgress)
                        transformOrigin = TransformOrigin.Center
                    }
            ) {
                content(previousEntry.navigatable, previousEntry.params)
            }
        }
    }
}

private fun getTransitionPair(
    currentEntry: NavigationEntry,
    previousEntry: NavigationEntry,
    isForward: Boolean
): Pair<NavTransition, NavTransition> {

    if (ReaktivDebug.isEnabled) {
        ReaktivDebug.nav("🎭 getTransitionPair:")
        ReaktivDebug.nav("  Current: ${currentEntry.navigatable.route} (destination)")
        ReaktivDebug.nav("  Previous: ${previousEntry.navigatable.route} (source)")
        ReaktivDebug.nav("  Is forward: $isForward")
    }

    val enterTransition = when {
        !isForward -> currentEntry.navigatable.popEnterTransition ?: currentEntry.navigatable.enterTransition
        else -> currentEntry.navigatable.enterTransition
    }

    val exitTransition = when {
        isForward -> previousEntry.navigatable.exitTransition
        else -> previousEntry.navigatable.popExitTransition ?: previousEntry.navigatable.exitTransition
    }

    if (ReaktivDebug.isEnabled) {
        ReaktivDebug.nav("  Enter transition (${currentEntry.navigatable.route}): $enterTransition")
        ReaktivDebug.nav("  Exit transition (${previousEntry.navigatable.route}): $exitTransition")
    }

    return enterTransition to exitTransition
}