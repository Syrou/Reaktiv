package io.github.syrou.reaktiv.navigation.transition

import io.github.syrou.reaktiv.navigation.definition.Navigatable

internal data class PopTransitionSpec(
    val transition: NavTransition,
    val reversedProgress: Boolean
)

private fun NavTransition.specOrNull(reversedProgress: Boolean): PopTransitionSpec? =
    takeUnless { it == NavTransition.None }?.let { PopTransitionSpec(it, reversedProgress) }

internal fun popExitSpec(popped: Navigatable): PopTransitionSpec? {
    val explicit = popped.popExitTransition
    if (explicit != null) {
        return explicit.specOrNull(reversedProgress = false)
    }
    return popped.enterTransition.specOrNull(reversedProgress = true)
        ?: popped.exitTransition.specOrNull(reversedProgress = false)
}

internal fun popEnterSpec(revealed: Navigatable, includeEnterFallback: Boolean = true): PopTransitionSpec? {
    val explicit = revealed.popEnterTransition
    if (explicit != null) {
        return explicit.specOrNull(reversedProgress = false)
    }
    return revealed.exitTransition.specOrNull(reversedProgress = true)
        ?: if (includeEnterFallback) revealed.enterTransition.specOrNull(reversedProgress = false) else null
}
