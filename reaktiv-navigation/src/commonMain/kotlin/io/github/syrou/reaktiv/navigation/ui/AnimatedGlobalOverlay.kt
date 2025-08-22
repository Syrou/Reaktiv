package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.model.ModalAnimationState
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.transition.resolve
import kotlinx.coroutines.launch

@Composable
fun AnimatedGlobalOverlay(
    animationState: ModalAnimationState,
    screenWidth: Float,
    screenHeight: Float,
    screenContent: @Composable (Navigatable, Params) -> Unit,
    onAnimationComplete: () -> Unit
) {
    val modal = animationState.entry.navigatable as? Modal
    val scope = rememberCoroutineScope()
    val store = rememberStore()

    val transition = when {
        animationState.isEntering -> animationState.entry.navigatable.enterTransition
        animationState.isExiting -> animationState.entry.navigatable.popExitTransition
            ?: animationState.entry.navigatable.exitTransition
        else -> animationState.entry.navigatable.enterTransition
    }

    val shouldAnimate = transition != NavTransition.Hold && transition != NavTransition.None

    val resolved = remember(transition, screenWidth, screenHeight, shouldAnimate) {
        if (shouldAnimate) {
            transition.resolve(screenWidth, screenHeight, isForward = animationState.isEntering)
        } else null
    }

    val animationTrigger = remember(animationState.animationId) { mutableStateOf(false) }

    LaunchedEffect(animationState.animationId) {
        if (animationState.animationId > 0L && shouldAnimate) {
            animationTrigger.value = true
        } else if (!shouldAnimate) {
            onAnimationComplete()
        }
    }

    val targetValue = when {
        !shouldAnimate -> if (animationState.isExiting) 0f else 1f
        animationState.isExiting -> 0f
        animationTrigger.value -> 1f
        else -> 0f
    }

    val animationProgress by animateFloatAsState(
        targetValue = targetValue,
        animationSpec = if (shouldAnimate && resolved != null) {
            tween(
                durationMillis = resolved.durationMillis,
                easing = LinearOutSlowInEasing
            )
        } else {
            tween(durationMillis = 0)
        },
        finishedListener = { progress ->
            if ((animationState.isEntering && progress == 1f) ||
                (animationState.isExiting && progress == 0f)) {
                onAnimationComplete()
            }
        },
        label = "modal_${animationState.animationId}"
    )

    val dimmerAlpha = if (modal?.shouldDimBackground == true) {
        modal.backgroundDimAlpha * animationProgress
    } else {
        0f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(2000f + animationState.entry.navigatable.elevation)
    ) {
        if (modal?.shouldDimBackground == true && dimmerAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dimmerAlpha))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = modal.onDismissTapOutside != null && !animationState.isExiting
                    ) {
                        scope.launch {
                            modal.onDismissTapOutside?.invoke(store)
                        }
                    }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { modifier ->
                    if (shouldAnimate && resolved != null) {
                        modifier.graphicsLayer {
                            alpha = resolved.alpha(animationProgress)
                            scaleX = resolved.scaleX(animationProgress)
                            scaleY = resolved.scaleY(animationProgress)
                            translationX = resolved.translationX(animationProgress)
                            translationY = resolved.translationY(animationProgress)
                            rotationZ = resolved.rotationZ(animationProgress)
                            transformOrigin = TransformOrigin.Center
                        }
                    } else {
                        modifier
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            screenContent(animationState.entry.navigatable, animationState.entry.params)
        }
    }
}