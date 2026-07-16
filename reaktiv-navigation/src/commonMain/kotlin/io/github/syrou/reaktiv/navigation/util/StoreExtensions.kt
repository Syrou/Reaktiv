package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.definition.Navigatable

/**
 * Convenience extension to get NavigationModule from a StoreAccessor (or Store).
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
public fun StoreAccessor.getNavigationModule(): NavigationModule {
    return getModule(NavigationModule::class)
        ?: error("NavigationModule not registered in store")
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
public fun StoreAccessor.getFullPath(navigatable: Navigatable): String? {
    return getNavigationModule().getFullPath(navigatable)
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
public fun StoreAccessor.requireFullPath(navigatable: Navigatable): String {
    return getFullPath(navigatable)
        ?: error("Navigatable '${navigatable.route}' is not registered in any navigation graph")
}
