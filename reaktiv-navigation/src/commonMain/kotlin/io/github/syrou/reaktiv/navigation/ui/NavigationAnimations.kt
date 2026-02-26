package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.transition.resolve
import io.github.syrou.reaktiv.navigation.util.AnimationDecision
import kotlinx.coroutines.launch

/**
 * Unified animation system for all navigation layers
 */
object NavigationAnimations {
    
    enum class AnimationType {
        SCREEN_ENTER,
        SCREEN_EXIT,
        MODAL_ENTER,
        MODAL_EXIT
    }
    
    /**
     * Animated content wrapper that handles all types of navigation animations
     */
    @Composable
    fun AnimatedEntry(
        entry: NavigationEntry,
        animationType: AnimationType,
        animationDecision: AnimationDecision?,
        screenWidth: Float,
        screenHeight: Float,
        zIndex: Float = 0f,
        onAnimationComplete: (() -> Unit)? = null,
        content: @Composable () -> Unit
    ) {
        when (animationType) {
            AnimationType.SCREEN_ENTER, AnimationType.SCREEN_EXIT -> {
                AnimatedScreenEntry(
                    entry = entry,
                    isEntering = animationType == AnimationType.SCREEN_ENTER,
                    animationDecision = animationDecision,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    zIndex = zIndex,
                    onAnimationComplete = onAnimationComplete,
                    content = content
                )
            }
            AnimationType.MODAL_ENTER, AnimationType.MODAL_EXIT -> {
                AnimatedModalEntry(
                    entry = entry,
                    isEntering = animationType == AnimationType.MODAL_ENTER,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    zIndex = zIndex,
                    onAnimationComplete = onAnimationComplete,
                    content = content
                )
            }
        }
    }
    
    /**
     * Screen animation for content layer entries
     */
    @Composable
    private fun AnimatedScreenEntry(
        entry: NavigationEntry,
        isEntering: Boolean,
        animationDecision: AnimationDecision?,
        screenWidth: Float,
        screenHeight: Float,
        zIndex: Float,
        onAnimationComplete: (() -> Unit)? = null,
        content: @Composable () -> Unit
    ) {
        val transition = if (isEntering) {
            animationDecision?.enterTransition ?: NavTransition.None
        } else {
            animationDecision?.exitTransition ?: NavTransition.None
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(zIndex)
                .animateNavTransition(
                    transition = transition,
                    isEntering = isEntering,
                    animationDecision = animationDecision,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    entryKey = entry.stableKey,
                    onAnimationComplete = onAnimationComplete
                )
                .let { modifier ->
                    // Block interactions for exit animations
                    if (!isEntering) {
                        modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    } else {
                        modifier
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(rememberNavigationBackgroundColor())
            ) {
                content()
            }
        }
    }
    
    /**
     * Modal animation for overlay layer entries
     */
    @Composable
    private fun AnimatedModalEntry(
        entry: NavigationEntry,
        isEntering: Boolean,
        screenWidth: Float,
        screenHeight: Float,
        zIndex: Float,
        onAnimationComplete: (() -> Unit)?,
        content: @Composable () -> Unit
    ) {
        val modal = entry.navigatable as? Modal
        val scope = rememberCoroutineScope()
        val store = rememberStore()
        
        val transition = when {
            isEntering -> entry.navigatable.popEnterTransition ?: entry.navigatable.enterTransition
            else -> entry.navigatable.popExitTransition ?: entry.navigatable.exitTransition
        }
        
        val shouldAnimate = transition != NavTransition.Hold && transition != NavTransition.None
        
        val resolved = remember(transition, screenWidth, screenHeight, shouldAnimate) {
            if (shouldAnimate) {
                transition.resolve(screenWidth, screenHeight, isForward = isEntering)
            } else null
        }
        
        val animationTrigger = remember(entry.stableKey) { mutableStateOf(false) }
        
        LaunchedEffect(entry.stableKey) {
            if (shouldAnimate) {
                animationTrigger.value = true
            } else {
                onAnimationComplete?.invoke()
            }
        }
        
        val targetValue = when {
            !shouldAnimate -> if (!isEntering) 0f else 1f
            !isEntering -> 0f
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
                if ((isEntering && progress == 1f) || (!isEntering && progress == 0f)) {
                    onAnimationComplete?.invoke()
                }
            },
            label = "modal_${entry.stableKey}"
        )
        
        val dimmerAlpha = if (modal?.shouldDimBackground == true) {
            modal.backgroundDimAlpha * animationProgress
        } else {
            0f
        }
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(zIndex)
        ) {
            // Dimmer background — always captures taps to prevent click pass-through.
            // Only navigates back when tapOutsideToDismiss is true.
            if (modal?.shouldDimBackground == true && dimmerAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = dimmerAlpha))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = isEntering
                        ) {
                            if (modal.tapOutsideToDismiss) {
                                scope.launch { store.navigateBack() }
                            }
                        }
                )
            }
            
            // Modal content
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
                content()
            }
        }
    }
}


/**
 * Modifier extension for screen transition animations
 */
@Composable
private fun Modifier.animateNavTransition(
    transition: NavTransition,
    isEntering: Boolean,
    animationDecision: AnimationDecision?,
    screenWidth: Float,
    screenHeight: Float,
    entryKey: String,
    onAnimationComplete: (() -> Unit)? = null
): Modifier {
    if (transition == NavTransition.None || animationDecision == null) {
        LaunchedEffect(entryKey, isEntering) {
            onAnimationComplete?.invoke()
        }
        return this
    }

    val shouldAnimate = if (isEntering) {
        animationDecision.shouldAnimateEnter
    } else {
        animationDecision.shouldAnimateExit
    }

    if (!shouldAnimate || screenWidth <= 0f || screenHeight <= 0f) {
        LaunchedEffect(entryKey, isEntering) {
            onAnimationComplete?.invoke()
        }
        return this
    }

    val animationKey = "${entryKey}_${isEntering}"
    val animatable = remember(animationKey) {
        Animatable(0f)
    }

    LaunchedEffect(animationKey) {
        animatable.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = transition.durationMillis,
                easing = LinearOutSlowInEasing
            )
        )
        onAnimationComplete?.invoke()
    }

    val progress = animatable.value
    val resolvedTransition = remember(transition, screenWidth, screenHeight, animationDecision.isForward) {
        transition.resolve(screenWidth, screenHeight, animationDecision.isForward)
    }

    return graphicsLayer {
        alpha = resolvedTransition.alpha(progress)
        scaleX = resolvedTransition.scaleX(progress)
        scaleY = resolvedTransition.scaleY(progress)
        translationX = resolvedTransition.translationX(progress)
        translationY = resolvedTransition.translationY(progress)
        rotationZ = resolvedTransition.rotationZ(progress)
        transformOrigin = TransformOrigin.Center
    }
}