package io.github.syrou.reaktiv.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.core.util.CustomTypeRegistrar
import io.github.syrou.reaktiv.navigation.AnimationLifecycleState.Entered
import io.github.syrou.reaktiv.navigation.AnimationLifecycleState.Entering
import io.github.syrou.reaktiv.navigation.AnimationLifecycleState.Exited
import io.github.syrou.reaktiv.navigation.AnimationLifecycleState.Exiting
import io.github.syrou.reaktiv.navigation.AnimationLifecycleState.Idle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

class RouteNotFoundException(route: String) : Exception("No screen found for route: $route")
object ClearingBackStackWithOtherOperations : Exception(
    "You can not combine clearing backstack with replaceWith or popUpTo"
)

typealias TitleResource = @Composable (() -> String)
typealias ActionResource = @Composable (() -> Unit)

/**
 * Represents a navigation node in the application's navigation structure.
 */
interface NavigationNode

/**
 * Represents a screen in the application's navigation structure.
 *
 * @property route The unique identifier for this screen.
 * @property titleResource The resource ID for the screen's title.
 * @property enterTransition The transition animation when entering this screen.
 * @property exitTransition The transition animation when exiting this screen.
 * @property requiresAuth Whether this screen requires authentication.
 *
 * Example implementation:
 * ```
 * object HomeScreen : Screen {
 *     override val route = "home"
 *     override val titleResourceId = R.string.home_title
 *     override val enterTransition = NavTransition.Fade
 *     override val exitTransition = NavTransition.Fade
 *     override val requiresAuth = false
 *
 *     @Composable
 *     override fun Content(params: Map<String, Any>) {
 *         Text("Welcome to the Home Screen")
 *     }
 * }
 * ```
 */
interface Screen : NavigationNode {
    val route: String
    val titleResource: TitleResource? get() = null
    val actionResource: ActionResource? get() = null
    val enterTransition: NavTransition
    val exitTransition: NavTransition
    val popEnterTransition: NavTransition? get() = null
    val popExitTransition: NavTransition? get() = null
    val requiresAuth: Boolean

    @Composable
    fun Content(params: Map<String, Any>)
}


/**
 * Represents a group of screens in the application's navigation structure.
 *
 * @property screens The list of screens in this group.
 *
 * Example usage:
 * ```
 * val settingsGroup = ScreenGroup(
 *     ProfileScreen,
 *     PreferencesScreen,
 *     PrivacyScreen
 * )
 * ```
 */
open class ScreenGroup(
    val screens: List<Screen>
) : NavigationNode {
    constructor(vararg screens: Screen) : this(screens.toList())
}

/**
 * Defines the transition animations for navigation.
 *
 * Example usage:
 * ```
 * val fadeTransition = NavTransition.Fade
 * val customTransition = NavTransition.Custom(
 *     enter = fadeIn() + slideInHorizontally(),
 *     exit = fadeOut() + slideOutHorizontally()
 * )
 * ```
 */
sealed class NavTransition(open val durationMillis: Int = DEFAULT_ANIMATION_DURATION) {
    data object None : NavTransition()
    data object SlideInRight : NavTransition()
    data object SlideOutRight : NavTransition()
    data object SlideInLeft : NavTransition()
    data object SlideOutLeft : NavTransition()
    data object SlideUpBottom : NavTransition()
    data object SlideOutBottom : NavTransition()
    data object Hold : NavTransition()
    data object Fade : NavTransition()
    data object Scale : NavTransition()
    data class CustomEnterTransition(val enter: EnterTransition, override val durationMillis: Int) :
        NavTransition(durationMillis)

    data class CustomExitTransition(val exit: ExitTransition, override val durationMillis: Int) :
        NavTransition(durationMillis)

    companion object {
        const val DEFAULT_ANIMATION_DURATION = 300
    }
}


/**
 * Represents the different states of the navigation animation lifecycle.
 *
 * @property Idle No animation is currently playing. This is the default state and the state after all animations have completed.
 * @property Entering The enter animation for a new screen is starting and in progress.
 * @property Entered The enter animation has completed, and the new screen is fully visible.
 * @property Exiting The exit animation for the current screen is starting and in progress.
 * @property Exited The exit animation has completed, and the previous screen is no longer visible.
 */
