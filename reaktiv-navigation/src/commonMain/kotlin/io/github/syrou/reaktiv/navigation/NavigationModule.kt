package io.github.syrou.reaktiv.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.syrou.reaktiv.core.Dispatch
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Custom exception thrown when a requested route is not found in the navigation system.
 *
 * @property route The route that was not found.
 */
class RouteNotFoundException(route: String) : Exception("No screen found for route: $route")

/**
 * Represents a node in the navigation hierarchy.
 */
interface NavigationNode

/**
 * Represents a screen in the navigation system.
 */
interface Screen : NavigationNode {
    /**
     * The unique identifier for this screen.
     */
    val route: String

    /**
     * The resource ID for the screen's title.
     */
    val titleResourceId: Int

    /**
     * The transition to use when entering this screen.
     */
    val enterTransition: NavTransition

    /**
     * The transition to use when exiting this screen.
     */
    val exitTransition: NavTransition

    /**
     * Whether this screen requires authentication to access.
     */
    val requiresAuth: Boolean

    /**
     * The composable content of the screen.
     *
     * @param params The parameters passed to this screen.
     */
    @Composable
    fun Content(params: Map<String, Any>)
}

/**
 * Represents a group of related screens in the navigation hierarchy.
 *
 * @property screens The list of screens in this group.
 */
open class ScreenGroup(
    val screens: List<Screen>
) : NavigationNode {
    /**
     * Convenience constructor to create a ScreenGroup from a vararg of Screens.
     *
     * @param screens The screens to include in this group.
     */
    constructor(vararg screens: Screen) : this(screens.toList())
}

/**
 * Defines the types of transitions available for navigation.
 */
sealed class NavTransition {
    /**
     * No transition animation.
     */
    data object None : NavTransition()

    /**
     * Slide transition animation.
     */
    data object Slide : NavTransition()

    /**
     * Fade transition animation.
     */
    data object Fade : NavTransition()

    /**
     * Scale transition animation.
     */
    data object Scale : NavTransition()

    /**
     * Custom transition animation.
     *
     * @property enter The enter transition.
     * @property exit The exit transition.
     */
    data class Custom(val enter: EnterTransition, val exit: ExitTransition) : NavTransition()
}

/**
 * Represents the current state of the navigation system.
 *
 * @property currentScreen The currently displayed screen.
 * @property backStack A list of previous screens and their associated parameters.
 * @property params The parameters for the current screen.
 * @property availableScreens A map of all available screens in the application.
 * @property isLoading Indicates whether a navigation action is in progress.
 */
data class NavigationState(
    val currentScreen: Screen,
    val backStack: List<Pair<Screen, Map<String, Any>>>,
    val params: Map<String, Any> = emptyMap(),
    val availableScreens: Map<String, Screen> = emptyMap(),
    val isLoading: Boolean = false
) : ModuleState

/**
 * Sealed class representing various navigation actions.
 */
sealed class NavigationAction : ModuleAction(NavigationModule::class) {
    /**
     * Represents a navigation to a specific route with optional parameters and configuration.
     *
     * @property route The destination route.
     * @property params Optional parameters for the destination screen.
     * @property popUpTo The route to pop up to before navigating.
     * @property inclusive Whether to include the popUpTo route in the pop operation.
     * @property isSingleTop Whether to bring an existing instance of the route to the top instead of creating a new one.
     * @property replaceWith The route to replace the current screen with.
     */
    data class Navigate(
        val route: String,
        val params: Map<String, Any> = emptyMap(),
        val popUpTo: String? = null,
        val inclusive: Boolean = false,
        val isSingleTop: Boolean = false,
        val replaceWith: String? = null
    ) : NavigationAction()

    /**
     * Represents a navigation back action.
     */
    data object Back : NavigationAction()

    /**
     * Represents a navigation action to pop the back stack to a specific route.
     *
     * @property route The target route to pop to.
     * @property inclusive Whether to include the target route in the pop operation.
     * @property replaceWith The route to replace the popped-to screen with.
     * @property replaceParams Parameters for the replacement screen.
     */
    data class PopUpTo(
        val route: String,
        val inclusive: Boolean,
        val replaceWith: String? = null,
        val replaceParams: Map<String, Any> = emptyMap()
    ) : NavigationAction()

    /**
     * Represents an action to clear the entire back stack.
     */
    data object ClearBackStack : NavigationAction()

    /**
     * Represents an action to replace the current screen with a new one.
     *
     * @property route The route of the new screen.
     * @property params Parameters for the new screen.
     */
    data class Replace(val route: String, val params: Map<String, Any> = emptyMap()) : NavigationAction()

