package io.github.syrou.reaktiv.navigation.transition

/**
 * How a surface arrives and leaves.
 *
 * Implemented by both a [io.github.syrou.reaktiv.navigation.definition.Navigatable] and a
 * [io.github.syrou.reaktiv.navigation.definition.Graph], so transition resolution can read the same
 * four values from whichever node is the surface actually moving. A screen always has an arrival,
 * so it narrows [enterTransition] and [exitTransition] to non-null. A graph may have no opinion at
 * all, which is what a purely structural graph is, so here they stay nullable.
 */
public interface TransitionSpec {
    public val enterTransition: NavTransition?
    public val exitTransition: NavTransition?
    public val popEnterTransition: NavTransition? get() = null
    public val popExitTransition: NavTransition? get() = null
}

/** True when this node describes a surface of its own rather than deferring to what it contains. */
public val TransitionSpec.presentsItself: Boolean
    get() = enterTransition != null
