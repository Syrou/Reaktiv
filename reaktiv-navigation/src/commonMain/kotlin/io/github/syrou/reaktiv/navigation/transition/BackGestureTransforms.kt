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

/**
 * The axis a surface moves along, read from whichever node presents it.
 *
 * Defined on the spec rather than on a screen so the same question can be asked of a graph. Backing
 * out of the first screen of a presented graph leaves the graph, so the axis that matters is the
 * graph's, not the screen's.
 */
internal fun TransitionSpec.gestureAxis(): GestureAxis {
    val enterAxis = enterTransition?.presentationAxis() ?: GestureAxis.Neutral
    if (enterAxis != GestureAxis.Neutral) return enterAxis
    return exitTransition?.presentationAxis() ?: GestureAxis.Neutral
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
    top: TransitionSpec,
    revealed: TransitionSpec,
    screenWidth: Float,
    screenHeight: Float
): BackGesturePlan {
    val topTransform = popExitSpec(top)?.toScrub(screenWidth, screenHeight)
        ?: ScrubTransform(
            NavTransition.IOSSlideIn.resolve(screenWidth, screenHeight, isForward = true),
            reversedProgress = true
        )

    val revealedTransform = popEnterSpec(top, revealed)?.toScrub(screenWidth, screenHeight)
        ?: ScrubTransform(
            NavTransition.IOSSlideOut.resolve(screenWidth, screenHeight, isForward = true),
            reversedProgress = true
        )

    return BackGesturePlan(top = topTransform, revealed = revealedTransform)
}

private const val VERTICAL_REVEAL_SCALE_DELTA = 0.06f

/**
 * Scrub transforms for dragging a surface away.
 *
 * [top] is whatever is being dismissed, which for a graph that presents itself is the graph rather
 * than the screen showing inside it. Both are a [TransitionSpec], so this reads the same values
 * the timed animation does and releasing a drag continues the motion it started.
 */
internal fun computeDismissGesturePlan(
    top: TransitionSpec,
    revealed: TransitionSpec?,
    screenWidth: Float,
    screenHeight: Float
): BackGesturePlan {
    val topTransform = popExitSpec(top)?.toScrub(screenWidth, screenHeight)
        ?: ScrubTransform(
            NavTransition.SlideOutBottom.resolve(screenWidth, screenHeight, isForward = false),
            reversedProgress = false
        )

    val revealedTransform = revealed
        ?.let { popEnterSpec(top, it, includeEnterFallback = false)?.toScrub(screenWidth, screenHeight) }
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