    /**
     * Represents an action to set the loading state of the navigation.
     *
     * @property isLoading The new loading state.
     */
    data class SetLoading(val isLoading: Boolean) : NavigationAction()
}

/**
 * Builder class for configuring navigation actions.
 *
 * @property route The destination route.
 * @property params Parameters for the destination screen.
 */
class NavigationBuilder(
    var route: String,
    var params: Map<String, Any> = emptyMap()
) {
    private var popUpTo: String? = null
    private var inclusive: Boolean = false
    private var isSingleTop: Boolean = false
    private var replaceWith: String? = null

    /**
     * Configures the navigation to pop up to a specific route before navigating.
     *
     * @param route The route to pop up to.
     * @param inclusive Whether to include the specified route in the pop operation.
     * @return This NavigationBuilder for chaining.
     */
    fun popUpTo(route: String, inclusive: Boolean = false): NavigationBuilder {
        this.popUpTo = route
        this.inclusive = inclusive
        return this
    }

    /**
     * Configures the navigation to use single top behavior.
     *
     * @return This NavigationBuilder for chaining.
     */
    fun singleTop(): NavigationBuilder {
        this.isSingleTop = true
        return this
    }

    /**
     * Configures the navigation to replace the current screen with a new one.
     *
     * @param route The route of the screen to replace with.
     * @return This NavigationBuilder for chaining.
     */
    fun replaceWith(route: String): NavigationBuilder {
        this.replaceWith = route
        return this
    }

    internal fun build(): NavigationAction.Navigate {
        return NavigationAction.Navigate(
            route = route,
            params = params,
            popUpTo = popUpTo,
            inclusive = inclusive,
            isSingleTop = isSingleTop,
            replaceWith = replaceWith
        )
    }
}

/**
 * Builder class for configuring pop up to actions.
 *
 * @property route The route to pop up to.
 * @property inclusive Whether to include the specified route in the pop operation.
 */
class PopUpToBuilder(
    var route: String,
    var inclusive: Boolean = false
) {
    private var replaceWith: String? = null
    private var replaceParams: Map<String, Any> = emptyMap()

    /**
     * Configures the pop up to action to replace the popped-to screen with a new one.
     *
     * @param route The route of the screen to replace with.
     * @param params Parameters for the replacement screen.
     * @return This PopUpToBuilder for chaining.
     */
    fun replaceWith(route: String, params: Map<String, Any> = emptyMap()): PopUpToBuilder {
        this.replaceWith = route
        this.replaceParams = params
        return this
    }

    internal fun build(): NavigationAction.PopUpTo {
        return NavigationAction.PopUpTo(
            route = route,
            inclusive = inclusive,
            replaceWith = replaceWith,
            replaceParams = replaceParams
        )
    }
}

/**
 * Logic class responsible for handling navigation actions.
 *
 * @property coroutineContext The coroutine context for asynchronous operations.
 * @property availableScreens A map of all available screens in the application.
 */
