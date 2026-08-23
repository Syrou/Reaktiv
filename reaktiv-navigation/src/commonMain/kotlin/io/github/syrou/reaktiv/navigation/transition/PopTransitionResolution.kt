package io.github.syrou.reaktiv.navigation.transition

internal data class PopTransitionSpec(
    val transition: NavTransition,
    val reversedProgress: Boolean
)

private fun NavTransition?.specOrNull(reversedProgress: Boolean): PopTransitionSpec? =
    this?.takeUnless { it == NavTransition.None }?.let { PopTransitionSpec(it, reversedProgress) }

internal fun pushExitSpec(arriving: TransitionSpec, covered: TransitionSpec): PopTransitionSpec? {
    val explicit = arriving.popExitTransition
    if (explicit != null) {
        return explicit.specOrNull(reversedProgress = false)
    }
    return covered.exitTransition.specOrNull(reversedProgress = false)
}

internal fun popEnterSpec(
    popped: TransitionSpec,
    revealed: TransitionSpec,
    includeEnterFallback: Boolean = true
): PopTransitionSpec? {
    val explicit = popped.popEnterTransition
    if (explicit != null) {
        return explicit.specOrNull(reversedProgress = false)
    }
    return revealed.exitTransition.specOrNull(reversedProgress = true)
        ?: if (includeEnterFallback) revealed.enterTransition.specOrNull(reversedProgress = false) else null
}

internal fun popExitSpec(popped: TransitionSpec): PopTransitionSpec? =
    popped.enterTransition.specOrNull(reversedProgress = true)
        ?: popped.exitTransition.specOrNull(reversedProgress = false)
