package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.Dispatch
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.Store
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
    val dispatch: Dispatch
) : ModuleLogic<NavigationAction>() {

    private fun routeExists(route: String): Boolean = availableScreens.containsKey(route)

    /**
     * Navigates to a specified route with optional parameters and configuration.
     *
     * @param route The destination route.
     * @param params The parameters to pass to the destination.
     * @param config An optional configuration block for the navigation action.
     * @throws RouteNotFoundException if the specified route doesn't exist.
     *
     * Example:
     * ```
     * navigationLogic.navigate(
     *     route = "profile",
     *     params = mapOf("userId" to 123),
     *     config = {
     *         popUpTo("home")
     *     }
     * )
     * ```
     */
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

        // Additional validation for popUpTo and replaceWith
        if (action.popUpTo != null && !routeExists(action.popUpTo)) {
            throw RouteNotFoundException(action.popUpTo)
        }
        if (action.replaceWith != null && !routeExists(action.replaceWith)) {
            throw RouteNotFoundException(action.replaceWith)
        }
        dispatch(action)
    }

    /**
     * Pops the back stack up to a specified route.
     *
     * @param route The route to pop up to.
     * @param inclusive Whether to include the specified route in the pop operation.
     * @param config An optional configuration block for the pop-up-to action.
     * @throws RouteNotFoundException if the specified route doesn't exist.
     *
     * Example:
     * ```
     * navigationLogic.popUpTo(
     *     route = "home",
     *     inclusive = true,
     *     config = {
     *         replaceWith("dashboard")
     *     }
     * )
     * ```
     */
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

        dispatch(action)
    }

    /**
     * Navigates back to the previous screen in the back stack.
     *
     * Example:
     * ```
     * navigationLogic.navigateBack()
     * ```
     */
    fun navigateBack() {
        dispatch(NavigationAction.Back)
    }

    /**
     * Clears the entire back stack, leaving only the current screen.
     *
     * Example:
     * ```
     * navigationLogic.clearBackStack()
     * ```
     */
    fun clearBackStack() {
        dispatch(NavigationAction.ClearBackStack)
    }

    /**
     * Replaces the current screen with a new one.
     *
     * @param route The route of the screen to replace with.
     * @param params The parameters to pass to the new screen.
     * @throws RouteNotFoundException if the specified route doesn't exist.
     *
     * Example:
     * ```
     * navigationLogic.replaceWith("settings", mapOf("theme" to "dark"))
     * ```
     */
    fun replaceWith(route: String, params: Map<String, Any> = emptyMap()) {
        if (!routeExists(route)) {
            throw RouteNotFoundException(route)
        }
        dispatch(NavigationAction.Replace(route, params))
    }

    /**
     * Navigates to a specified route with validation.
     *
     * @param route The destination route.
     * @param params The parameters to pass to the destination.
     * @param store The Reaktiv store instance.
     * @param validate A suspend function that performs validation before navigation.
     *
     * Example:
     * ```
     * navigationLogic.navigateWithValidation(
     *     route = "checkout",
     *     params = mapOf("cartId" to 456),
     *     store = myStore
     * ) { store, params ->
     *     val cartId = params["cartId"] as? Int ?: return@navigateWithValidation false
     *     val cartState = store.selectState<CartState>().value
     *     cartState.items.isNotEmpty() && cartState.totalAmount > 0
     * }
     * ```
     */
    fun navigateWithValidation(
        route: String,
        params: Map<String, Any> = emptyMap(),
        store: Store,
        validate: suspend (Store, Map<String, Any>) -> Boolean
    ) {
        val (finalRoute, extractedParams) = extractRouteAndParams(route)
        if (!routeExists(finalRoute)) {
            throw RouteNotFoundException(finalRoute)
        }
        val sanitizedParams = (extractedParams + params).mapValues { (_, value) -> sanitizeParam(value) }
        coroutineScope.launch {
            dispatch(NavigationAction.SetLoading(true))
            try {
                if (validate(store, sanitizedParams)) {
                    navigate(finalRoute, sanitizedParams)
                }
            } catch (e: Exception) {
                throw e
            } finally {
                dispatch(NavigationAction.SetLoading(false))
            }
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

    private fun sanitizeParam(value: Any): Any {
        return when (value) {
            is String -> value.replace(Regex("[^A-Za-z0-9_-]"), "")
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