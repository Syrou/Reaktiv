package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.model.NavigationEntry

/**
 * Convenience extension to get NavigationModule from Store.
 *
 * Usage:
 * ```kotlin
 * val navModule = store.getNavigationModule()
 * val graphDefinitions = navModule.getGraphDefinitions()
 * ```
 *
 * @return NavigationModule instance
 * @throws IllegalStateException if NavigationModule is not registered in the store
 */
fun Store.getNavigationModule(): NavigationModule {
    return getModule<NavigationModule>()
        ?: error("NavigationModule not registered in store")
}

/**
 * Convenience extension to get NavigationModule from StoreAccessor.
 * Casts to Store and delegates to Store.getNavigationModule().
 *
 * Usage:
 * ```kotlin
 * val navModule = storeAccessor.getNavigationModule()
 * val graphDefinitions = navModule.getGraphDefinitions()
 * ```
 *
 * @return NavigationModule instance
 * @throws IllegalStateException if NavigationModule is not registered in the store
 */
fun StoreAccessor.getNavigationModule(): NavigationModule {
    return (this as Store).getNavigationModule()
}

/**
 * Get the full path for a Navigatable.
 *
 * The full path includes all graph prefixes, making it unambiguous for navigation.
 * For example, a screen with route "tools" nested in graphs home -> workspace -> projects
 * would have full path "home/workspace/projects/tools".
 *
 * Usage:
 * ```kotlin
 * val path = store.getFullPath(ToolsScreen)
 * // Returns "home/workspace/projects/tools"
 *
 * // Use in navigation:
 * store.navigation {
 *     navigateTo(store.getFullPath(ToolsScreen)!!)
 * }
 * ```
 *
 * @param navigatable The screen or modal to get the path for
 * @return The full path, or null if the navigatable is not registered
 */
fun Store.getFullPath(navigatable: Navigatable): String? {
    return getNavigationModule().getFullPath(navigatable)
}

/**
 * Get the full path for a Navigatable from StoreAccessor.
 * @see Store.getFullPath
 */
fun StoreAccessor.getFullPath(navigatable: Navigatable): String? {
    return (this as Store).getFullPath(navigatable)
}

/**
 * Resolve the [Navigatable] for a [NavigationEntry].
 *
 * This replaces the old `entry.screen` / `entry.navigatable` direct property that existed
 * before the path-based NavigationEntry migration. The navigatable is looked up by the
 * entry's full path in the navigation module's registered routes.
 *
 * Usage:
 * ```kotlin
 * val navigationState by composeState<NavigationState>()
 * val navigatable = store.resolveNavigatable(navigationState.currentEntry)
 * val title = navigatable?.titleResource?.invoke() ?: "Home"
 * ```
 *
 * @param entry The navigation entry to resolve
 * @return The [Navigatable], or null if not found
 */
fun Store.resolveNavigatable(entry: NavigationEntry): Navigatable? {
    return getNavigationModule().resolveNavigatable(entry)
}

/**
 * Resolve the [Navigatable] for a [NavigationEntry] from StoreAccessor.
 * @see Store.resolveNavigatable
 */
fun StoreAccessor.resolveNavigatable(entry: NavigationEntry): Navigatable? {
    return (this as Store).resolveNavigatable(entry)
}

/**
 * Get the full path for a Navigatable, throwing if not found.
 *
 * Usage:
 * ```kotlin
 * store.navigation {
 *     navigateTo(store.requireFullPath(ToolsScreen))
 * }
 * ```
 *
 * @param navigatable The screen or modal to get the path for
 * @return The full path
 * @throws IllegalStateException if the navigatable is not registered
 */
fun Store.requireFullPath(navigatable: Navigatable): String {
    return getFullPath(navigatable)
        ?: error("Navigatable '${navigatable.route}' is not registered in any navigation graph")
}

/**
 * Get the full path for a Navigatable from StoreAccessor, throwing if not found.
 * @see Store.requireFullPath
 */
fun StoreAccessor.requireFullPath(navigatable: Navigatable): String {
    return (this as Store).requireFullPath(navigatable)
}
