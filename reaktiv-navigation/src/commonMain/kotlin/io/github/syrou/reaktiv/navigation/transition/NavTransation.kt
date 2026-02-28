package io.github.syrou.reaktiv.navigation.transition

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Describes the animation style used when a screen enters or exits the composition.
 *
 * Assign these to a [Screen] or [Modal]'s `enterTransition` / `exitTransition` properties.
 * Call [resolve] to obtain a [ResolvedNavTransition] with concrete animation lambdas.
 *
 * ```kotlin
 * object HomeScreen : Screen {
 *     override val route = "home"
 *     override val enterTransition = NavTransition.SlideInRight
 *     override val exitTransition = NavTransition.SlideOutLeft
 * }
 * ```
 */
@Serializable
sealed class NavTransition(@Transient open val durationMillis: Int = DEFAULT_ANIMATION_DURATION) {

    /** No animation; the screen appears or disappears instantly. */
    @Serializable
    data object None : NavTransition(0)

    /** Cross-fade in — opacity goes from 0 to 1. */
    @Serializable
    data object Fade : NavTransition()

    /** Cross-fade out — opacity goes from 1 to 0. */
    @Serializable
    data object FadeOut : NavTransition()

    /** Screen slides in from the right edge. */
    @Serializable
    data object SlideInRight : NavTransition()

    /** Screen slides out toward the right edge. */
    @Serializable
    data object SlideOutRight : NavTransition()

    /** Screen slides in from the left edge. */
    @Serializable
    data object SlideInLeft : NavTransition()

    /** Screen slides out toward the left edge. */
    @Serializable
    data object SlideOutLeft : NavTransition()

    /** Screen slides up from the bottom edge. */
    @Serializable
    data object SlideUpBottom : NavTransition()

    /** Screen slides down and exits through the bottom edge. */
    @Serializable
    data object SlideOutBottom : NavTransition()

    /** Screen scales up (80 % → 100 %) while fading in. */
    @Serializable
    class Scale(override val durationMillis: Int = DEFAULT_ANIMATION_DURATION) : NavTransition(durationMillis)

    /** Screen scales down (100 % → 80 %) while fading out. */
    @Serializable
    class ScaleOut(override val durationMillis: Int = DEFAULT_ANIMATION_DURATION) : NavTransition(durationMillis)

    /** iOS-style push: new screen slides in from the right edge. */
    @Serializable
    data object IOSSlideIn : NavTransition()

    /** iOS-style pop: outgoing screen slides left with a parallax scale-down effect. */
    @Serializable
    data object IOSSlideOut : NavTransition()

    /** Material Design 3 forward enter: slide in from right with subtle scale and fade. */
    @Serializable
    data object MaterialSlideIn : NavTransition()

    /** Material Design 3 forward exit: slide out to the left with a trailing fade. */
    @Serializable
    data object MaterialSlideOut : NavTransition()

    /** Card-stack push: new screen rises from the bottom with a scale-up and fade. */
    @Serializable
    data object StackPush : NavTransition()

    /** Card-stack pop: outgoing screen falls down and fades out. */
    @Serializable
    data object StackPop : NavTransition()

    /**
     * Fully custom transition using per-frame transform lambdas.
     *
     * Each lambda receives the animation progress value in `[0, 1]` and returns
     * the corresponding transform value. Functions that are not overridden use
     * identity defaults (no-op).
     *
     * @param durationMillis Total duration of the animation in milliseconds.
     * @param alpha Maps progress → opacity.
     * @param scaleX Maps progress → horizontal scale factor.
     * @param scaleY Maps progress → vertical scale factor.
     * @param translationX Maps progress → horizontal translation in pixels.
     * @param translationY Maps progress → vertical translation in pixels.
     * @param rotationZ Maps progress → rotation around the Z-axis in degrees.
     */
    @Serializable
    class Custom(
        override val durationMillis: Int = DEFAULT_ANIMATION_DURATION,
        @Transient val alpha: (Float) -> Float = { 1f },
        @Transient val scaleX: (Float) -> Float = { 1f },
        @Transient val scaleY: (Float) -> Float = { 1f },
        @Transient val translationX: (Float) -> Float = { 0f },
        @Transient val translationY: (Float) -> Float = { 0f },
        @Transient val rotationZ: (Float) -> Float = { 0f }
    ) : NavTransition(durationMillis)

    companion object {
        const val DEFAULT_ANIMATION_DURATION = 200
    }
}
/**
 * The concrete, renderer-ready form of a [NavTransition].
 *
 * Produced by [NavTransition.resolve]; consumed by the animation layer in [NavigationRender].
 * All transform lambdas accept a normalised progress value in `[0, 1]`.
 *
 * @property durationMillis Total animation duration in milliseconds.
 * @property alpha Progress → opacity mapping.
 * @property scaleX Progress → horizontal scale factor mapping.
 * @property scaleY Progress → vertical scale factor mapping.
 * @property translationX Progress → horizontal pixel offset mapping.
 * @property translationY Progress → vertical pixel offset mapping.
 * @property rotationZ Progress → Z-axis rotation in degrees mapping.
 */
