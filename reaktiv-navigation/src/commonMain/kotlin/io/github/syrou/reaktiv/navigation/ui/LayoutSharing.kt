package io.github.syrou.reaktiv.navigation.ui

internal data class LayoutSharingDecision(
    val sharedRoutes: Set<String>,
    val liftExiting: Boolean
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

    return LayoutSharingDecision(sharedRoutes = sharedRoutes, liftExiting = liftExiting)
}
