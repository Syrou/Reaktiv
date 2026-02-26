package io.github.syrou.reaktiv.navigation.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Defines an intercept guard that applies to all navigation into a group of graphs,
 * including deep links.
 *
 * Created by the [NavigationGraphBuilder.intercept] DSL method.
 *
 * @param guard Guard evaluated before navigation is committed; returns a [GuardResult] decision
 * @param loadingThreshold How long to wait before showing the global loading modal (default 200ms)
 */
data class InterceptDefinition(
    val guard: NavigationGuard,
    val loadingThreshold: Duration = 200.milliseconds
)