class NavigationLogic(
    private val coroutineContext: CoroutineContext,
    private val availableScreens: Map<String, Screen>
) : ModuleLogic<NavigationAction>() {

    val scope = CoroutineScope(SupervisorJob() + coroutineContext)

    private fun routeExists(route: String): Boolean = availableScreens.containsKey(route)

    /**
     * Navigates to the specified route with optional parameters and configuration.
     *
     * @param route The destination route.
     * @param params Optional parameters for the destination screen.
     * @param config Optional configuration for the navigation action.
     *
     * @throws RouteNotFoundException if the specified route does not exist.
     *
     * Example usage:
     * ```
     * navigate("profile", mapOf("userId" to "123")) {
     *     popUpTo("home")
     *     singleTop()
     * }
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
     * Pops the back stack up to a specified route with optional configuration.
     *
     * @param route The route to pop up to.
     * @param inclusive Whether to include the specified route in the pop operation.
     * @param config Optional configuration for the pop up to action.
     *
     * @throws RouteNotFoundException if the specified route does not exist.
     *
     * Example usage:
     * ```
     * popUpTo("home", inclusive = true) {
     *     replaceWith("dashboard", mapOf("refresh" to true))
     * }
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
     * Example usage:
     * ```
     * navigateBack()
     * ```
     */
    fun navigateBack() {
        dispatch(NavigationAction.Back)
    }

    /**
     * Clears the entire back stack, leaving only the initial screen.
     *
     * Example usage:
     * ```
     * clearBackStack()
     * ```
     */
    fun clearBackStack() {
        dispatch(NavigationAction.ClearBackStack)
    }

    /**
     * Replaces the current screen with a new one.
     *
     * @param route The route of the new screen.
     * @param params Parameters for the new screen.
     *
     * @throws RouteNotFoundException if the specified route does not exist.
     *
     * Example usage:
     * ```
     * replaceWith("profile", mapOf("userId" to "123"))
     * ```
     */
    fun replaceWith(route: String, params: Map<String, Any> = emptyMap()) {
        if (!routeExists(route)) {
            throw RouteNotFoundException(route)
        }
        dispatch(NavigationAction.Replace(route, params))
    }

    /**
     * Navigates to a route with validation.
     *
     * @param route The destination route.
     * @param params Parameters for the destination screen.
     * @param store The Store instance.
     * @param validate A suspend function that performs the validation.
     *
     * @throws RouteNotFoundException if the specified route does not exist.
     *
     * Example usage:
     * ```
     * navigateWithValidation("checkout", mapOf("cartId" to "456"), store) { store, params ->
     *     val cartState = store.selectState<CartState>().value
     *     cartState.items.isNotEmpty()
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
        scope.launch {
            dispatch(NavigationAction.SetLoading(true))
            try {
                if (validate(store, sanitizedParams)) {
                    navigate(finalRoute, sanitizedParams)
                } else {
                    navigateBack()
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

    /**
     * Sanitizes a parameter value to prevent injection attacks.
     *
     * @param value The value to sanitize.
     * @return The sanitized value.
     * @throws IllegalArgumentException if the value type is not supported.
     */
    private fun sanitizeParam(value: Any): Any {
        return when (value) {
            is String -> value.replace(Regex("[^A-Za-z0-9_-]"), "")
            is Number -> value
            is Boolean -> value
            else -> throw IllegalArgumentException("Unsupported parameter type: ${value::class.simpleName}")
        }
    }

    /**
     * Extracts path parameters from a route.
     *
     * @param path The route path to extract parameters from.
     * @return A pair of the matching route and extracted parameters.
     * @throws IllegalArgumentException if no matching route is found.
     */
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

    /**
     * Extracts query parameters from a query string.
     *
     * @param query The query string to extract parameters from.
     * @return A map of extracted query parameters.
     */
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

    override suspend fun invoke(action: ModuleAction, dispatch: Dispatch) {
        //super.dispatch.invoke(action)
    }
}

/**
 * Module responsible for managing navigation state and logic.
 *
 * @property coroutineContext The coroutine context for asynchronous operations.
 * @property initialScreen The initial screen to display when the app starts.
 * @property navigationNodes The available navigation nodes (screens or screen groups) in the app.
 */