@Serializable
sealed class AnimationLifecycleState {
    @Serializable
    data class Idle(val currentRoute: String? = null) : AnimationLifecycleState()

    @Serializable
    data class Entering(val enteringRoute: String) : AnimationLifecycleState()

    @Serializable
    data class Entered(val enteredRoute: String) : AnimationLifecycleState()

    @Serializable
    data class Exiting(val exitingRoute: String, val enteringRoute: String) : AnimationLifecycleState()

    @Serializable
    data class Exited(val exitedRoute: String) : AnimationLifecycleState()
}

/**
 * Represents the state of the navigation system.
 *
 * @property currentScreen The currently active screen.
 * @property backStack The stack of screens representing the navigation history.
 * @property availableScreens A map of all available screens in the application.
 * @property isLoading Indicates whether a navigation action is in progress.
 */
@Serializable
data class NavigationState(
    val currentScreen: Screen,
    val backStack: List<NavigationEntry>,
    val availableScreens: Map<String, Screen> = emptyMap(),
    val clearedBackStackWithNavigate: Boolean = false,
    val isLoading: Boolean = false,
    val animationLifecycleState: AnimationLifecycleState = AnimationLifecycleState.Idle()
) : ModuleState

@Serializable
data class NavigationEntry(
    val screen: Screen,
    val params: StringAnyMap
)

/**
 * Builder class for configuring navigation actions.
 *
 * @property route The destination route.
 * @property params The parameters to pass to the destination.
 */
class NavigationBuilder(
    var route: String,
    var params: Map<String, Any> = emptyMap()
) {
    private var popUpTo: String? = null
    private var inclusive: Boolean = false
    private var replaceWith: String? = null
    private var clearBackStack: Boolean = false
    private var forwardParams: Boolean = false

    /**
     * Configures the navigation action to pop up to a specific destination.
     *
     * @param route The route to pop up to.
     * @param inclusive Whether to include the specified route in the pop operation.
     * @return The NavigationBuilder instance for chaining.
     */
    fun popUpTo(route: String, inclusive: Boolean = false): NavigationBuilder {
        this.popUpTo = route
        this.inclusive = inclusive
        return this
    }

    /**
     * Configures the navigation action to replace the current destination.
     *
     * @param route The route to replace with.
     * @return The NavigationBuilder instance for chaining.
     */
    fun replaceWith(route: String): NavigationBuilder {
        this.replaceWith = route
        return this
    }

    /**
     * Configures the navigation action to add on the previous params when navigating.
     * This is useful for params that needs to survive a long navigation chain.
     * Will always take the latest params and replace any previous params if they share the same key.
     *
     * @return The NavigationBuilder instance for chaining.
     */
    fun forwardParams(): NavigationBuilder {
        this.forwardParams = true
        return this
    }

    /**
     * Configures the navigation to clear the backstack after navigation
     *
     * @return The NavigationBuilder instance for chaining.
     */
    fun clearBackStack(): NavigationBuilder {
        this.clearBackStack = true
        return this
    }

    internal fun build(): NavigationAction.Navigate {
        return NavigationAction.Navigate(
            route = route,
            params = params,
            popUpTo = popUpTo,
            inclusive = inclusive,
            replaceWith = replaceWith,
            clearBackStack = clearBackStack,
            forwardParams = forwardParams
        )
    }
}

class ClearBackStackBuilder(
    private var root: String? = null,
    private var params: StringAnyMap = emptyMap()
) {

    fun setRoot(route: String, params: StringAnyMap = emptyMap()) {
        this.root = route
        this.params = params
    }

    internal fun build(): NavigationAction.ClearBackStack {
        return NavigationAction.ClearBackStack(
            root = root,
            params = params
        )
    }
}

