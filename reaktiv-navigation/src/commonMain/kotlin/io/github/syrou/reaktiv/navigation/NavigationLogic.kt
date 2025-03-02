package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.util.PathUtil
import io.github.syrou.reaktiv.navigation.util.UrlUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Handles the logic for navigation actions in the Reaktiv architecture.
 *
 * @property coroutineScope The CoroutineScope for this logic.
 * @property availableScreens A map of all available screens in the application.
 */
class NavigationLogic(
    private val coroutineScope: CoroutineScope,
    private val availableScreens: Map<String, Screen>,
    val storeAccessor: StoreAccessor
) : ModuleLogic<NavigationAction>() {

    private fun routeExists(route: String): Boolean {
        val exists = availableScreens.containsKey(route)
        println("DEBUG [NavigationLogic.routeExists] route: '$route', exists: $exists")
        return exists
    }

    /**
     * Navigates to a specified route.
     */
    fun navigate(
        route: String,
        params: Map<String, Any> = emptyMap(),
        config: (NavigationBuilder.() -> Unit)? = null
    ) {
        println("DEBUG [NavigationLogic.navigate] to route: '$route', params: $params")

        val (finalRoute, extractedParams) = extractRouteAndParams(route)
        println("DEBUG [NavigationLogic.navigate] finalRoute: '$finalRoute', extractedParams: $extractedParams")

        if (!routeExists(finalRoute)) {
            println("DEBUG [NavigationLogic.navigate] ROUTE NOT FOUND: '$finalRoute'")
            throw RouteNotFoundException(finalRoute)
        }

        val preparedParams = (extractedParams + params).mapValues { (_, value) -> prepareParam(value) }
        val builder = NavigationBuilder(finalRoute, preparedParams)
        config?.let { builder.apply(it) }

        // Get navigation state for debugging
        coroutineScope.launch {
            println("DEBUG [NavigationLogic.navigate] getting current state for debugging")
            val currentState = storeAccessor.selectState<NavigationState>().first()
            println("DEBUG [NavigationLogic.navigate] current path: '${currentState.currentEntry.path}'")
            println("DEBUG [NavigationLogic.navigate] backStack: ${currentState.backStack.map { it.path }}")

            // Determine parent path and entry
            val parentPath = PathUtil.getParentPath(finalRoute)
            println("DEBUG [NavigationLogic.navigate] parentPath: '$parentPath'")

            val parentEntry = if (parentPath.isNotEmpty()) {
                val foundParent = currentState.backStack.find { it.path == parentPath }
                println("DEBUG [NavigationLogic.navigate] found parent: ${foundParent?.path}")
                foundParent
            } else null

            // Build the action
            val action = builder.build().copy(parent = parentEntry)
            println("DEBUG [NavigationLogic.navigate] dispatching action: $action")

            // Validation and dispatch (unchanged)
            if (action.clearBackStack && action.popUpTo == null && action.replaceWith == null) {
                storeAccessor.dispatch(action)
            } else if (action.clearBackStack && (action.popUpTo != null || action.replaceWith != null)) {
                throw ClearingBackStackWithOtherOperations
            } else {
                if (action.popUpTo != null && !routeExists(action.popUpTo)) {
                    throw RouteNotFoundException(action.popUpTo)
                }
                if (action.replaceWith != null && !routeExists(action.replaceWith)) {
                    throw RouteNotFoundException(action.replaceWith)
                }
                storeAccessor.dispatch(action)
            }
        }
    }

    /**
     * Navigates to a child screen in nested navigation.
     */
    fun navigateToChild(parentPath: String, childSegment: String, params: Map<String, Any> = emptyMap()) {
        println("DEBUG [NavigationLogic.navigateToChild] parentPath: '$parentPath', childSegment: '$childSegment'")

        val fullPath = if (parentPath.endsWith("/")) {
            "$parentPath$childSegment"
        } else {
            "$parentPath/$childSegment"
        }

        println("DEBUG [NavigationLogic.navigateToChild] fullPath: '$fullPath'")

        coroutineScope.launch {
            println("DEBUG [NavigationLogic.navigateToChild] getting current state")
            val currentState = storeAccessor.selectState<NavigationState>().first()
            println("DEBUG [NavigationLogic.navigateToChild] current path: '${currentState.currentEntry.path}'")
            println("DEBUG [NavigationLogic.navigateToChild] backStack: ${currentState.backStack.map { it.path }}")

            val parentEntry = currentState.backStack.find { it.path == parentPath }
            println("DEBUG [NavigationLogic.navigateToChild] found parent: ${parentEntry?.path}")

            if (parentEntry == null) {
                println("DEBUG [NavigationLogic.navigateToChild] PARENT NOT FOUND: '$parentPath'")
                throw RouteNotFoundException("Parent path not found: $parentPath")
            }

            if (!routeExists(fullPath)) {
                println("DEBUG [NavigationLogic.navigateToChild] ROUTE NOT FOUND: '$fullPath'")
                throw RouteNotFoundException(fullPath)
            }

            val preparedParams = params.mapValues { (_, value) -> prepareParam(value) }

            // Set the parent reference in the action
            val action = NavigationAction.Navigate(
                route = fullPath,
                params = preparedParams,
                parent = parentEntry
            )

            println("DEBUG [NavigationLogic.navigateToChild] dispatching action: $action")
            storeAccessor.dispatch(action)
        }
    }

    private fun extractRouteAndParams(fullRoute: String): Pair<String, Map<String, Any>> {
        println("DEBUG [NavigationLogic.extractRouteAndParams] fullRoute: '$fullRoute'")

        val (routePart, queryPart) = fullRoute.split("?", limit = 2).let {
            if (it.size == 2) it[0] to it[1] else it[0] to ""
        }

        println("DEBUG [NavigationLogic.extractRouteAndParams] routePart: '$routePart', queryPart: '$queryPart'")

        val (matchingRoute, pathParams) = extractPathParameters(routePart)
        val queryParams = extractQueryParameters(queryPart)

        println("DEBUG [NavigationLogic.extractRouteAndParams] matchingRoute: '$matchingRoute', pathParams: $pathParams, queryParams: $queryParams")

        return Pair(matchingRoute, pathParams + queryParams)
    }

    private fun extractPathParameters(path: String): Pair<String, Map<String, Any>> {
        println("DEBUG [NavigationLogic.extractPathParameters] path: '$path'")

        val parts = path.split("/")
        println("DEBUG [NavigationLogic.extractPathParameters] parts: $parts")

        val params = mutableMapOf<String, Any>()

        val matchingRoutes = availableScreens.keys.filter { route ->
            val matches = PathUtil.matchPath(route, path)
            println("DEBUG [NavigationLogic.extractPathParameters] checking route: '$route', matches: $matches")
            matches
        }

        println("DEBUG [NavigationLogic.extractPathParameters] matchingRoutes: $matchingRoutes")

        val matchingRoute = matchingRoutes.firstOrNull()
            ?: throw RouteNotFoundException(path)

        println("DEBUG [NavigationLogic.extractPathParameters] selected matchingRoute: '$matchingRoute'")

        val routeParts = matchingRoute.split("/")
        routeParts.zip(parts).forEach { (routePart, actualPart) ->
            if (PathUtil.isParameterSegment(routePart)) {
                val paramName = PathUtil.extractParameterName(routePart)
                params[paramName] = actualPart
                println("DEBUG [NavigationLogic.extractPathParameters] extracted param: '$paramName' = '$actualPart'")
            }
        }

        return matchingRoute to params
    }

    private fun extractQueryParameters(query: String): Map<String, Any> {
        if (query.isEmpty()) return emptyMap()

        println("DEBUG [NavigationLogic.extractQueryParameters] query: '$query'")

        val params = query.split("&")
            .filter { it.isNotEmpty() }
            .associate { param ->
                val (key, value) = param.split("=", limit = 2).let {
                    if (it.size == 2) it[0] to it[1] else it[0] to ""
                }
                println("DEBUG [NavigationLogic.extractQueryParameters] param: '$key' = '$value'")
                key to UrlUtil.decodeURIComponent(value)
            }

        println("DEBUG [NavigationLogic.extractQueryParameters] params: $params")
        return params
    }

    private fun prepareParam(value: Any): Any {
        return when (value) {
            is String -> {
                val encoded = UrlUtil.encodeURIComponent(value)
                println("DEBUG [NavigationLogic.prepareParam] encoded '$value' to '$encoded'")
                encoded
            }
            is Number, Boolean -> {
                println("DEBUG [NavigationLogic.prepareParam] keeping as is: $value")
                value
            }
            else -> {
                println("DEBUG [NavigationLogic.prepareParam] UNSUPPORTED TYPE: ${value::class.simpleName}")
                throw IllegalArgumentException("Unsupported parameter type: ${value::class.simpleName}")
            }
        }
    }

    fun navigateBack() {
        storeAccessor.dispatch(NavigationAction.Back)
    }

    fun popUpTo(
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

        storeAccessor.dispatch(action)
    }

    fun clearBackStack(config: (ClearBackStackBuilder.() -> Unit)? = null) {
        val builder = ClearBackStackBuilder()
        config?.let { builder.apply(it) }
        val action = builder.build()
        storeAccessor.dispatch(action)
    }

    fun replaceWith(route: String, params: Map<String, Any> = emptyMap()) {
        if (!routeExists(route)) {
            throw RouteNotFoundException(route)
        }
        storeAccessor.dispatch(NavigationAction.Replace(route, params))
    }

    fun navigateWithValidation(
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
        coroutineScope.launch {
            storeAccessor.dispatch(NavigationAction.SetLoading(true))
            try {
                if (validate(storeAccessor, preparedParams)) {
                    navigate(finalRoute, preparedParams)
                }
            } finally {
                storeAccessor.dispatch(NavigationAction.SetLoading(false))
            }
        }
    }

    fun clearCurrentScreenParams() {
        storeAccessor.dispatch(NavigationAction.ClearCurrentScreenParams)
    }

    fun clearCurrentScreenParam(key: String) {
        storeAccessor.dispatch(NavigationAction.ClearCurrentScreenParam(key))
    }

    fun clearScreenParams(route: String) {
        if (routeExists(route)) {
            storeAccessor.dispatch(NavigationAction.ClearScreenParams(route))
        } else {
            throw RouteNotFoundException(route)
        }
    }

    fun clearScreenParam(route: String, key: String) {
        if (routeExists(route)) {
            storeAccessor.dispatch(NavigationAction.ClearScreenParam(route, key))
        } else {
            throw RouteNotFoundException(route)
        }
    }
}