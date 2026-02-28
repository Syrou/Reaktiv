package io.github.syrou.reaktiv.navigation.definition

import io.github.syrou.reaktiv.navigation.layer.RenderLayer

/**
 * A full-screen SYSTEM overlay shown while a guard or entry definition is evaluating.
 *
 * Automatically shown by `evaluateWithThreshold` when evaluation exceeds the configured
 * loading threshold, and removed atomically once navigation commits.
 *
 * Sits at the bottom of the SYSTEM layer (`elevation = 0f`) so that user-triggered
 * SYSTEM modals (e.g. system alerts) always render above it.
 *
 * Stacking order within [RenderLayer.SYSTEM]:
 * - `LoadingModal` → `zIndex(9001f + 0f)` = 9001f
 * - Normal SYSTEM `Modal` → `zIndex(9001f + 1000f)` = 10001f
 *
 * Usage:
 * ```kotlin
 * object AuthLoadingScreen : LoadingModal {
 *     override val route = "auth-loading"
 *     override val enterTransition = NavTransition.Fade
 *     override val exitTransition = NavTransition.FadeOut
 *
 *     @Composable
 *     override fun Content(params: Params) { /* loading UI */ }
 * }
 * ```
 */
interface LoadingModal : Modal {
    override val renderLayer get() = RenderLayer.SYSTEM
    override val dismissable get() = false
    override val tapOutsideToDismiss get() = false
    override val shouldDimBackground get() = false
    override val elevation get() = 0f
}