class NavigationModule(
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
    private val initialScreen: Screen,
    private vararg val navigationNodes: NavigationNode
) : Module<NavigationState, NavigationAction> {

    /**
     * The initial state of the navigation system.
     */
    override val initialState: NavigationState by lazy {
        val availableScreens = mutableMapOf<String, Screen>()

        navigationNodes.forEach { node ->
            when (node) {
                is Screen -> availableScreens[node.route] = node
                is ScreenGroup -> node.screens.forEach { screen ->
                    availableScreens[screen.route] = screen
                }
            }
        }

        availableScreens[initialScreen.route] = initialScreen

        NavigationState(
            currentScreen = initialScreen,
            backStack = listOf(Pair(initialScreen, emptyMap())),
            availableScreens = availableScreens
        )
    }

    /**
     * The reducer function for handling navigation actions and updating the state.
     */
    override val reducer: (NavigationState, NavigationAction) -> NavigationState = { state, action ->
        when (action) {
            is NavigationAction.Navigate -> {
                // Handle single top
                if (action.isSingleTop && state.currentScreen.route == action.route) {
                    state.copy(params = action.params)
                } else {
                    var newBackStack = state.backStack

                    // Handle popUpTo
                    if (action.popUpTo != null) {
                        val popIndex = newBackStack.indexOfLast { it.first.route == action.popUpTo }
                        if (popIndex != -1) {
                            newBackStack = if (action.inclusive) {
                                newBackStack.subList(0, popIndex)
                            } else {
                                newBackStack.subList(0, popIndex + 1)
                            }
                        }
                    }

                    val targetScreen = if (action.replaceWith != null) {
                        state.availableScreens[action.replaceWith]
                            ?: error("No screen found for route: ${action.replaceWith}")
                    } else {
                        state.availableScreens[action.route]
                            ?: error("No screen found for route: ${action.route}")
                    }

                    state.copy(
                        currentScreen = targetScreen,
                        backStack = newBackStack + Pair(targetScreen, action.params),
                        params = action.params
                    )
                }
            }

            is NavigationAction.PopUpTo -> {
                val targetIndex = state.backStack.indexOfLast { it.first.route == action.route }
                if (targetIndex != -1) {
                    var newBackStack = if (action.inclusive) {
                        state.backStack.subList(0, targetIndex)
                    } else {
                        state.backStack.subList(0, targetIndex + 1)
                    }

                    var currentScreen = newBackStack.lastOrNull()?.first ?: state.currentScreen
                    var params = newBackStack.lastOrNull()?.second ?: emptyMap()

                    if (action.replaceWith != null) {
                        val replaceScreen = state.availableScreens[action.replaceWith]
                            ?: error("No screen found for route: ${action.replaceWith}")
                        currentScreen = replaceScreen
                        params = action.replaceParams
                        newBackStack = newBackStack.dropLast(1) + Pair(replaceScreen, action.replaceParams)
                    }

                    state.copy(
                        currentScreen = currentScreen,
                        backStack = newBackStack,
                        params = params
                    )
                } else {
                    state
                }
            }

            is NavigationAction.Back -> {
                if (state.backStack.size > 1) {
                    val newBackStack = state.backStack.dropLast(1)
                    state.copy(
                        currentScreen = newBackStack.last().first,
                        backStack = newBackStack,
                        params = newBackStack.last().second
                    )
                } else {
                    state
                }
            }

            is NavigationAction.ClearBackStack -> {
                state.copy(
                    backStack = listOf(state.backStack.first())
                )
            }

            is NavigationAction.Replace -> {
                val newScreen = state.availableScreens[action.route]
                    ?: error("No screen found for route: ${action.route}")
                state.copy(
                    currentScreen = newScreen,
                    backStack = state.backStack.dropLast(1) + Pair(newScreen, action.params),
                    params = action.params
                )
            }

            is NavigationAction.SetLoading -> {
                state.copy(isLoading = action.isLoading)
            }
        }
    }

    /**
     * The logic instance for handling navigation actions.
     */
    override val logic = NavigationLogic(coroutineContext, initialState.availableScreens)
}

// Extension functions for Store

/**
 * Navigates to the specified route with optional parameters and configuration.
 *
 * @param route The destination route.
 * @param params Optional parameters for the destination screen.
 * @param config Optional configuration for the navigation action.
 *
 * Example usage:
 * ```
 * store.navigate("profile", mapOf("userId" to "123")) {
 *     popUpTo("home")
 *     singleTop()
 * }
 * ```
 */
fun Store.navigate(
    route: String,
    params: Map<String, Any> = emptyMap(),
    config: (NavigationBuilder.() -> Unit)? = null
) {
    selectLogic<NavigationLogic>().navigate(route, params, config)
}

/**
 * Navigates to the specified route with optional parameters and configuration.
 *
 * @param route The destination route.
 * @param params Optional parameters for the destination screen.
 * @param config Optional configuration for the navigation action.
 *
 * Example usage:
 * ```
 * store.navigate("profile", mapOf("userId" to "123")) {
 *     popUpTo("home")
 *     singleTop()
 * }
 * ```
 */
fun Store.navigate(
    screen: Screen,
    params: Map<String, Any> = emptyMap(),
    config: (NavigationBuilder.() -> Unit)? = null
) {
    navigate(screen.route, params, config)
}

/**
 * Pops the back stack up to a specified route with optional configuration.
 *
 * @param route The route to pop up to.
 * @param inclusive Whether to include the specified route in the pop operation.
 * @param config Optional configuration for the pop up to action.
 *
 * Example usage:
 * ```
 * store.popUpTo("home", inclusive = true) {
 *     replaceWith("dashboard", mapOf("refresh" to true))
 * }
 * ```
 */
fun Store.popUpTo(
    route: String,
    inclusive: Boolean = false,
    config: (PopUpToBuilder.() -> Unit)? = null
) {
    selectLogic<NavigationLogic>().popUpTo(route, inclusive, config)
}

/**
 * Navigates back to the previous screen in the back stack.
 *
 * Example usage:
 * ```
 * store.navigateBack()
 * ```
 */
fun Store.navigateBack() {
    selectLogic<NavigationLogic>().navigateBack()
}

/**
 * Clears the entire back stack, leaving only the initial screen.
 *
 * Example usage:
 * ```
 * store.clearBackStack()
 * ```
 */
