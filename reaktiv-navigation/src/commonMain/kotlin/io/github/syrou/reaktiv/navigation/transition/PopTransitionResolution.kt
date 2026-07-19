package io.github.syrou.reaktiv.navigation.transition

import io.github.syrou.reaktiv.navigation.definition.Navigatable

internal data class PopTransitionSpec(
    val transition: NavTransition,
    val reversedProgress: Boolean
)

private fun NavTransition.specOrNull(reversedProgress: Boolean): PopTransitionSpec? =
    takeUnless { it == NavTransition.None }?.let { PopTransitionSpec(it, reversedProgress) }

internal fun pushExitSpec(arriving: Navigatable, covered: Navigatable): PopTransitionSpec? {
    val explicit = arriving.popExitTransition
    if (explicit != null) {
        return explicit.specOrNull(reversedProgress = false)
    }
    return covered.exitTransition.specOrNull(reversedProgress = false)
}

internal fun popEnterSpec(
    popped: Navigatable,
    revealed: Navigatable,
    includeEnterFallback: Boolean = true
): PopTransitionSpec? {
    val explicit = popped.popEnterTransition
    if (explicit != null) {
        return explicit.specOrNull(reversedProgress = false)
    }
    return revealed.exitTransition.specOrNull(reversedProgress = true)
        ?: if (includeEnterFallback) revealed.enterTransition.specOrNull(reversedProgress = false) else null
}

internal fun popExitSpec(popped: Navigatable): PopTransitionSpec? =
    popped.enterTransition.specOrNull(reversedProgress = true)
        ?: popped.exitTransition.specOrNull(reversedProgress = false)
