package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.dsl.ClearBackStackBuilder
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import io.github.syrou.reaktiv.navigation.dsl.PopUpToBuilder
import io.github.syrou.reaktiv.navigation.exception.ClearingBackStackWithOtherOperations
import io.github.syrou.reaktiv.navigation.exception.RouteNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
        val sanitizedParams = (extractedParams + params).mapValues { (_, value) -> sanitizeParam(value) }
        val builder = NavigationBuilder(finalRoute, sanitizedParams)
        config?.let { builder.apply(it) }
        val action = builder.build()

        if (action.clearBackStack && action.popUpTo == null && action.replaceWith == null) {
            storeAccessor.dispatch(action)
        } else if (action.clearBackStack && (action.popUpTo != null || action.replaceWith != null)) {
            throw ClearingBackStackWithOtherOperations
        } else {
            // Additional validation for popUpTo and replaceWith
            if (action.popUpTo != null && !routeExists(action.popUpTo)) {
                throw RouteNotFoundException(action.popUpTo)
            }
            if (action.replaceWith != null && !routeExists(action.replaceWith)) {
                throw RouteNotFoundException(action.replaceWith)
            }
            storeAccessor.dispatch(action)
        }
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

        // Validate replaceWith route
        if (action.replaceWith != null && !routeExists(action.replaceWith)) {
            throw RouteNotFoundException(action.replaceWith)
        }

        storeAccessor.dispatch(action)
    }

    fun navigateBack() {
        storeAccessor.dispatch(NavigationAction.Back)
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
        val sanitizedParams = (extractedParams + params).mapValues { (_, value) -> sanitizeParam(value) }
        coroutineScope.launch {
            storeAccessor.dispatch(NavigationAction.SetLoading(true))
            try {
                if (validate(storeAccessor, sanitizedParams)) {
                    navigate(finalRoute, sanitizedParams)
                }
            } catch (e: Exception) {
                throw e
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

    private fun extractRouteAndParams(fullRoute: String): Pair<String, Map<String, Any>> {
        val (routePart, queryPart) = fullRoute.split("?", limit = 2).let {
            if (it.size == 2) it[0] to it[1] else it[0] to ""
        }

        val (matchingRoute, pathParams) = extractPathParameters(routePart)
        val queryParams = extractQueryParameters(queryPart)
        val sanitizedParams = (pathParams + queryParams).mapValues { (_, value) -> sanitizeParam(value) }

        return Pair(matchingRoute, sanitizedParams)
    }

    private val allowedCharacters = Regex("""%(?![0-9A-Fa-f]{2})|[^a-zA-Z0-9-._~%]""")
    private fun sanitizeParam(value: Any): Any {
        return when (value) {
            is String -> {
                val matches = allowedCharacters.findAll(value)
                if (matches.any()) {
                    println("WARNING: Parameter value: $value contains characters that need to be encoded.")
                    println("Removing non valid characters...")
                    matches.forEach { match ->
                        println("'${match.value}' at index ${match.range.first}")
                    }
                }
                value.replace(allowedCharacters, "")
            }

            is Number -> value
            is Boolean -> value
            else -> throw IllegalArgumentException("Unsupported parameter type: ${value::class.simpleName}")
        }
    }

    private fun extractPathParameters(path: String): Pair<String, Map<String, Any>> {
        val parts = path.split("/")
        val params = mutableMapOf<String, Any>()

        val matchingRoute = availableScreens.keys.find { routeKey ->
            val routeParts = routeKey.split("/")
            if (routeParts.size != parts.size) return@find false

            routeParts.zip(parts).all { (routePart, actualPart) ->
                when {
                    routePart == actualPart -> true
                    routePart.startsWith("{") && routePart.endsWith("}") -> true
                    else -> false
                }
            }
        } ?: throw IllegalArgumentException("No matching route found for: $path")

        val routeParts = matchingRoute.split("/")
        routeParts.zip(parts).forEach { (routePart, actualPart) ->
            if (routePart.startsWith("{") && routePart.endsWith("}")) {
                val paramName = routePart.substring(1, routePart.length - 1)
                params[paramName] = actualPart
            }
        }

        return Pair(matchingRoute, params)
    }

    private fun extractQueryParameters(query: String): Map<String, Any> {
        return query.split("&")
            .filter { it.isNotEmpty() }
            .associate { param ->
                val (key, value) = param.split("=", limit = 2).let {
                    if (it.size == 2) it[0] to it[1] else it[0] to ""
                }
                key to value
            }
    }
}