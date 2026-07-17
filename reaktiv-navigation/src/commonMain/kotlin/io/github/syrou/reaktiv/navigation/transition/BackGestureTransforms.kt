package io.github.syrou.reaktiv.navigation.transition

import io.github.syrou.reaktiv.navigation.definition.Navigatable

public enum class GestureAxis {
    Horizontal,
    Vertical,
    Neutral
}

public fun NavTransition.presentationAxis(): GestureAxis = when (this) {
    is NavTransition.SlideInRight,
    is NavTransition.SlideOutRight,
    is NavTransition.SlideInLeft,
    is NavTransition.SlideOutLeft,
    is NavTransition.IOSSlideIn,
    is NavTransition.IOSSlideOut,
    is NavTransition.MaterialSlideIn,
    is NavTransition.MaterialSlideOut -> GestureAxis.Horizontal

    is NavTransition.SlideUpBottom,
    is NavTransition.SlideOutBottom,
    is NavTransition.StackPush,
    is NavTransition.StackPop -> GestureAxis.Vertical

    else -> GestureAxis.Neutral
}

internal data class ScrubTransform(
    val resolved: ResolvedNavTransition,
    val reversedProgress: Boolean
)

internal data class BackGesturePlan(
    val top: ScrubTransform,
    val revealed: ScrubTransform
)

private fun PopTransitionSpec.toScrub(screenWidth: Float, screenHeight: Float): ScrubTransform =
    ScrubTransform(transition.resolve(screenWidth, screenHeight, isForward = reversedProgress), reversedProgress)

internal fun computeBackGesturePlan(
    top: Navigatable,
    revealed: Navigatable,
    screenWidth: Float,
    screenHeight: Float
): BackGesturePlan {
    val topTransform = popExitSpec(top)?.toScrub(screenWidth, screenHeight)
        ?: ScrubTransform(
            NavTransition.IOSSlideIn.resolve(screenWidth, screenHeight, isForward = true),
            reversedProgress = true
        )

    val revealedTransform = popEnterSpec(revealed)?.toScrub(screenWidth, screenHeight)
        ?: ScrubTransform(
            NavTransition.IOSSlideOut.resolve(screenWidth, screenHeight, isForward = true),
            reversedProgress = true
        )

    return BackGesturePlan(top = topTransform, revealed = revealedTransform)
}

private const val VERTICAL_REVEAL_SCALE_DELTA = 0.06f

internal fun computeDismissGesturePlan(
    top: Navigatable,
    revealed: Navigatable?,
    screenWidth: Float,
    screenHeight: Float
): BackGesturePlan {
    val topTransform = popExitSpec(top)?.toScrub(screenWidth, screenHeight)
        ?: ScrubTransform(
            NavTransition.SlideOutBottom.resolve(screenWidth, screenHeight, isForward = false),
            reversedProgress = false
        )

    val revealedTransform = revealed
        ?.let { popEnterSpec(it, includeEnterFallback = false)?.toScrub(screenWidth, screenHeight) }
        ?: ScrubTransform(
            ResolvedNavTransition(
                durationMillis = 0,
                scaleX = { 1f - it * VERTICAL_REVEAL_SCALE_DELTA },
                scaleY = { 1f - it * VERTICAL_REVEAL_SCALE_DELTA }
            ),
            reversedProgress = true
        )

    return BackGesturePlan(top = topTransform, revealed = revealedTransform)
}
