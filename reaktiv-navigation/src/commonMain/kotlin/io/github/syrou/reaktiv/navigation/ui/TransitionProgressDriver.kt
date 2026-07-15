package io.github.syrou.reaktiv.navigation.ui

import io.github.syrou.reaktiv.navigation.transition.ResolvedNavTransition

internal sealed interface TransitionProgressDriver {

    data object Timed : TransitionProgressDriver

    class External(
        val progress: () -> Float,
        val resolved: ResolvedNavTransition,
        val reversedProgress: Boolean = false
    ) : TransitionProgressDriver {

        fun transformProgress(): Float {
            val raw = progress().coerceIn(0f, 1f)
            return if (reversedProgress) 1f - raw else raw
        }
    }
}
