package io.github.syrou.reaktiv.navigation.definition

public interface NavigationNode{
    public val route: String
}

/**
 * A lightweight [NavigationNode] that wraps a plain route string.
 *
 * Use this when you need to return a route string from an [entry] selector
 * but don't have a typed screen or graph object available — for example when
 * navigating to a graph route by its ID.
 *
 * Example:
 * ```kotlin
 * rootGraph {
 *     entry(
 *         route = { store ->
 *             val auth = store.selectState<AuthState>().value
 *             if (auth.isAuthenticated) NavigationPath("home") else LoginScreen
 *         },
 *         loadingScreen = AuthLoadingScreen
 *     )
 * }
 * ```
 */
public data class NavigationPath(override val route: String) : NavigationNode
