package io.github.syrou.reaktiv.navigation.history

import io.github.syrou.reaktiv.navigation.TraverseDirection
import io.github.syrou.reaktiv.navigation.TraversePresentation
import io.github.syrou.reaktiv.navigation.param.Params

internal sealed class ExternalLocation {
    data class Url(val path: String, val href: String? = null, val params: Params = Params.empty()) : ExternalLocation()

    data class Snapshot(
        val snapshot: LocationSnapshot,
        val url: Url,
        val direction: TraverseDirection,
        val presentation: TraversePresentation,
        val isCurrent: () -> Boolean = { true }
    ) : ExternalLocation()
}

internal sealed class ExternalOutcome {
    data object Landed : ExternalOutcome()
    data class LandedOnNotFound(val reason: String) : ExternalOutcome()
    data class Redirected(val to: String) : ExternalOutcome()
    data object Rejected : ExternalOutcome()
    data class Unresolvable(val reason: String) : ExternalOutcome()
    data object Stale : ExternalOutcome()
    data object Dropped : ExternalOutcome()
}