class PopUpToBuilder(
    var route: String,
    var inclusive: Boolean = false
) {
    private var replaceWith: String? = null
    private var replaceParams: Map<String, Any> = emptyMap()

    /**
     * Configures the popUpTo action to replace the current destination.
     *
     * @param route The route to replace with.
     * @return The PopUpToBuilder instance for chaining.
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
 * The main module for handling navigation in the Reaktiv architecture.
 *
 * @property coroutineScope The coroutine context for this module.
 * @property rootScreen The initial screen to display when the app starts.
 * @property screens The list of screens and screen groups in the application.
 */
class NavigationModule private constructor(
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val rootScreen: Screen,
    private val screens: List<Pair<KClass<out Screen>, NavigationNode>>,
    private val addRootScreenToBackStack: Boolean
) : Module<NavigationState, NavigationAction>, CustomTypeRegistrar {

    override val initialState: NavigationState by lazy {
        val availableScreens = mutableMapOf<String, Screen>()

        screens.forEach { node ->
            when (node.second) {
                is Screen -> availableScreens[(node.second as Screen).route] = node.second as Screen
                is ScreenGroup -> (node.second as ScreenGroup).screens.forEach { screen ->
                    availableScreens[screen.route] = screen
                }
            }
        }
        availableScreens[rootScreen.route] = rootScreen
        NavigationState(
            currentScreen = rootScreen,
            backStack = if (addRootScreenToBackStack) listOf(
                NavigationEntry(
                    screen = rootScreen,
                    params = emptyMap()
                )
            ) else emptyList(),
            availableScreens = availableScreens
        )
    }

    @OptIn(InternalSerializationApi::class)
    override fun registerAdditionalSerializers(builder: SerializersModuleBuilder) {
        builder.polymorphic(Screen::class) {
            //Handle persistence of the initial screen regardless if it is added to available screen or not
            if (screens.count { it.second == rootScreen } <= 0) {
                val initialScreen = rootScreen::class
                subclass(initialScreen as KClass<Screen>, initialScreen.serializer())
            }
            screens.forEach { (screenClass, screen) ->
                @Suppress("UNCHECKED_CAST")
                subclass(screenClass as KClass<Screen>, screenClass.serializer())
            }
        }
    }

    override val reducer: (NavigationState, NavigationAction) -> NavigationState = { state, action ->
        when (action) {
            is NavigationAction.Navigate -> {

                var newBackStack = if (action.clearBackStack) listOf() else state.backStack
                // Handle popUpTo
                if (action.popUpTo != null) {
                    val popIndex = newBackStack.indexOfLast { it.screen.route == action.popUpTo }
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

                val params: StringAnyMap = if (action.forwardParams) {
                    val previousParams = newBackStack.lastOrNull()?.params ?: emptyMap()
                    previousParams.plus(action.params)
                } else {
                    action.params
                }

                val newEntry = NavigationEntry(
                    screen = targetScreen,
                    params = params
                )

                val currentScreen = newBackStack.lastOrNull()
                if (currentScreen?.screen?.route == targetScreen.route) {
                    state
                } else {
                    state.copy(
                        currentScreen = targetScreen,
                        backStack = newBackStack + newEntry,
                        clearedBackStackWithNavigate = action.clearBackStack
                    )
                }
            }

            is NavigationAction.PopUpTo -> {
                val targetIndex = state.backStack.indexOfLast { it.screen.route == action.route }
                if (targetIndex != -1) {
                    var newBackStack = if (action.inclusive) {
                        state.backStack.subList(0, targetIndex)
                    } else {
                        state.backStack.subList(0, targetIndex + 1)
                    }


                    var currentScreen = newBackStack.lastOrNull()?.screen ?: state.currentScreen

                    if (action.replaceWith != null) {
                        val replaceScreen = state.availableScreens[action.replaceWith]
                            ?: error("No screen found for route: ${action.replaceWith}")
                        currentScreen = replaceScreen
                        val newEntry = NavigationEntry(
                            screen = replaceScreen,
                            params = action.replaceParams
                        )
                        newBackStack = newBackStack.dropLast(1) + newEntry
                    }

                    state.copy(
                        currentScreen = currentScreen,
                        backStack = newBackStack,
                    )
                } else {
                    state
                }
            }

            is NavigationAction.Back -> {
                if (state.backStack.size > 1) {
                    val newBackStack = state.backStack.dropLast(1)
                    state.copy(
                        currentScreen = newBackStack.last().screen,
                        backStack = newBackStack,
                    )
                } else {
                    state
                }
            }

            is NavigationAction.ClearBackStack -> {
                if (action.root != null) {
                    val currentScreen =
                        state.availableScreens[action.root] ?: error("No screen found for route: ${action.root}")
                    state.copy(
                        currentScreen = currentScreen,
                        backStack = listOf(NavigationEntry(currentScreen, action.params))
                    )
                } else {
                    state.copy(backStack = listOf())
                }
            }

            is NavigationAction.Replace -> {
                val newScreen = state.availableScreens[action.route]
                    ?: error("No screen found for route: ${action.route}")
                val newEntry = NavigationEntry(
                    screen = newScreen,
                    params = action.params
                )
                state.copy(
                    currentScreen = newScreen,
                    backStack = state.backStack.dropLast(1) + newEntry,
                )
            }

            is NavigationAction.SetLoading -> {
                state.copy(isLoading = action.isLoading)
            }

            is NavigationAction.UpdateAnimationState -> {
                state.copy(animationLifecycleState = action.state)
            }
        }
    }

    override val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic<NavigationAction> = { storeAccessor ->
        NavigationLogic(coroutineScope, initialState.availableScreens, storeAccessor)
    }

    /**
     * Builder class for creating a NavigationModule instance.
     */
    class Builder {
        var startScreen: Screen? = null
        var _addInitialScreenToBackStack: Boolean = false
        val screens = mutableListOf<Pair<KClass<out Screen>, Screen>>()
        private var coroutineContext = CoroutineScope(SupervisorJob() + Dispatchers.Default)


        /**
         * Sets the initial screen for the navigation.
         *
         * @param screen The initial screen to display.
         */
        inline fun <reified S : Screen> setInitialScreen(
            screen: S,
            addInitialScreenToAvailableScreens: Boolean = false,
            addInitialScreenToBackStack: Boolean = false
        ) {
            startScreen = screen
            // Ensure the initial screen is also in the screens list
            _addInitialScreenToBackStack = addInitialScreenToBackStack
            if (addInitialScreenToAvailableScreens) {
                addScreen(screen)
            }
        }

        /**
         * Adds a screen to the navigation module.
         *
         * @param screen The screen to add.
         */
        inline fun <reified S : Screen> addScreen(screen: S) {
            screens.add(S::class to screen)
        }

        /**
         * Adds a group of screens to the navigation module.
         *
         * @param screenGroup The group of screens to add.
         */
        fun addScreenGroup(screenGroup: ScreenGroup) {
            screenGroup.screens.forEach { screen ->
                screens.add(screen::class as KClass<out Screen> to screen)
            }
        }

        fun coroutineContext(dispatcher: CoroutineDispatcher) {
            coroutineContext = CoroutineScope(dispatcher)
        }

        fun build(): NavigationModule {
            requireNotNull(startScreen) { "Initial screen must be set" }
            return NavigationModule(coroutineContext, startScreen!!, screens, _addInitialScreenToBackStack)
        }
    }

    /**
     * Creates a new NavigationModule instance using the provided configuration block.
     *
     * @param block The configuration block for setting up the navigation module.
     * @return A new NavigationModule instance.
     *
     * Example usage:
     * ```
     * val navigationModule = NavigationModule.create {
     *     setInitialScreen(HomeScreen)
     *     addScreen(ProfileScreen)
     *     addScreenGroup(SettingsScreenGroup)
     * }
     * ```
     */
    companion object {
        inline fun create(block: Builder.() -> Unit): NavigationModule {
            return Builder().apply(block).build()
        }
    }
}

/**
 * Creates a NavigationModule instance using the provided configuration block.
 *
 * @param block The configuration block for setting up the navigation module.
 * @return A new NavigationModule instance.
 *
 * Example usage:
 * ```
 * val navigationModule = createNavigationModule {
 *     setInitialScreen(HomeScreen)
 *     addScreen(ProfileScreen)
 *     addScreenGroup(SettingsScreenGroup)
 * }
 * ```
 */
fun createNavigationModule(block: NavigationModule.Builder.() -> Unit): NavigationModule {
    return NavigationModule.create {
        block.invoke(this)
    }
}