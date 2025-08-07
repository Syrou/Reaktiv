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
import androidx.compose.ui.zIndex
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.transition.ResolvedNavTransition
import io.github.syrou.reaktiv.navigation.transition.resolve
import io.github.syrou.reaktiv.navigation.util.AnimationDecision

@Composable
fun NavTransitionContainer(
    currentEntry: NavigationEntry,
    previousEntry: NavigationEntry,
    screenWidth: Float,
    screenHeight: Float,
    animationId: Long,
    animationDecision: AnimationDecision,
    onAnimationComplete: () -> Unit,
    content: @Composable (Navigatable, StringAnyMap) -> Unit
) {
    if (ReaktivDebug.isEnabled) {
        LaunchedEffect(animationDecision) {
            ReaktivDebug.nav("🎭 NavTransitionContainer:")
            ReaktivDebug.nav("  Enter animate: ${animationDecision.shouldAnimateEnter}")
            ReaktivDebug.nav("  Exit animate: ${animationDecision.shouldAnimateExit}")
            ReaktivDebug.nav("  Enter transition: ${animationDecision.enterTransition}")
            ReaktivDebug.nav("  Exit transition: ${animationDecision.exitTransition}")
            ReaktivDebug.nav("  Is forward: ${animationDecision.isForward}")
        }
    }

    if (!animationDecision.hasAnyAnimation) {
        content(currentEntry.navigatable, currentEntry.params)
        LaunchedEffect(animationId) { onAnimationComplete() }
        return
    }

    val resolvedEnter =
        remember(animationDecision.enterTransition, screenWidth, screenHeight, animationDecision.shouldAnimateEnter) {
            if (animationDecision.shouldAnimateEnter) {
                animationDecision.enterTransition.resolve(screenWidth, screenHeight, animationDecision.isForward)
            } else null
        }

    val resolvedExit =
        remember(animationDecision.exitTransition, screenWidth, screenHeight, animationDecision.shouldAnimateExit) {
            if (animationDecision.shouldAnimateExit) {
                animationDecision.exitTransition.resolve(screenWidth, screenHeight, animationDecision.isForward)
            } else null
        }

    val animationTrigger = remember(animationId) { mutableStateOf(false) }

    LaunchedEffect(animationId) {
        if (animationId > 0L) {
            ReaktivDebug.nav("🚀 Starting animation with ID: $animationId")
            animationTrigger.value = true
        }
    }

    val enterProgress by animateFloatAsState(
        targetValue = if (animationTrigger.value && animationDecision.shouldAnimateEnter) 1f else 0f,
        animationSpec = if (animationDecision.shouldAnimateEnter && resolvedEnter != null) {
            tween(resolvedEnter.durationMillis, 0, LinearOutSlowInEasing)
        } else tween(0),
        label = "enter_progress_$animationId"
    )

    val exitProgress by animateFloatAsState(
        targetValue = if (animationTrigger.value && animationDecision.shouldAnimateExit) 1f else 0f,
        animationSpec = if (animationDecision.shouldAnimateExit && resolvedExit != null) {
            tween(resolvedExit.durationMillis, 0, LinearOutSlowInEasing)
        } else tween(0),
        label = "exit_progress_$animationId"
    )

    LaunchedEffect(
        enterProgress,
        exitProgress,
        animationDecision.shouldAnimateEnter,
        animationDecision.shouldAnimateExit,
        animationTrigger.value
    ) {
        if (animationTrigger.value) {
            val enterFinished = !animationDecision.shouldAnimateEnter || enterProgress >= 1f
            val exitFinished = !animationDecision.shouldAnimateExit || exitProgress >= 1f

            if (enterFinished && exitFinished) {
                ReaktivDebug.nav("🏁 Animation completed")
                onAnimationComplete()
            }
        }
    }

    if (ReaktivDebug.isEnabled) {
        LaunchedEffect(enterProgress, exitProgress) {
            ReaktivDebug.nav("📊 Progress - Enter: $enterProgress, Exit: $exitProgress")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val enterScreenAnimated = animationDecision.shouldAnimateEnter
        val exitScreenAnimated = animationDecision.shouldAnimateExit

        when {
            enterScreenAnimated && !exitScreenAnimated -> {
                RenderExitScreen(previousEntry, resolvedExit, exitProgress, content)
                RenderEnterScreen(currentEntry, resolvedEnter, enterProgress, content)
            }

            !enterScreenAnimated && exitScreenAnimated -> {
                RenderEnterScreen(currentEntry, resolvedEnter, enterProgress, content)
                RenderExitScreen(previousEntry, resolvedExit, exitProgress, content)
            }

            else -> {
                if (animationDecision.isForward) {
                    RenderExitScreen(previousEntry, resolvedExit, exitProgress, content)
                    RenderEnterScreen(currentEntry, resolvedEnter, enterProgress, content)
                } else {
                    RenderEnterScreen(currentEntry, resolvedEnter, enterProgress, content)
                    RenderExitScreen(previousEntry, resolvedExit, exitProgress, content)
                }
            }
        }
    }
}

@Composable
private fun RenderEnterScreen(
    entry: NavigationEntry,
    resolvedTransition: ResolvedNavTransition?,
    progress: Float,
    content: @Composable (Navigatable, StringAnyMap) -> Unit
) {
    if (resolvedTransition != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = resolvedTransition.alpha(progress)
                    scaleX = resolvedTransition.scaleX(progress)
                    scaleY = resolvedTransition.scaleY(progress)
                    translationX = resolvedTransition.translationX(progress)
                    translationY = resolvedTransition.translationY(progress)
                    rotationZ = resolvedTransition.rotationZ(progress)
                    transformOrigin = TransformOrigin.Center
                }
                .zIndex(1f)
        ) {
            content(entry.navigatable, entry.params)
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            content(entry.navigatable, entry.params)
        }
    }
}

@Composable
private fun RenderExitScreen(
    entry: NavigationEntry,
    resolvedTransition: ResolvedNavTransition?,
    progress: Float,
    content: @Composable (Navigatable, StringAnyMap) -> Unit
) {
    if (resolvedTransition != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = resolvedTransition.alpha(progress)
                    scaleX = resolvedTransition.scaleX(progress)
                    scaleY = resolvedTransition.scaleY(progress)
                    translationX = resolvedTransition.translationX(progress)
                    translationY = resolvedTransition.translationY(progress)
                    rotationZ = resolvedTransition.rotationZ(progress)
                    transformOrigin = TransformOrigin.Center
                }
                .zIndex(0f)
        ) {
            content(entry.navigatable, entry.params)
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(0f)
        ) {
            content(entry.navigatable, entry.params)
        }
    }
}