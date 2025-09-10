package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.model.NavigationAnimationState
import io.github.syrou.reaktiv.navigation.util.determineContentAnimationDecision
import io.github.syrou.reaktiv.core.util.ReaktivDebug

@Composable
fun RenderContentWithAnimation(
    animationState: NavigationAnimationState,
    screenWidth: Float,
    screenHeight: Float,
    navigatable: Navigatable,
    params: Params,
    contentCache: Map<String, @Composable () -> Unit>,
    currentContentKey: String,
    previousContentKey: String?,
    fallbackContent: @Composable (Navigatable, Params) -> Unit,
    onAnimationComplete: () -> Unit
) {
    ReaktivDebug.trace("🎞️ RenderContentWithAnimation - isAnimating: ${animationState.isAnimating}, hasCache: ${contentCache.containsKey(currentContentKey)}, currentKey: $currentContentKey")
    
    if (animationState.isAnimating && animationState.previousEntry != null) {
        ReaktivDebug.trace("🎞️ Taking animation path - previousEntry exists")
        val animationDecision = determineContentAnimationDecision(
            animationState.previousEntry,
            animationState.currentEntry
        )

        NavTransitionContainer(
            currentEntry = animationState.currentEntry,
            previousEntry = animationState.previousEntry,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            animationId = animationState.animationId,
            animationDecision = animationDecision,
            onAnimationComplete = onAnimationComplete
        ) { nav, p ->
            val entryKey = "${nav.route}_${nav.hashCode()}_${p.hashCode()}"
            when (nav) {
                animationState.currentEntry.navigatable if p == animationState.currentEntry.params -> {
                    contentCache[currentContentKey]?.invoke() ?: fallbackContent(nav, p)
                }
                animationState.previousEntry.navigatable if p == animationState.previousEntry.params -> {
                    contentCache[previousContentKey]?.invoke() ?: fallbackContent(nav, p)
                }
                else -> {
                    key("fallback_$entryKey") {
                        fallbackContent(nav, p)
                    }
                }
            }
        }
    } else {
        ReaktivDebug.trace("🎞️ Taking post-animation path - isAnimating: ${animationState.isAnimating}")
        // Post-animation: try cached content first, but ensure it renders in proper context
        val cachedContent = contentCache[currentContentKey]
        if (cachedContent != null) {
            ReaktivDebug.trace("🎞️ Using cached content for: $currentContentKey")
            key("cached_$currentContentKey") {
                cachedContent()
            }
        } else {
            ReaktivDebug.trace("🎞️ Using fallback content for: $currentContentKey")
            key("direct_$currentContentKey") {
                fallbackContent(navigatable, params)
            }
        }
    }
}