package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.util.PathUtil
import io.github.syrou.reaktiv.navigation.util.UrlUtil
import kotlinx.coroutines.flow.first

/**
 * Handles the logic for navigation actions in the Reaktiv architecture.
 *
 * @property availableScreens A map of all available screens in the application.
 */
internal class NavigationLogic(
    private val availableScreens: Map<String, Screen>,
    val storeAccessor: StoreAccessor
) : ModuleLogic<NavigationAction>() {
    /**
     * Helper method to ensure parent paths exist in a navigation operation.
     */
    private fun ensureParentPathsExist(
        path: String,
        currentBackStack: List<NavigationEntry>,
        insertBeforeEntry: NavigationEntry? = null
    ): List<NavigationEntry> {
        val pathSegments = path.split("/")

        // If not a nested path, return original backstack
        if (pathSegments.size <= 1) {
            return currentBackStack
        }

        var newBackStack = currentBackStack
        var currentPath = ""

        // Add all parent paths
        for (i in 0..<pathSegments.size - 1) {
            if (i > 0) currentPath += "/"
            currentPath += pathSegments[i]

            // Skip if already in backstack
            if (newBackStack.any { it.path == currentPath }) {
                continue
            }
            println("DEBUG - AVAILABLE SCREENS: $availableScreens")
            val parentScreen = availableScreens[currentPath] ?: availableScreens[path]
            ?: error("No screen found for parent path: $currentPath or direct path: ${availableScreens[path]}")

            val parentEntry = NavigationEntry(
                screen = parentScreen,
                params = emptyMap(),
                id = currentPath
            )

            // If we're doing a replace operation, insert at specific position
            newBackStack = if (insertBeforeEntry != null) {
                val insertIndex = newBackStack.indexOf(insertBeforeEntry)
                if (insertIndex >= 0) {
                    newBackStack.subList(0, insertIndex) +
                            parentEntry +
                            newBackStack.subList(insertIndex, newBackStack.size)
                } else {
                    newBackStack + parentEntry
                }
            } else {
                // Otherwise just add to the end
                newBackStack + parentEntry
            }
        }

        return newBackStack
    }

    /**
     * Helper method to create a NavigationEntry for a path.
     */
    private fun createEntryForPath(
        path: String,
        params: Map<String, Any> = emptyMap()
    ): NavigationEntry {
        val screen = availableScreens[path] ?: error("No screen found for route: $path")

        return NavigationEntry(
            screen = screen,
            params = params,
            id = path
        )
    }

    /**
     * Prepares a Navigate action for the reducer.
     */
    private suspend fun prepareNavigateAction(
        route: String,
        params: Map<String, Any>,
        popUpTo: String?,
        inclusive: Boolean,
        replaceWith: String?,
        clearBackStack: Boolean,
        forwardParams: Boolean
    ) {
        val currentState = storeAccessor.selectState<NavigationState>().first()

        var newBackStack = if (clearBackStack) listOf() else currentState.backStack

        // Handle popUpTo
        if (popUpTo != null) {
            val popIndex = newBackStack.indexOfLast { it.screen.route == popUpTo }
            if (popIndex != -1) {
                newBackStack = if (inclusive) {
                    newBackStack.subList(0, popIndex)
                } else {
                    newBackStack.subList(0, popIndex + 1)
                }
            }
        }

        val targetRoute = replaceWith ?: route

        val finalParams: StringAnyMap = if (forwardParams) {
            val previousParams = newBackStack.lastOrNull()?.params ?: emptyMap()
            previousParams.plus(params)
        } else {
            params
        }

        // Create new entry
        val newEntry = createEntryForPath(targetRoute, finalParams)

        // Ensure parent paths exist in backstack
        newBackStack = ensureParentPathsExist(targetRoute, newBackStack)

        // Add the new entry
        newBackStack = newBackStack + newEntry

        // Create enhanced action with prepared backstack
        val enhancedAction = NavigationAction.NavigateState(
            currentEntry = newEntry,
            backStack = newBackStack,
            clearedBackStack = clearBackStack
        )

        storeAccessor.dispatch(enhancedAction)
    }

    /**
     * Prepares a Back action for the reducer.
     */
    internal suspend fun prepareBackAction() {
        val currentState = storeAccessor.selectState<NavigationState>().first()
        if (currentState.backStack.size <= 1) return

        // Find current entry index
        val currentIndex = currentState.backStack.indexOf(currentState.currentEntry)
        if (currentIndex <= 0) return

        // Get previous entry
        val newEntry = currentState.backStack[currentIndex - 1]

        // Create backstack without current entry
        val newBackStack = currentState.backStack.filter { it != currentState.currentEntry }

        // Dispatch prepared state
        storeAccessor.dispatch(
            NavigationAction.NavigateState(
                currentEntry = newEntry,
                backStack = newBackStack,
                clearedBackStack = false
            )
        )
    }

    /**
     * Prepares a PopUpTo action for the reducer.
     */
    suspend fun preparePopUpToAction(
        route: String,
        inclusive: Boolean,
        replaceWith: String?,
        replaceParams: Map<String, Any>
    ) {
        val currentState = storeAccessor.selectState<NavigationState>().first()

        val targetIndex = currentState.backStack.indexOfLast { it.screen.route == route }
        if (targetIndex == -1) return

        var newBackStack = if (inclusive) {
            currentState.backStack.subList(0, targetIndex)
        } else {
            currentState.backStack.subList(0, targetIndex + 1)
        }

        var newEntry = newBackStack.lastOrNull() ?: currentState.currentEntry

        if (replaceWith != null) {
            // Create new entry for replacement
            newEntry = createEntryForPath(replaceWith, replaceParams)

            // Ensure parent paths exist
            newBackStack = ensureParentPathsExist(replaceWith, newBackStack)

            // Add replacement entry
            newBackStack = newBackStack + newEntry
        }

        // Dispatch prepared state
        storeAccessor.dispatch(
            NavigationAction.NavigateState(
                currentEntry = newEntry,
                backStack = newBackStack,
                clearedBackStack = false
            )
        )
    }

    /**
     * Prepares a ClearBackStack action for the reducer.
     */
    private suspend fun prepareClearBackStackAction(
        root: String?,
        params: Map<String, Any>
    ) {
        val currentState = storeAccessor.selectState<NavigationState>().first()

        if (root == null) {
            // Just clear the backstack
            storeAccessor.dispatch(
                NavigationAction.NavigateState(
                    currentEntry = currentState.currentEntry,
                    backStack = listOf(),
                    clearedBackStack = true
                )
            )
            return
        }

        // Create root entry
        val rootEntry = createEntryForPath(root, params)

        // Start with empty backstack
        var newBackStack = listOf<NavigationEntry>()

        // Ensure parent paths exist
        newBackStack = ensureParentPathsExist(root, newBackStack)

        // Add root entry
        newBackStack = newBackStack + rootEntry

        // Dispatch prepared state
        storeAccessor.dispatch(
            NavigationAction.NavigateState(
                currentEntry = rootEntry,
                backStack = newBackStack,
                clearedBackStack = true
            )
        )
    }

    /**
     * Prepares a Replace action for the reducer.
     */
    private suspend fun prepareReplaceAction(
        route: String,
        params: Map<String, Any>
    ) {
        val currentState = storeAccessor.selectState<NavigationState>().first()

        // Create new entry for replacement
        val newEntry = createEntryForPath(route, params)

        // Get the entry being replaced
        val replacedEntry = currentState.currentEntry

        // Ensure parent paths exist, inserting before the replaced entry
        var newBackStack = ensureParentPathsExist(
            route,
            currentState.backStack,
            insertBeforeEntry = replacedEntry
        )

        // Replace the current entry in the backstack
        newBackStack = newBackStack.map {
            if (it == replacedEntry) newEntry else it
        }

        // Dispatch prepared state
        storeAccessor.dispatch(
            NavigationAction.NavigateState(
                currentEntry = newEntry,
                backStack = newBackStack,
                clearedBackStack = false
            )
        )
    }

    /**
     * Prepares a ClearCurrentScreenParams action for the reducer.
     */
    internal suspend fun prepareClearCurrentScreenParamsAction() {
        val currentState = storeAccessor.selectState<NavigationState>().first()
        val updatedBackStack = currentState.backStack.map { entry ->
            if (entry.screen == currentState.currentEntry.screen) {
                entry.copy(params = emptyMap())
            } else {
                entry
            }
        }

        storeAccessor.dispatch(
            NavigationAction.NavigateState(
                currentEntry = currentState.currentEntry.copy(params = emptyMap()),
                backStack = updatedBackStack,
                clearedBackStack = false
            )
        )
    }

    /**
     * Prepares a ClearCurrentScreenParam action for the reducer.
     */
    internal suspend fun prepareClearCurrentScreenParamAction(key: String) {
        val currentState = storeAccessor.selectState<NavigationState>().first()
        val updatedBackStack = currentState.backStack.map { entry ->
            if (entry.screen == currentState.currentEntry.screen) {
                entry.copy(params = entry.params - key)
            } else {
                entry
            }
        }

        storeAccessor.dispatch(
            NavigationAction.NavigateState(
                currentEntry = currentState.currentEntry.copy(params = currentState.currentEntry.params - key),
                backStack = updatedBackStack,
                clearedBackStack = false
            )
        )
    }

    /**
     * Prepares a ClearScreenParams action for the reducer.
     */
    internal suspend fun prepareClearScreenParamsAction(route: String) {
        if (!routeExists(route)) {
            throw RouteNotFoundException(route)
        }
        val currentState = storeAccessor.selectState<NavigationState>().first()
        val updatedBackStack = currentState.backStack.map { entry ->
            if (entry.screen.route == route) {
                entry.copy(params = emptyMap())
            } else {
                entry
            }
        }

        val updatedCurrentEntry = if (currentState.currentEntry.screen.route == route) {
            currentState.currentEntry.copy(params = emptyMap())
        } else {
            currentState.currentEntry
        }

        storeAccessor.dispatch(
            NavigationAction.NavigateState(
                currentEntry = updatedCurrentEntry,
                backStack = updatedBackStack,
                clearedBackStack = false
            )
        )
    }

    /**
     * Prepares a ClearScreenParam action for the reducer.
     */
    suspend fun prepareClearScreenParamAction(route: String, key: String) {
        if (!routeExists(route)) {
            throw RouteNotFoundException(route)
        }

        val currentState = storeAccessor.selectState<NavigationState>().first()

        val updatedBackStack = currentState.backStack.map { entry ->
            if (entry.screen.route == route) {
                entry.copy(params = entry.params - key)
            } else {
                entry
            }
        }

        val updatedCurrentEntry = if (currentState.currentEntry.screen.route == route) {
            currentState.currentEntry.copy(params = currentState.currentEntry.params - key)
        } else {
            currentState.currentEntry
        }

        storeAccessor.dispatch(
            NavigationAction.NavigateState(
                currentEntry = updatedCurrentEntry,
                backStack = updatedBackStack,
                clearedBackStack = false
            )
        )
    }

    // Public navigation methods that interface with the UI

    /**
     * Navigates to a specified route.
     */
    suspend fun navigate(
        route: String,
        params: Map<String, Any> = emptyMap(),
        config: (NavigationBuilder.() -> Unit)? = null
    ) {
        val (finalRoute, extractedParams) = extractRouteAndParams(route)
        if (!routeExists(finalRoute)) {
            throw RouteNotFoundException(finalRoute)
        }

        val preparedParams = (extractedParams + params).mapValues { (_, value) -> prepareParam(value) }
        val builder = NavigationBuilder(finalRoute, preparedParams)
        config?.let { builder.apply(it) }
        val action = builder.build()

        // Validation
        if (action.clearBackStack && (action.popUpTo != null || action.replaceWith != null)) {
            throw ClearingBackStackWithOtherOperations
        }

        if (action.popUpTo != null && !routeExists(action.popUpTo)) {
            throw RouteNotFoundException(action.popUpTo)
        }

        if (action.replaceWith != null && !routeExists(action.replaceWith)) {
            throw RouteNotFoundException(action.replaceWith)
        }

        // Prepare enhanced action
        prepareNavigateAction(
            route = action.route,
            params = action.params,
            popUpTo = action.popUpTo,
            inclusive = action.inclusive,
            replaceWith = action.replaceWith,
            clearBackStack = action.clearBackStack,
            forwardParams = action.forwardParams
        )
    }

    /**
     * Pops up to a specific route in the navigation stack.
     */
    suspend fun popUpTo(
        route: String,
        inclusive: Boolean = false,
        config: (PopUpToBuilder.() -> Unit)? = null
    ) {
        if (!routeExists(route)) {
            throw RouteNotFoundException(route)
        }

        val builder = PopUpToBuilder(route, inclusive)
        config?.let { builder.apply(it) }
        val action = builder.build()

        if (action.replaceWith != null && !routeExists(action.replaceWith)) {
            throw RouteNotFoundException(action.replaceWith)
        }

        preparePopUpToAction(
            route = action.route,
            inclusive = action.inclusive,
            replaceWith = action.replaceWith,
            replaceParams = action.replaceParams
        )
    }

    suspend fun navigateWithValidation(
        route: String,
        params: Map<String, Any> = emptyMap(),
        storeAccessor: StoreAccessor,
        validate: suspend (StoreAccessor, Map<String, Any>) -> Boolean
    ) {
        val (finalRoute, extractedParams) = extractRouteAndParams(route)
        if (!routeExists(finalRoute)) {
            throw RouteNotFoundException(finalRoute)
        }
        val preparedParams = (extractedParams + params).mapValues { (_, value) -> prepareParam(value) }

        storeAccessor.dispatch(NavigationAction.SetLoading(true))
        try {
            if (validate(storeAccessor, preparedParams)) {
                navigate(finalRoute, preparedParams)
            }
        } finally {
            storeAccessor.dispatch(NavigationAction.SetLoading(false))
        }
    }

    /**
     * Clears the navigation back stack.
     */
    suspend fun clearBackStack(config: (ClearBackStackBuilder.() -> Unit)? = null) {
        val builder = ClearBackStackBuilder()
        config?.let { builder.apply(it) }
        val action = builder.build()

        prepareClearBackStackAction(action.root, action.params)
    }

    /**
     * Replaces the current screen with another.
     */
    suspend fun replaceWith(route: String, params: Map<String, Any> = emptyMap()) {
        if (!routeExists(route)) {
            throw RouteNotFoundException(route)
        }

        prepareReplaceAction(route, params)
    }

    private fun routeExists(route: String): Boolean = availableScreens.containsKey(route)

    private fun extractRouteAndParams(fullRoute: String): Pair<String, Map<String, Any>> {
        val (routePart, queryPart) = fullRoute.split("?", limit = 2).let {
            if (it.size == 2) it[0] to it[1] else it[0] to ""
        }

        val (matchingRoute, pathParams) = extractPathParameters(routePart)
        val queryParams = extractQueryParameters(queryPart)

        return Pair(matchingRoute, pathParams + queryParams)
    }

    private fun extractPathParameters(path: String): Pair<String, Map<String, Any>> {
        val parts = path.split("/")
        val params = mutableMapOf<String, Any>()

        val matchingRoutes = availableScreens.keys.filter { route ->
            PathUtil.matchPath(route, path)
        }

        val matchingRoute = matchingRoutes.firstOrNull()
            ?: throw RouteNotFoundException(path)

        val routeParts = matchingRoute.split("/")
        routeParts.zip(parts).forEach { (routePart, actualPart) ->
            if (PathUtil.isParameterSegment(routePart)) {
                val paramName = PathUtil.extractParameterName(routePart)
                params[paramName] = actualPart
            }
        }

        return matchingRoute to params
    }

    private fun extractQueryParameters(query: String): Map<String, Any> {
        return query.split("&")
            .filter { it.isNotEmpty() }
            .associate { param ->
                val (key, value) = param.split("=", limit = 2).let {
                    if (it.size == 2) it[0] to it[1] else it[0] to ""
                }
                key to UrlUtil.decodeURIComponent(value)
            }
    }

    private fun prepareParam(value: Any): Any {
        return when (value) {
            is String -> UrlUtil.encodeURIComponent(value)
            is Number, Boolean -> value
            else -> throw IllegalArgumentException("Unsupported parameter type: ${value::class.simpleName}")
        }
    }
}