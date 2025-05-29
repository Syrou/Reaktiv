package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import io.github.syrou.reaktiv.navigation.transition.ResolvedNavTransition

@Composable
fun <T> NavTransitionContainer(
    targetState: T,
    modifier: Modifier = Modifier,
    enterTransition: ResolvedNavTransition,
    exitTransition: ResolvedNavTransition,
    isForward: Boolean = false,
    content: @Composable (T) -> Unit
) {
    val transitionState = remember {
        mutableStateOf(
            TransitionState(
                currentState = targetState,
                previousState = null,
                isTransitioning = false
            )
        )
    }
    val shouldAnimate by remember {
        derivedStateOf { transitionState.value.isTransitioning }
    }
    val currentEnterTransition by rememberUpdatedState(enterTransition)
    val currentExitTransition by rememberUpdatedState(exitTransition)
    val currentContent by rememberUpdatedState(content)
    val animationEasing by remember {
        derivedStateOf {
            if (isForward) LinearOutSlowInEasing else FastOutSlowInEasing
        }
    }

    val maxDuration = remember(currentEnterTransition.durationMillis, currentExitTransition.durationMillis) {
        maxOf(currentEnterTransition.durationMillis, currentExitTransition.durationMillis).coerceAtLeast(1) // Minimum 1ms to avoid division by zero
    }
    val overallProgress by animateFloatAsState(
        targetValue = if (shouldAnimate) 1f else 0f,
        animationSpec = tween(
            durationMillis = maxDuration,
            easing = animationEasing
        ),
        finishedListener = { progress ->
            if (progress == 1f) {
                transitionState.value = transitionState.value.copy(
                    previousState = null,
                    isTransitioning = false
                )
            }
        },
        label = "nav_transition"
    )
    val enterProgress = remember(overallProgress, currentEnterTransition.durationMillis, maxDuration) {
        if (currentEnterTransition.durationMillis == 0) {
            if (shouldAnimate) 1f else 0f // Immediate completion for 0ms duration
        } else {
            val enterTimeRatio = currentEnterTransition.durationMillis.toFloat() / maxDuration
            (overallProgress / enterTimeRatio).coerceIn(0f, 1f)
        }
    }

    val exitProgress = remember(overallProgress, currentExitTransition.durationMillis, maxDuration) {
        if (currentExitTransition.durationMillis == 0) {
            if (shouldAnimate) 1f else 0f // Immediate completion for 0ms duration
        } else {
            val exitTimeRatio = currentExitTransition.durationMillis.toFloat() / maxDuration
            (overallProgress / exitTimeRatio).coerceIn(0f, 1f)
        }
    }
    LaunchedEffect(targetState) {
        val current = transitionState.value
        if (targetState != current.currentState && !current.isTransitioning) {
            transitionState.value = TransitionState(
                currentState = targetState,
                previousState = current.currentState,
                isTransitioning = true
            )
        }
    }
    TransitionLayout(
        modifier = modifier,
        transitionState = transitionState.value,
        enterProgress = enterProgress,
        exitProgress = exitProgress,
        enterTransition = currentEnterTransition,
        exitTransition = currentExitTransition,
        isForward = isForward,
        content = currentContent
    )
}
private data class TransitionState<T>(
    val currentState: T,
    val previousState: T?,
    val isTransitioning: Boolean
)

@Composable
private fun <T> TransitionLayout(
    modifier: Modifier,
    transitionState: TransitionState<T>,
    enterProgress: Float,
    exitProgress: Float,
    enterTransition: ResolvedNavTransition,
    exitTransition: ResolvedNavTransition,
    isForward: Boolean,
    content: @Composable (T) -> Unit
) {
    Layout(
        content = {
            Box(
                modifier = Modifier
                    .layoutId("current")
                    .then(
                        if (transitionState.isTransitioning) {
                            Modifier.graphicsLayer {
                                val progress = if (transitionState.isTransitioning) enterProgress else 1f
                                alpha = enterTransition.alpha(progress)
                                scaleX = enterTransition.scaleX(progress)
                                scaleY = enterTransition.scaleY(progress)
                                translationX = enterTransition.translationX(progress)
                                translationY = enterTransition.translationY(progress)
                                rotationZ = enterTransition.rotationZ(progress)
                                transformOrigin = TransformOrigin(0.5f, 0.5f)
                            }
                        } else Modifier
                    )
            ) {
                content(transitionState.currentState)
            }
            transitionState.previousState?.let { prevState ->
                if (transitionState.isTransitioning) {
                    Box(
                        modifier = Modifier
                            .layoutId("previous")
                            .graphicsLayer {
                                alpha = exitTransition.alpha(exitProgress)
                                scaleX = exitTransition.scaleX(exitProgress)
                                scaleY = exitTransition.scaleY(exitProgress)
                                translationX = exitTransition.translationX(exitProgress)
                                translationY = exitTransition.translationY(exitProgress)
                                rotationZ = exitTransition.rotationZ(exitProgress)
                                transformOrigin = TransformOrigin(0.5f, 0.5f)
                            }
                    ) {
                        content(prevState)
                    }
                }
            }
        },
        modifier = modifier
    ) { measurables, constraints ->
        val currentMeasurable = measurables.firstOrNull { it.layoutId == "current" }
        val previousMeasurable = measurables.firstOrNull { it.layoutId == "previous" }
        if (currentMeasurable == null) {
            return@Layout layout(0, 0) {}
        }

        val currentPlaceable = currentMeasurable.measure(constraints)
        val previousPlaceable = previousMeasurable?.measure(constraints)
        val width = maxOf(currentPlaceable.width, previousPlaceable?.width ?: 0)
        val height = maxOf(currentPlaceable.height, previousPlaceable?.height ?: 0)

        layout(width, height) {
            val centerX = (width - currentPlaceable.width) / 2
            val centerY = (height - currentPlaceable.height) / 2

            if (!isForward) {
                currentPlaceable.placeRelative(centerX, centerY, zIndex = 0f)
                previousPlaceable?.let { placeable ->
                    val prevCenterX = (width - placeable.width) / 2
                    val prevCenterY = (height - placeable.height) / 2
                    placeable.placeRelative(prevCenterX, prevCenterY, zIndex = 1f)
                }
            } else {
                previousPlaceable?.let { placeable ->
                    val prevCenterX = (width - placeable.width) / 2
                    val prevCenterY = (height - placeable.height) / 2
                    placeable.placeRelative(prevCenterX, prevCenterY, zIndex = 0f)
                }
                currentPlaceable.placeRelative(centerX, centerY, zIndex = 1f)
            }
        }
    }
}