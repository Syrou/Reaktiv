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
    private fun extractPathSegmentsWithRegex(path: String): List<String> {
        val segmentPattern = Regex("([^/]+/\\{[^}]+\\})|([^/]+(?=/))|([^/]+\$)")

        return segmentPattern.findAll(path)
            .map { it.value }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun ensureParentPathsExist(
        path: String,
        currentBackStack: List<NavigationEntry>,
        insertBeforeEntry: NavigationEntry? = null
    ): List<NavigationEntry> {
        val pathSegments = extractPathSegmentsWithRegex(path)

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
            val parentScreen = availableScreens[currentPath] ?: error("No screen found for parent path: $currentPath")

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
    /**
     * Prepares a Navigate action for the reducer with support for nested navigation.
     * Only dispatches NavigationAction.NavigateState once at the end.
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
        println("HERPADERPA - prepareNavigateAction - current entry: ${currentState.rootEntry}")
        println("HARKELDARKEL - prepareNavigateAction - route: $route")
        println("HARKELDARKEL - prepareNavigateAction - clearBackStack: $clearBackStack")
        var newBackStack = if (clearBackStack) listOf() else currentState.backStack
        var newNestedBackStack = if (clearBackStack) listOf() else currentState.nestedBackStack

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

        // Create the new entry for the target route
        val targetEntry = createEntryForPath(targetRoute, finalParams)
        var mutableNestedBackStack = newNestedBackStack.toMutableList()
        // Process nested navigation when applicable
        var rootEntry = currentState.rootEntry
        val nestedDepth = currentState.nestedBackStack.size
        if (isNestedRoute(targetRoute)) {
            val depth = targetRoute.count { it == '/' }
            println("HERPADERPA - target route depth: $depth")
            println("HERPADERPA - previous nested depth: $nestedDepth")
            if(nestedDepth < depth) {
                mutableNestedBackStack = (newNestedBackStack + targetEntry).toMutableList()
            }else if(depth == nestedDepth){
                mutableNestedBackStack[depth-1] = targetEntry
            }
        } else {
            rootEntry = targetEntry
            newBackStack + targetEntry
        }

        newNestedBackStack.forEach {
            println("HERPADERPA - prepareNavigateAction - newNestedBackStack: $it")
        }
        println("HERPADERPA - prepareNavigateAction new rootEntry: $rootEntry")

        // Create enhanced action with prepared backstack - dispatch only once
        val enhancedAction = NavigationAction.NavigateState(
            rootEntry = rootEntry,
            backStack = newBackStack,
            nestedBackStack = mutableNestedBackStack,
            clearedBackStack = clearBackStack
        )

        storeAccessor.dispatch(enhancedAction)
    }

    /**
     * Determines if a route is nested (contains path separators).
     */
    private fun isNestedRoute(route: String): Boolean {
        return route.contains("/")
    }

    /**
     * Finds the parent route for a nested route.
     * For example, for "home/profile/edit", it would return "home/profile".
     */
    private fun findParentRoute(route: String): String? {
        val lastSlashIndex = route.lastIndexOf('/')
        if (lastSlashIndex <= 0) return null
        return route.substring(0, lastSlashIndex)
    }

    /**
     * Updates the navigation hierarchy to include the new child entry at the specified parent route.
     *
     * @param currentRoot The current root entry
     * @param parentRoute The route of the parent entry that should receive the child
     * @param newChildEntry The new child entry to add
     * @return The updated root entry with the new navigation hierarchy
     */
    private fun updateNavigationHierarchy(
        currentRoot: NavigationEntry,
        parentRoute: String,
        newChildEntry: NavigationEntry
    ): NavigationEntry {
        // If the current root is the parent, attach the child directly
        if (currentRoot.path == parentRoute) {
            return currentRoot.copy(childEntry = newChildEntry)
        }

        // Otherwise, recursively update the child hierarchy
        if (currentRoot.hasChild()) {
            val updatedChild = updateNavigationHierarchy(currentRoot.childEntry!!, parentRoute, newChildEntry)
            return currentRoot.copy(childEntry = updatedChild)
        }

        // If we get here, the parent wasn't found in the hierarchy - return unchanged
        return currentRoot
    }

    /**
     * Helper method to ensure parent paths exist in a navigation operation.
     * This is used for the backstack to ensure that parent containers are included.
     */
    private fun ensureParentPathsExist(
        path: String,
        currentBackStack: List<NavigationEntry>
    ): List<NavigationEntry> {
        val pathSegments = extractPathSegmentsWithRegex(path)

        // If not a nested path, return original backstack
        if (pathSegments.size <= 1) {
            return currentBackStack
        }

        val newBackStack = currentBackStack.toMutableList()
        var currentPath = ""

        // Add all parent paths to backstack
        for (i in 0..<pathSegments.size - 1) {
            if (i > 0) currentPath += "/"
            currentPath += pathSegments[i]

            // Skip if already in backstack
            if (newBackStack.any { it.screen.route == currentPath }) {
                continue
            }

            val parentScreen = availableScreens[currentPath] ?: error("No screen found for parent path: $currentPath")

            // Only add to backstack if it's a container screen
            if (parentScreen.isContainer) {
                val parentEntry = NavigationEntry(
                    screen = parentScreen,
                    params = emptyMap(),
                    id = currentPath
                )

                newBackStack.add(parentEntry)
            }
        }

        return newBackStack
    }

    /**
     * Prepares a Back action for the reducer.
     */
    internal suspend fun prepareBackAction() {
        val currentState = storeAccessor.selectState<NavigationState>().first()
        println("HERPADERPA - prepareBackAction - backStack: ${currentState.backStack.size}")

        // Handle regular back navigation
        if (currentState.backStack.isEmpty()) return

        // Find current entry index
        //val currentIndex = currentState.backStack.indexOf(currentState.rootEntry)
        //if (currentIndex <= 0) return

        // Get previous entry
        val newEntry = if (currentState.nestedBackStack.isEmpty()) {
            currentState.backStack[currentState.backStack.size - 1]
        } else {
            currentState.nestedBackStack[currentState.nestedBackStack.size - 1]
        }

        // Create backstack without current entry
        var newBackStack = currentState.backStack
        var newNestedBackStack = currentState.nestedBackStack
        //val newEntry = currentState.backStack.last()
        if (currentState.nestedBackStack.isEmpty()) {
            newBackStack = currentState.backStack.dropLast(1)
        } else {
            newNestedBackStack = currentState.nestedBackStack.dropLast(1)
        }
        println("HERPADERPA - prepareBackAction - newEntry: $newEntry")
        println("HERPADERPA - prepareBackAction - newBackStack: $newBackStack")
        // Dispatch prepared state
        storeAccessor.dispatch(
            NavigationAction.NavigateState(
                rootEntry = newEntry,
                backStack = newBackStack,
                nestedBackStack = newNestedBackStack,
                clearedBackStack = false
            )
        )
    }

    /**
     * Helper function to navigate back within a nested navigation hierarchy.
     * It removes the deepest child first when back is pressed.
     */
    private fun navigateBackInNestedHierarchy(entry: NavigationEntry): NavigationEntry {
        // If the entry doesn't have a child, return it unchanged
        if (!entry.hasChild()) return entry

        // If the child has a child, recursively navigate back in the child's hierarchy
        if (entry.childEntry!!.hasChild()) {
            val updatedChild = navigateBackInNestedHierarchy(entry.childEntry!!)
            return entry.copy(childEntry = updatedChild)
        }

        // If the child has no child (it's a leaf node), remove it and return
        return entry.copy(childEntry = null)
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

        var newEntry = newBackStack.lastOrNull() ?: currentState.rootEntry

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
                rootEntry = newEntry,
                backStack = newBackStack,
                nestedBackStack = currentState.nestedBackStack,
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
                    rootEntry = currentState.rootEntry,
                    backStack = listOf(),
                    nestedBackStack = currentState.nestedBackStack,
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
                rootEntry = rootEntry,
                backStack = newBackStack,
                nestedBackStack = currentState.nestedBackStack,
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
        val replacedEntry = currentState.rootEntry

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
                rootEntry = newEntry,
                backStack = newBackStack,
                nestedBackStack = currentState.nestedBackStack,
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
            if (entry.screen == currentState.rootEntry.screen) {
                entry.copy(params = emptyMap())
            } else {
                entry
            }
        }

        storeAccessor.dispatch(
            NavigationAction.NavigateState(
                rootEntry = currentState.rootEntry.copy(params = emptyMap()),
                backStack = updatedBackStack,
                nestedBackStack = currentState.nestedBackStack,
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
            if (entry.screen == currentState.rootEntry.screen) {
                entry.copy(params = entry.params - key)
            } else {
                entry
            }
        }

        storeAccessor.dispatch(
            NavigationAction.NavigateState(
                rootEntry = currentState.rootEntry.copy(params = currentState.rootEntry.params - key),
                backStack = updatedBackStack,
                nestedBackStack = currentState.nestedBackStack,
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

        val updatedCurrentEntry = if (currentState.rootEntry.screen.route == route) {
            currentState.rootEntry.copy(params = emptyMap())
        } else {
            currentState.rootEntry
        }

        storeAccessor.dispatch(
            NavigationAction.NavigateState(
                rootEntry = updatedCurrentEntry,
                backStack = updatedBackStack,
                nestedBackStack = currentState.nestedBackStack,
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

        val updatedCurrentEntry = if (currentState.rootEntry.screen.route == route) {
            currentState.rootEntry.copy(params = currentState.rootEntry.params - key)
        } else {
            currentState.rootEntry
        }

        storeAccessor.dispatch(
            NavigationAction.NavigateState(
                rootEntry = updatedCurrentEntry,
                backStack = updatedBackStack,
                nestedBackStack = currentState.nestedBackStack,
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