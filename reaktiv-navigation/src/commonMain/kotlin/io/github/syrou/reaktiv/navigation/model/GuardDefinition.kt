package io.github.syrou.reaktiv.navigation.model

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.definition.NavigationNode

/**
 * A suspend function evaluated before navigation is committed.
 *
 * Returns a [GuardResult] to allow, reject, or redirect the navigation.
 *
 * Example:
 * ```kotlin
 * val requireAuth: NavigationGuard = { store ->
 *     if (store.selectState<AuthState>().value.isAuthenticated) GuardResult.Allow
 *     else GuardResult.RedirectTo(LoginScreen)
 * }
 * ```
 */
typealias NavigationGuard = suspend (StoreAccessor) -> GuardResult

/**
 * A suspend function that selects which destination to navigate to when entering a graph.
 *
 * Returns a [NavigationNode] — either a typed [io.github.syrou.reaktiv.navigation.definition.Navigatable]
 * (screen or modal object) for full-path resolution, or a [io.github.syrou.reaktiv.navigation.definition.NavigationPath]
 * for a plain route string. Used internally by [EntryDefinition.route].
 */
typealias RouteSelector = suspend (StoreAccessor) -> NavigationNode
