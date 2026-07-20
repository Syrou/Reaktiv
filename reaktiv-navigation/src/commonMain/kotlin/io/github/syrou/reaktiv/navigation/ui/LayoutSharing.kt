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
    if (liftExiting) {
        sharedRoutes = sharedRoutes.intersect(previousLayoutRoutes.orEmpty().toSet())
    }
    if (revealedLayoutRoutes != null) {
        sharedRoutes = sharedRoutes.intersect(revealedLayoutRoutes.toSet())
    }
    if (restingBackLayoutRoutes != null && previousLayoutRoutes == null && revealedLayoutRoutes == null) {
        sharedRoutes = sharedRoutes.intersect(restingBackLayoutRoutes.toSet())
    }

    return LayoutSharingDecision(sharedRoutes = sharedRoutes, liftExiting = liftExiting)
}
