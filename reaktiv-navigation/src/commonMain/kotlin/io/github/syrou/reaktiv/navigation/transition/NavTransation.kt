package io.github.syrou.reaktiv.navigation.transition

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
sealed class NavTransition(@Transient open val durationMillis: Int = DEFAULT_ANIMATION_DURATION) {
    @Serializable
    data object None : NavTransition(0)
    @Deprecated("This is deprecated and will be removed in future versions, use None")
    @Serializable
    data object Hold : NavTransition(0)
    @Serializable
    data object Fade : NavTransition()
    @Serializable
    data object FadeOut : NavTransition()
    @Serializable
    data object SlideInRight : NavTransition()
    @Serializable
    data object SlideOutRight : NavTransition()
    @Serializable
    data object SlideInLeft : NavTransition()
    @Serializable
    data object SlideOutLeft : NavTransition()
    @Serializable
    data object SlideUpBottom : NavTransition()
    @Serializable
    data object SlideOutBottom : NavTransition()

    @Serializable
    class Scale(override val durationMillis: Int = DEFAULT_ANIMATION_DURATION) : NavTransition(durationMillis)
    @Serializable
    class ScaleOut(override val durationMillis: Int = DEFAULT_ANIMATION_DURATION) : NavTransition(durationMillis)
    @Serializable
    data object IOSSlideIn : NavTransition()
    @Serializable
    data object IOSSlideOut : NavTransition()
    @Serializable
    data object MaterialSlideIn : NavTransition()
    @Serializable
    data object MaterialSlideOut : NavTransition()
    @Serializable
    data object StackPush : NavTransition()
    @Serializable
    data object StackPop : NavTransition()
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
data class ResolvedNavTransition(
    val durationMillis: Int,
    val alpha: (Float) -> Float = { 1f },
    val scaleX: (Float) -> Float = { 1f },
    val scaleY: (Float) -> Float = { 1f },
    val translationX: (Float) -> Float = { 0f },
    val translationY: (Float) -> Float = { 0f },
    val rotationZ: (Float) -> Float = { 0f }
)
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

        is NavTransition.Hold -> ResolvedNavTransition(
            durationMillis = 0,
            alpha = { if (it == 0f) 0.99f else 1f }
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