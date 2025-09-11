package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import io.github.syrou.reaktiv.navigation.param.Params
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
    currentScreenContent: (@Composable () -> Unit)? = null,
    previousScreenContent: (@Composable () -> Unit)? = null,
    content: @Composable (Navigatable, Params) -> Unit
) {
    // Recomposition tracking
    ReaktivDebug.trace("🔄 NavTransitionContainer recomposition - animationId: $animationId")
    
    // Entry object identity tracking
    ReaktivDebug.trace("🏷️ Entry identity - Current: ${currentEntry.hashCode()}, Previous: ${previousEntry.hashCode()}")
    ReaktivDebug.trace("🏷️ Entry details - Current: [${currentEntry.navigatable.route}@${currentEntry.stackPosition}:${currentEntry.graphId}], Previous: [${previousEntry.navigatable.route}@${previousEntry.stackPosition}:${previousEntry.graphId}]")
    ReaktivDebug.trace("🏷️ Navigatable objects - Current: ${currentEntry.navigatable.hashCode()}, Previous: ${previousEntry.navigatable.hashCode()}")
    
    // Parameter analysis
    ReaktivDebug.trace("📋 Params - Current hash: ${currentEntry.params.hashCode()}, Previous hash: ${previousEntry.params.hashCode()}")
    ReaktivDebug.trace("📋 Params content - Current: ${currentEntry.params}, Previous: ${previousEntry.params}")
    
    val backgroundColor = rememberNavigationBackgroundColor()

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

    if (animationDecision.hasAnyAnimation) {
        val resolvedEnter =
            remember(animationDecision.enterTransition, screenWidth, screenHeight, animationDecision.shouldAnimateEnter) {
                ReaktivDebug.trace("🔄 Resolving enter transition - shouldAnimate: ${animationDecision.shouldAnimateEnter}")
                if (animationDecision.shouldAnimateEnter) {
                    val resolved = animationDecision.enterTransition.resolve(screenWidth, screenHeight, animationDecision.isForward)
                    ReaktivDebug.trace("⚙️ Enter transition resolved - duration: ${resolved.durationMillis}ms")
                    resolved
                } else {
                    ReaktivDebug.trace("⏩ Enter transition skipped")
                    null
                }
            }

        val resolvedExit =
            remember(animationDecision.exitTransition, screenWidth, screenHeight, animationDecision.shouldAnimateExit) {
                ReaktivDebug.trace("🔄 Resolving exit transition - shouldAnimate: ${animationDecision.shouldAnimateExit}")
                if (animationDecision.shouldAnimateExit) {
                    val resolved = animationDecision.exitTransition.resolve(screenWidth, screenHeight, animationDecision.isForward)
                    ReaktivDebug.trace("⚙️ Exit transition resolved - duration: ${resolved.durationMillis}ms")
                    resolved
                } else {
                    ReaktivDebug.trace("⏩ Exit transition skipped")
                    null
                }
            }

        val animationTrigger = remember(animationId) { mutableStateOf(false) }
        ReaktivDebug.trace("🆔 Animation setup - ID: $animationId, trigger: ${animationTrigger.value}")

        LaunchedEffect(animationId) {
            if (animationId > 0L) {
                ReaktivDebug.nav("🚀 Starting animation with ID: $animationId")
                ReaktivDebug.trace("🎬 Animation trigger activated for ID: $animationId")
                animationTrigger.value = true
            } else {
                ReaktivDebug.trace("⏸️ No animation - ID is $animationId")
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

        val currentScreenKey = remember(currentEntry.navigatable.route, currentEntry.graphId, currentEntry.stackPosition) {
            val key = "${currentEntry.navigatable.route}_${currentEntry.graphId}_${currentEntry.stackPosition}"
            ReaktivDebug.trace("🔑 Current screen key: $key")
            key
        }

        val previousScreenKey = remember(previousEntry.navigatable.route, previousEntry.graphId, previousEntry.stackPosition) {
            val key = "${previousEntry.navigatable.route}_${previousEntry.graphId}_${previousEntry.stackPosition}"
            ReaktivDebug.trace("🔑 Previous screen key: $key")
            key
        }

        val currentScreenContentComposable = currentScreenContent ?: remember(currentScreenKey, currentEntry.params) {
            ReaktivDebug.trace("📱 Creating movableContent for current: $currentScreenKey, params: ${currentEntry.params}")
            movableContentOf {
                ReaktivDebug.trace("🎬 Rendering current screen content: ${currentEntry.navigatable.route}")
                content(currentEntry.navigatable, currentEntry.params)
            }
        }

        val previousScreenContentComposable = previousScreenContent ?: remember(previousScreenKey, previousEntry.params) {
            ReaktivDebug.trace("📱 Creating movableContent for previous: $previousScreenKey, params: ${previousEntry.params}")
            movableContentOf {
                ReaktivDebug.trace("🎬 Rendering previous screen content: ${previousEntry.navigatable.route}")
                content(previousEntry.navigatable, previousEntry.params)
            }
        }

        LaunchedEffect(
            enterProgress,
            exitProgress,
            animationDecision.shouldAnimateEnter,
            animationDecision.shouldAnimateExit,
            animationTrigger.value
        ) {
            ReaktivDebug.trace("🔄 Animation completion effect triggered - trigger: ${animationTrigger.value}")
            if (animationTrigger.value) {
                val enterFinished = !animationDecision.shouldAnimateEnter || enterProgress >= 1f
                val exitFinished = !animationDecision.shouldAnimateExit || exitProgress >= 1f

                ReaktivDebug.trace("📊 Animation check - Enter finished: $enterFinished ($enterProgress), Exit finished: $exitFinished ($exitProgress)")

                if (enterFinished && exitFinished) {
                    ReaktivDebug.trace("🏁 Animation completed for current: $currentScreenKey, previous: $previousScreenKey")
                    onAnimationComplete()
                } else {
                    ReaktivDebug.trace("⏳ Animation in progress - waiting for completion")
                }
            } else {
                ReaktivDebug.trace("⏸️ Animation trigger not activated yet")
            }
        }

        if (ReaktivDebug.isEnabled) {
            LaunchedEffect(enterProgress, exitProgress) {
                ReaktivDebug.nav("📊 Progress - Enter: $enterProgress, Exit: $exitProgress")
            }
        }

        // Additional stability checks
        ReaktivDebug.trace("🔍 Stability check - Animation hasAnyAnimation: ${animationDecision.hasAnyAnimation}")
        ReaktivDebug.trace("🔍 Stability check - Background color: $backgroundColor")



        // MovableContent identity tracking
        ReaktivDebug.trace("🎭 MovableContent identity - Current: ${currentScreenContentComposable.hashCode()}, Previous: ${previousScreenContentComposable.hashCode()}")
        ReaktivDebug.trace("🎭 MovableContent keys comparison - Current key: '$currentScreenKey', Previous key: '$previousScreenKey'")
        
        // Check for duplicate keys
        if (currentScreenKey == previousScreenKey) {
            ReaktivDebug.trace("⚠️ CRITICAL: Current and previous screen keys are identical: $currentScreenKey")
            ReaktivDebug.trace("⚠️ This may cause movableContent runtime errors!")
            ReaktivDebug.trace("⚠️ Entry routes - Current: ${currentEntry.navigatable.route}, Previous: ${previousEntry.navigatable.route}")
            ReaktivDebug.trace("⚠️ Entry positions - Current: ${currentEntry.stackPosition}, Previous: ${previousEntry.stackPosition}")
            ReaktivDebug.trace("⚠️ Entry graphIds - Current: ${currentEntry.graphId}, Previous: ${previousEntry.graphId}")
            ReaktivDebug.trace("⚠️ Entry hashes - Current: ${currentEntry.hashCode()}, Previous: ${previousEntry.hashCode()}")
        }

        ReaktivDebug.trace("🎭 Transition setup - Current: $currentScreenKey, Previous: $previousScreenKey")
        ReaktivDebug.trace("🎭 Animation decision - Enter: ${animationDecision.shouldAnimateEnter}, Exit: ${animationDecision.shouldAnimateExit}")
        ReaktivDebug.trace("🎭 Screen dimensions: ${screenWidth}x${screenHeight}")

        Box(modifier = Modifier.fillMaxSize()) {
            val enterScreenAnimated = animationDecision.shouldAnimateEnter
            val exitScreenAnimated = animationDecision.shouldAnimateExit
            
            ReaktivDebug.trace("🎨 Rendering phase - Enter animated: $enterScreenAnimated, Exit animated: $exitScreenAnimated, Forward: ${animationDecision.isForward}")

            when {
                enterScreenAnimated && !exitScreenAnimated -> {
                    ReaktivDebug.trace("🎨 Render case: Enter animated only")
                    RenderExitScreen(previousEntry, resolvedExit, exitProgress, backgroundColor, previousScreenContentComposable)
                    RenderEnterScreen(currentEntry, resolvedEnter, enterProgress, backgroundColor, currentScreenContentComposable)
                }

                !enterScreenAnimated && exitScreenAnimated -> {
                    ReaktivDebug.trace("🎨 Render case: Exit animated only")
                    RenderEnterScreen(currentEntry, resolvedEnter, enterProgress, backgroundColor, currentScreenContentComposable)
                    RenderExitScreen(previousEntry, resolvedExit, exitProgress, backgroundColor, previousScreenContentComposable)
                }

                else -> {
                    ReaktivDebug.trace("🎨 Render case: Both or neither animated, forward: ${animationDecision.isForward}")
                    if (animationDecision.isForward) {
                        RenderExitScreen(previousEntry, resolvedExit, exitProgress, backgroundColor, previousScreenContentComposable)
                        RenderEnterScreen(currentEntry, resolvedEnter, enterProgress, backgroundColor, currentScreenContentComposable)
                    } else {
                        RenderEnterScreen(currentEntry, resolvedEnter, enterProgress, backgroundColor, currentScreenContentComposable)
                        RenderExitScreen(previousEntry, resolvedExit, exitProgress, backgroundColor, previousScreenContentComposable)
                    }
                }
            }
        }
    } else {
        // no animation case
        ReaktivDebug.trace("🚫 No animation - rendering current screen directly: ${currentEntry.navigatable.route}")
        content(currentEntry.navigatable, currentEntry.params)
        LaunchedEffect(animationId) { 
            ReaktivDebug.trace("🏁 No-animation completion callback")
            onAnimationComplete() 
        }
    }
}

@Composable
private fun RenderEnterScreen(
    entry: NavigationEntry,
    resolvedTransition: ResolvedNavTransition?,
    progress: Float,
    backgroundColor: Color,
    content: @Composable () -> Unit
) {
    val screenKey = "enter_${entry.navigatable.route}_${entry.graphId}_${entry.stackPosition}"
    ReaktivDebug.trace("🟢 RenderEnterScreen called: $screenKey, progress: $progress, hasTransition: ${resolvedTransition != null}")
    key(screenKey) {
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
                    .zIndex(2f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                ) {
                    ReaktivDebug.trace("🎬 Calling enter screen content (animated): ${entry.navigatable.route}")
                    content()
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(0f)
                    .background(backgroundColor)
            ) {
                ReaktivDebug.trace("🎬 Calling enter screen content (no animation): ${entry.navigatable.route}")
                content()
            }
        }
    }
}

@Composable
private fun RenderExitScreen(
    entry: NavigationEntry,
    resolvedTransition: ResolvedNavTransition?,
    progress: Float,
    backgroundColor: Color,
    content: @Composable () -> Unit
) {
    val screenKey = "exit_${entry.navigatable.route}_${entry.graphId}_${entry.stackPosition}"
    ReaktivDebug.trace("🔴 RenderExitScreen called: $screenKey, progress: $progress, hasTransition: ${resolvedTransition != null}")
    key(screenKey) {
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                ) {
                    ReaktivDebug.trace("🎬 Calling exit screen content (animated): ${entry.navigatable.route}")
                    content()
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundColor)
            ) {
                ReaktivDebug.trace("🎬 Calling exit screen content (no animation): ${entry.navigatable.route}")
                content()
            }
        }
    }
}