data class ResolvedNavTransition(
    val durationMillis: Int,
    val alpha: (Float) -> Float = { 1f },
    val scaleX: (Float) -> Float = { 1f },
    val scaleY: (Float) -> Float = { 1f },
    val translationX: (Float) -> Float = { 0f },
    val translationY: (Float) -> Float = { 0f },
    val rotationZ: (Float) -> Float = { 0f }
)

/**
 * Resolves this [NavTransition] into a [ResolvedNavTransition] with concrete per-frame lambdas.
 *
 * @param screenWidth Width of the screen in pixels; used for horizontal slide transitions.
 * @param screenHeight Height of the screen in pixels; used for vertical slide transitions.
 * @param isForward `true` when navigating forward (push); `false` when navigating backward (pop).
 * @return A [ResolvedNavTransition] ready for consumption by the animation system.
 */
fun NavTransition.resolve(
    screenWidth: Float,
    screenHeight: Float,
    isForward: Boolean = true
): ResolvedNavTransition {
    return when (this) {
        is NavTransition.None -> ResolvedNavTransition(
            durationMillis = 0,
            alpha = { 1f }
        )

        is NavTransition.Fade -> ResolvedNavTransition(
            durationMillis = durationMillis,
            alpha = { it }
        )

        is NavTransition.FadeOut -> ResolvedNavTransition(
            durationMillis = durationMillis,
            alpha = { 1f - it }
        )

        is NavTransition.SlideInRight -> ResolvedNavTransition(
            durationMillis = durationMillis,
            alpha = { 1f },
            translationX = { progress ->
                if (isForward) (1f - progress) * screenWidth
                else -(1f - progress) * screenWidth
            }
        )

        is NavTransition.SlideOutRight -> ResolvedNavTransition(
            durationMillis = durationMillis,
            alpha = { 1f },
            translationX = { progress ->
                if (isForward) progress * screenWidth
                else -progress * screenWidth
            }
        )

        is NavTransition.SlideInLeft -> ResolvedNavTransition(
            durationMillis = durationMillis,
            alpha = { 1f },
            translationX = { progress ->
                if (isForward) -(1f - progress) * screenWidth
                else (1f - progress) * screenWidth
            }
        )

        is NavTransition.SlideOutLeft -> ResolvedNavTransition(
            durationMillis = durationMillis,
            alpha = { 1f },
            translationX = { progress ->
                if (isForward) -progress * screenWidth
                else progress * screenWidth
            }
        )

        is NavTransition.SlideUpBottom -> ResolvedNavTransition(
            durationMillis = durationMillis,
            alpha = { 1f },
            translationY = { progress -> (1f - progress) * screenHeight }
        )

        is NavTransition.SlideOutBottom -> ResolvedNavTransition(
            durationMillis = durationMillis,
            alpha = { 1f },
            translationY = { progress -> progress * screenHeight }
        )

        is NavTransition.Scale -> ResolvedNavTransition(
            durationMillis = durationMillis,
            alpha = { it },
            scaleX = { 0.8f + it * 0.2f },
            scaleY = { 0.8f + it * 0.2f }
        )

        is NavTransition.ScaleOut -> ResolvedNavTransition(
            durationMillis = durationMillis,
            alpha = { 1f - it },
            scaleX = { 1f - it * 0.2f },
            scaleY = { 1f - it * 0.2f }
        )

        is NavTransition.IOSSlideIn -> ResolvedNavTransition(
            durationMillis = durationMillis,
            alpha = { 1f },
            translationX = { progress -> (1f - progress) * screenWidth }
        )

        is NavTransition.IOSSlideOut -> ResolvedNavTransition(
            durationMillis = durationMillis,
            alpha = { 1f },
            translationX = { progress -> -progress * screenWidth * 0.3f }, // Parallax effect
            scaleX = { progress -> 1f - progress * 0.05f },
            scaleY = { progress -> 1f - progress * 0.05f }
        )

        is NavTransition.MaterialSlideIn -> ResolvedNavTransition(
            durationMillis = durationMillis,
            alpha = { if (it < 0.3f) it / 0.3f else 1f }, // Fade in first 30%
            translationX = { progress -> (1f - progress) * screenWidth },
            scaleX = { 0.95f + it * 0.05f },
            scaleY = { 0.95f + it * 0.05f }
        )

        is NavTransition.MaterialSlideOut -> ResolvedNavTransition(
            durationMillis = durationMillis,
            alpha = { if (it > 0.7f) 1f - ((it - 0.7f) / 0.3f) else 1f }, // Fade out last 30%
            translationX = { progress -> -progress * screenWidth }
        )

        is NavTransition.StackPush -> ResolvedNavTransition(
            durationMillis = durationMillis,
            alpha = { it },
            translationY = { progress -> (1f - progress) * screenHeight },
            scaleX = { 0.9f + it * 0.1f },
            scaleY = { 0.9f + it * 0.1f }
        )

        is NavTransition.StackPop -> ResolvedNavTransition(
            durationMillis = durationMillis,
            alpha = { 1f - it },
            translationY = { progress -> progress * screenHeight }
        )

        is NavTransition.Custom -> ResolvedNavTransition(
            durationMillis = durationMillis,
            alpha = alpha,
            scaleX = scaleX,
            scaleY = scaleY,
            translationX = translationX,
            translationY = translationY,
            rotationZ = rotationZ
        )
    }
}