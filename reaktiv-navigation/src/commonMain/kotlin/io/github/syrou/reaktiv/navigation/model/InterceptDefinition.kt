package io.github.syrou.reaktiv.navigation.model

import io.github.syrou.reaktiv.navigation.definition.Screen
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Defines an intercept guard that applies to all navigation into a group of graphs,
 * including deep links.
 *
 * Created by the [NavigationGraphBuilder.intercept] DSL method.
 *
 * @param guard Guard evaluated before navigation is committed; returns a [GuardResult] decision
 * @param loadingScreen Optional screen shown while the guard suspends beyond [loadingThreshold]
 * @param loadingThreshold How long to wait before showing the loading screen (default 200ms)
 */
data class InterceptDefinition(
    val guard: NavigationGuard,
    val loadingScreen: Screen? = null,
    val loadingThreshold: Duration = 200.milliseconds
)
