package io.github.syrou.reaktiv.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
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
import kotlinx.coroutines.Dispatchers

interface NavigationNode

interface Screen : NavigationNode {
    val route: String
    val titleResourceId: Int
    val enterTransition: NavTransition
    val exitTransition: NavTransition
    val requiresAuth: Boolean

    @Composable
    fun Content(params: Map<String, Any>)
}

open class ScreenGroup(
    val screens: List<Screen>
) : NavigationNode {
    constructor(vararg screens: Screen) : this(screens.toList())
}

enum class NavTransition {
    NONE, SLIDE, FADE, SCALE
}

data class NavigationState(
    val currentScreen: Screen,
    val backStack: List<Pair<Screen, Map<String, Any>>>,
    val params: Map<String, Any> = emptyMap(),
    val availableScreens: Map<String, Screen> = emptyMap(),
    val isLoading: Boolean = false
) : ModuleState

sealed class NavigationAction : ModuleAction(NavigationModule::class) {
    data class Navigate(val route: String, val params: Map<String, Any> = emptyMap()) : NavigationAction()
    data object Back : NavigationAction()
    data class PopTo(val route: String, val inclusive: Boolean = false) : NavigationAction()
    data object ClearBackStack : NavigationAction()
    data class SetLoading(val isLoading: Boolean) : NavigationAction()
}

class NavigationLogic(
    private val availableScreens: Map<String, Screen>
) : ModuleLogic<NavigationAction>() {
    fun navigate(route: String) {
        val (finalRoute, params) = extractRouteAndParams(route)
        dispatch(NavigationAction.Navigate(finalRoute, params))
    }

    suspend fun navigateWithValidation(
        route: String,
        validate: suspend (Map<String, Any>) -> Boolean
    ) {
        val (finalRoute, params) = extractRouteAndParams(route)
        println("TESTOR - START finalRoute: $finalRoute, params: $params")
        dispatch(NavigationAction.SetLoading(true))
        try {
            if (validate(params)) {
                println("TESTOR - VALIDTED YES! finalRoute: $finalRoute, params: $params")
                dispatch(NavigationAction.Navigate(finalRoute, params))
            }else{
                println("TESTOR - VALIDTED YES! finalRoute: $finalRoute, params: $params")
            }
        } finally {
            dispatch(NavigationAction.SetLoading(false))
        }
    }

    fun navigateBack() {
        dispatch(NavigationAction.Back)
    }

    fun popTo(route: String, inclusive: Boolean = false) {
        dispatch(NavigationAction.PopTo(route, inclusive))
    }

    fun clearBackStack() {
        dispatch(NavigationAction.ClearBackStack)
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

    override suspend fun invoke(action: ModuleAction, dispatch: Dispatch) {
        //super.dispatch.invoke(action)
    }
}

class NavigationModule(
    private val initialScreen: Screen,
    private val loadingScreen: Screen,
    private vararg val navigationNodes: NavigationNode
) : Module<NavigationState, NavigationAction> {

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
        availableScreens[loadingScreen.route] = loadingScreen

        NavigationState(
            currentScreen = initialScreen,
            backStack = listOf(Pair(initialScreen, emptyMap())),
            availableScreens = availableScreens
        )
    }

    override val reducer: (NavigationState, NavigationAction) -> NavigationState = { state, action ->
        when (action) {
            is NavigationAction.Navigate -> {
                val newScreen = state.availableScreens[action.route]
                    ?: error("No screen found for route: ${action.route}")
                state.copy(
                    currentScreen = newScreen,
                    backStack = state.backStack + Pair(newScreen, action.params)
                )
            }

            is NavigationAction.Back -> {
                if (state.backStack.size > 1) {
                    val newBackStack = state.backStack.dropLast(1)
                    state.copy(
                        currentScreen = newBackStack.last().first,
                        backStack = newBackStack
                    )
                } else {
                    state
                }
            }

            is NavigationAction.PopTo -> {
                val targetIndex = state.backStack.indexOfLast { it.first.route == action.route }
                if (targetIndex != -1) {
                    val newBackStack = if (action.inclusive) {
                        state.backStack.subList(0, targetIndex)
                    } else {
                        state.backStack.subList(0, targetIndex + 1)
                    }
                    state.copy(
                        currentScreen = newBackStack.lastOrNull()?.first ?: state.currentScreen,
                        backStack = newBackStack,
                        params = emptyMap()
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

            is NavigationAction.SetLoading -> {
                /*state.copy(
                    currentScreen = if (action.isLoading) loadingScreen else state.backStack.last().first,
                    isLoading = action.isLoading
                )*/
                state
            }
        }
    }

    override val logic = NavigationLogic(initialState.availableScreens)
}

// Extension functions for Store
fun Store.navigate(route: String) {
    selectLogic<NavigationLogic>().navigate(route)
}

fun Store.navigate(screen: Screen) {
    selectLogic<NavigationLogic>().navigate(screen.route)
}

suspend fun Store.navigateWithValidation(
    route: String,
    validate: suspend (Store, Map<String, Any>) -> Boolean
) {
    selectLogic<NavigationLogic>().navigateWithValidation(route) { params ->
        validate(this, params)
    }
}

suspend fun Store.navigateWithValidation(
    screen: Screen,
    validate: suspend (Store, Map<String, Any>) -> Boolean
) {
    navigateWithValidation(screen.route, validate)
}

fun Store.navigateBack() {
    selectLogic<NavigationLogic>().navigateBack()
}

fun Store.popTo(route: String, inclusive: Boolean = false) {
    selectLogic<NavigationLogic>().popTo(route, inclusive)
}

fun Store.clearBackStack() {
    selectLogic<NavigationLogic>().clearBackStack()
}

@Composable
fun NavHost(
    store: Store,
    isAuthenticated: Boolean,
    onAuthenticationRequired: () -> Unit = {}
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
        if (screen.requiresAuth && !isAuthenticated) {
            onAuthenticationRequired()
        } else {
            screen.Content(navigationState.params)
        }
    }
}

private fun getTransitionAnimation(
    initialTransition: NavTransition,
    targetTransition: NavTransition
): ContentTransform {
    return when (targetTransition) {
        NavTransition.SLIDE -> slideInHorizontally { fullWidth -> fullWidth } + fadeIn() togetherWith
                slideOutHorizontally { fullWidth -> -fullWidth } + fadeOut()

        NavTransition.FADE -> fadeIn() togetherWith fadeOut()
        NavTransition.SCALE -> scaleIn() + fadeIn() togetherWith scaleOut() + fadeOut()
        NavTransition.NONE -> fadeIn() togetherWith fadeOut() // Default to fade for NONE
    }
}