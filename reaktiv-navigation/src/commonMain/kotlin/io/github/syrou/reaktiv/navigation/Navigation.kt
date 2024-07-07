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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.collections.set

// Enum for transition animations
enum class NavTransition {
    NONE, SLIDE, FADE, SCALE
}

data class DeepLink(val route: String, val queryParams: Map<String, Any>)

// Sealed interface for different screen types
interface Screen {
    val route: String
    val titleResourceId: Int
    val enterTransition: NavTransition
    val exitTransition: NavTransition
    val requiresAuth: Boolean

    @Composable
    fun Content(params: Map<String, Any>)
}

// Abstract class to represent a group of related screens
abstract class ScreenGroup {
    private val _screens = mutableListOf<Screen>()
    val screens: List<Screen> get() = _screens.toList()

    protected fun addScreens(vararg screens: Screen) {
        _screens.addAll(screens)
    }
}

// Navigation controller to manage navigation state
class NavController(val startScreen: Screen) {
    private val _currentScreen = MutableStateFlow<Pair<Screen, Map<String, Any>>>(Pair(startScreen, emptyMap()))
    val currentScreen: StateFlow<Pair<Screen, Map<String, Any>>> = _currentScreen

    private val _backStack = mutableListOf<Pair<Screen, Map<String, Any>>>()
    private val canGoBack: Boolean get() = _backStack.size > 1

    internal fun navigate(screen: Screen, params: Map<String, Any> = emptyMap()) {
        _backStack.add(screen to params)
        _currentScreen.value = screen to params
    }

    internal fun navigateBack(): Boolean {
        if (canGoBack) {
            _backStack.removeAt(_backStack.lastIndex)
            _currentScreen.value = _backStack.last()
            return true
        }
        return false
    }

    fun replaceAll(screen: Screen, params: Map<String, Any> = emptyMap()) {
        _backStack.clear()
        _backStack.add(screen to params)
        _currentScreen.value = screen to params
    }

    fun clear() {
        _backStack.clear()
        _currentScreen.value = startScreen to emptyMap()
    }
}

// Navigation graph to define the app's navigation structure
class NavGraph(private val navController: NavController) {
    private val routes = mutableMapOf<String, Screen>()

    fun addScreen(screen: Screen) {
        routes[screen.route] = screen
    }

    fun addScreenGroup(group: ScreenGroup) {
        group.screens.forEach { addScreen(it) }
    }

    fun clear() {
        navController.clear()
        routes.clear()
    }

    fun navigate(route: String) {
        val (matchingRoute, extractedParams) = extractParameters(route)
        val screen = routes[matchingRoute] ?: error("No screen found for route: $matchingRoute")
        navController.navigate(screen, extractedParams)
    }

    fun back(){
        navController.navigateBack()
    }

    private fun extractParameters(route: String): Pair<String, Map<String, Any>> {
        val parts = route.split("/")
        val params = mutableMapOf<String, Any>()

        // Find matching route
        val matchingRoute = routes.keys.find { routeKey ->
            val routeParts = routeKey.split("/")
            if (routeParts.size != parts.size) return@find false

            routeParts.zip(parts).all { (routePart, actualPart) ->
                when {
                    routePart == actualPart -> true
                    routePart.startsWith("{") && routePart.endsWith("}") -> true
                    else -> false
                }
            }
        } ?: throw IllegalArgumentException("No matching route found for: $route")

        // Validate route format and extract parameters
        val routeParts = matchingRoute.split("/")
        routeParts.zip(parts).forEach { (routePart, actualPart) ->
            when {
                routePart.startsWith("{") && routePart.endsWith("}") -> {
                    val paramName = routePart.substring(1, routePart.length - 1)
                    if (paramName.isEmpty()) {
                        throw IllegalArgumentException("Invalid route format: $matchingRoute. Dynamic segment name cannot be empty.")
                    }
                    // Extract content between braces, if present
                    val paramValue = when {
                        actualPart.startsWith("{") && actualPart.endsWith("}") -> {
                            actualPart.substring(1, actualPart.length - 1)
                        }

                        actualPart.contains("{") || actualPart.contains("}") -> {
                            throw IllegalArgumentException("Invalid parameter format: $actualPart. Unmatched braces.")
                        }

                        else -> actualPart
                    }
                    params[paramName] = paramValue
                }

                routePart != actualPart -> {
                    throw IllegalArgumentException("Route mismatch: Expected '$routePart', but got '$actualPart'")
                }
            }
        }

        return Pair(matchingRoute, params)
    }

    fun handleDeepLink(url: String, navigationPrefix: String = "navigation/") {
        println("TESTOR - DEEP LINK RECIEVED: $url")
        extractRouteFromDeepLink(url, navigationPrefix)?.let { deepLink ->
            navigate(deepLink)
        }
    }

    private fun extractRouteFromDeepLink(url: String, navigationPrefix: String): String? {
        return url.substringAfter(navigationPrefix, "")
            .takeIf { it.isNotEmpty() }
    }
}

// Composable function to handle navigation
@Composable
fun NavHost(
    navController: NavController,
    isAuthenticated: Boolean,
    onAuthenticationRequired: () -> Unit = {},
) {
    val currentScreen by navController.currentScreen.collectAsState()

    AnimatedContent(
        modifier = Modifier.testTag("AnimatedContent"),
        targetState = currentScreen,
        transitionSpec = {
            getTransitionAnimation(
                initialState.first.enterTransition,
                targetState.first.enterTransition
            )
        }
    ) { (screen, params) ->
        if (screen.requiresAuth && !isAuthenticated) {
            onAuthenticationRequired()
        } else {
            screen.Content(params)
        }
    }
}

private fun getTransitionAnimation(
    initialTransition: NavTransition?,
    targetTransition: NavTransition?
): ContentTransform {
    return when {
        initialTransition == null && targetTransition != null -> fadeIn() togetherWith fadeOut()
        initialTransition != null && targetTransition == null -> fadeIn() togetherWith fadeOut()
        targetTransition == NavTransition.SLIDE -> slideInHorizontally() + fadeIn() togetherWith slideOutHorizontally() + fadeOut()
        targetTransition == NavTransition.FADE -> fadeIn() togetherWith fadeOut()
        targetTransition == NavTransition.SCALE -> scaleIn() + fadeIn() togetherWith scaleOut() + fadeOut()
        else -> fadeIn() togetherWith fadeOut()
    }
}