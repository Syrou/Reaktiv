package io.github.syrou.reaktiv.navigation.definition

import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import kotlin.time.Duration

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
 * - `LoadingModal` -> `zIndex(9001f + 0f)` = 9001f
 * - Normal SYSTEM `Modal` -> `zIndex(9001f + 1000f)` = 10001f
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
public interface LoadingModal : Modal {
    override val renderLayer: RenderLayer get() = RenderLayer.SYSTEM
    override val shouldDimBackground: Boolean get() = false
    override val elevation: Float get() = 0f
    override val swipeToDismiss: Boolean get() = false
    override val backGestureEnabled: Boolean get() = false

    /**
     * How long the loading modal stays up once a slow guard or start has shown it, so a check that answers just after
     * its loading threshold doesn't flash it on and off. Zero, the default, hides it as soon as the check is done.
     * Startup and a navigation another one cancelled are never held.
     */
    public val minimumDisplay: Duration get() = Duration.ZERO
}
