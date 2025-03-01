package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.util.PathUtil
import io.github.syrou.reaktiv.navigation.util.UrlUtil
import kotlinx.coroutines.CoroutineScope
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

    private fun routeExists(route: String): Boolean = availableScreens.containsKey(route)

    fun navigate(
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

        // Simple validation and dispatch - no need for ID handling
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

    fun navigateToChild(parentPath: String, childSegment: String, params: Map<String, Any> = emptyMap()) {
        navigate("$parentPath/$childSegment", params)
    }

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