fun Store.clearBackStack() {
    selectLogic<NavigationLogic>().clearBackStack()
}

/**
 * Replaces the current screen with a new one.
 *
 * @param route The route of the new screen.
 * @param params Parameters for the new screen.
 *
 * Example usage:
 * ```
 * store.replaceWith("profile", mapOf("userId" to "123"))
 * ```
 */
fun Store.replaceWith(route: String, params: Map<String, Any> = emptyMap()) {
    selectLogic<NavigationLogic>().replaceWith(route, params)
}

/**
 * Navigates to a route with validation.
 *
 * @param route The destination route.
 * @param params Parameters for the destination screen.
 * @param validate A suspend function that performs the validation.
 *
 * Example usage:
 * ```
 * store.navigateWithValidation("checkout", mapOf("cartId" to "456")) { store, params ->
 *     val cartState = store.selectState<CartState>().value
 *     cartState.items.isNotEmpty()
 * }
 * ```
 */
fun Store.navigateWithValidation(
    route: String,
    params: Map<String, Any> = emptyMap(),
    validate: suspend (Store, Map<String, Any>) -> Boolean
) {
    selectLogic<NavigationLogic>().navigateWithValidation(route, params, this, validate)
}

/**
 * Navigates to a screen with validation.
 *
 * @param screen The destination screen.
 * @param params Parameters for the destination screen.
 * @param validate A suspend function that performs the validation.
 *
 * Example usage:
 * ```
 * store.navigateWithValidation(CheckoutScreen, mapOf("cartId" to "456")) { store, params ->
 *     val cartState = store.selectState<CartState>().value
 *     cartState.items.isNotEmpty()
 * }
 * ```
 */
fun Store.navigateWithValidation(
    screen: Screen,
    params: Map<String, Any> = emptyMap(),
    validate: suspend (Store, Map<String, Any>) -> Boolean
) {
    navigateWithValidation(screen.route, params, validate)
}

/**
 * Composable function that sets up the navigation host for the application.
 *
 * @param store The central store managing the application state.
 * @param isAuthenticated Whether the user is currently authenticated.
 * @param onAuthenticationRequired Callback function when authentication is required.
 * @param loadingContent Composable function to display while loading.
 *
 * Example usage:
 * ```
 * NavHost(
 *     store = store,
 *     isAuthenticated = viewModel.isAuthenticated,
 *     onAuthenticationRequired = { viewModel.showLoginPrompt() },
 *     loadingContent = { CircularProgressIndicator() }
 * )
 * ```
 */
@Composable
fun NavHost(
    store: Store,
    isAuthenticated: Boolean,
    onAuthenticationRequired: () -> Unit = {},
    loadingContent: @Composable () -> Unit = { /* Default loading UI */ }
) {
    val navigationState by store.selectState<NavigationState>().collectAsState(Dispatchers.Main)

    AnimatedContent(
        modifier = Modifier.testTag("AnimatedContent"),
        targetState = navigationState.currentScreen,
        transitionSpec = {
            getTransitionAnimation(
                initialState.enterTransition,
                targetState.enterTransition
            )
        }
    ) { screen ->
        when {
            navigationState.isLoading -> {
                loadingContent()
            }

            screen.requiresAuth && !isAuthenticated -> {
                onAuthenticationRequired()
            }

            else -> {
                screen.Content(navigationState.params)
            }
        }
    }
}

/**
 * Determines the appropriate transition animation based on the exit and enter transitions.
 *
 * @param exitTransition The exit transition of the current screen.
 * @param enterTransition The enter transition of the target screen.
 * @return The ContentTransform to be used for the transition.
 */
private fun getTransitionAnimation(
    exitTransition: NavTransition,
    enterTransition: NavTransition
): ContentTransform {
    val enter = when (enterTransition) {
        is NavTransition.Slide -> slideInHorizontally { fullWidth -> fullWidth } + fadeIn()
        is NavTransition.Fade -> fadeIn()
        is NavTransition.Scale -> scaleIn() + fadeIn()
        is NavTransition.Custom -> enterTransition.enter
        NavTransition.None -> fadeIn() // Default to fade for None
    }

    val exit = when (exitTransition) {
        is NavTransition.Slide -> slideOutHorizontally { fullWidth -> -fullWidth } + fadeOut()
        is NavTransition.Fade -> fadeOut()
        is NavTransition.Scale -> scaleOut() + fadeOut()
        is NavTransition.Custom -> exitTransition.exit
        NavTransition.None -> fadeOut() // Default to fade for None
    }

    return enter togetherWith exit
}