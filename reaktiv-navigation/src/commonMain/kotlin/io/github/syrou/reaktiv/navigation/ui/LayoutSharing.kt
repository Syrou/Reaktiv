package io.github.syrou.reaktiv.navigation.ui

internal data class LayoutSharingDecision(
    val sharedRoutes: Set<String>,
    val liftExiting: Boolean,
    val exitingUniqueRoutes: Set<String>
)

internal fun decideLayoutSharing(
    currentLayoutRoutes: List<String>,
    previousLayoutRoutes: List<String>?,
    revealedLayoutRoutes: List<String>?,
    restingBackLayoutRoutes: List<String>?,
    shouldAnimateExit: Boolean
): LayoutSharingDecision {
    val layoutChanged = previousLayoutRoutes != null && previousLayoutRoutes != currentLayoutRoutes
    val liftExiting = layoutChanged && shouldAnimateExit

    var sharedRoutes = currentLayoutRoutes.toSet()
    // A layout is only shared if the screen we are leaving actually had it. Anything else belongs
    // to the arriving screen and has to travel with it, inside the transition and inside the
    // gesture handlers wrapped around that slot. Treating a brand new layout as shared pins it
    // outside the animation, so a screen that slides up from the bottom appears to slide in
    // underneath chrome that was already there, and a drag handler ends up on the screen alone
    // rather than on the whole graph.
    if (previousLayoutRoutes != null) {
        sharedRoutes = sharedRoutes.intersect(previousLayoutRoutes.toSet())
    }
    if (revealedLayoutRoutes != null) {
        sharedRoutes = sharedRoutes.intersect(revealedLayoutRoutes.toSet())
    }
    if (restingBackLayoutRoutes != null && previousLayoutRoutes == null && revealedLayoutRoutes == null) {
        sharedRoutes = sharedRoutes.intersect(restingBackLayoutRoutes.toSet())
    }

    // A screen on its way out is still on screen, so it keeps whatever chrome is not shared with
    // the screen replacing it. This is independent of whether the exit animates: lifting only
    // decides where the exiting surface is ordered, never whether it is whole. Dropping these when
    // the exit was not animated left the outgoing screen rendering bare for the length of the
    // transition, so its height changed and its content jumped to fill the space its header left.
    val exitingUniqueRoutes = previousLayoutRoutes.orEmpty().toSet() - sharedRoutes

    return LayoutSharingDecision(
        sharedRoutes = sharedRoutes,
        liftExiting = liftExiting,
        exitingUniqueRoutes = exitingUniqueRoutes
    )
}
