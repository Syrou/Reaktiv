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
import io.github.syrou.reaktiv.navigation.util.PathUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
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
 * Represents the state of the navigation system.
 *
 * @property currentEntry The currently active screen.
 * @property backStack The stack of screens representing the navigation history.
 * @property availableScreens A map of all available screens in the application.
 * @property isLoading Indicates whether a navigation action is in progress.
 */
@Serializable
data class NavigationState(
    val currentEntry: NavigationEntry,
    val backStack: List<NavigationEntry>,
    val availableScreens: Map<String, Screen> = emptyMap(),
    val clearedBackStackWithNavigate: Boolean = false,
    val isLoading: Boolean = false,
) : ModuleState {
    /**
     * Determines which entry should be displayed at a specific rendering level.
     * This uses only the path structure to determine nesting - no registration needed.
     */
    fun getEntryToDisplay(basePath: String): NavigationEntry? {
        // If we're not at root level, show children at this level
        if (basePath.isNotEmpty()) {
            return getActiveChildAtLevel(basePath)
        }

        // For root level, check if current path belongs to a nested navigation
        val currentPath = currentEntry.path
        val segments = currentPath.split("/")

        // If path has multiple segments, it might belong to a nested navigation
        if (segments.size > 1) {
            val topLevelPath = segments[0]

            // Check if this top-level path exists in backstack
            val topLevelEntry = backStack.find { it.path == topLevelPath }

            // If top level path exists and it's not the current entry,
            // pass control to that level's NavigationRender
            if (topLevelEntry != null && currentPath != topLevelPath) {
                return topLevelEntry
            }
        }

        // Default: show current entry
        return currentEntry
    }

    /**
     * Gets the active child at a specific path level.
     */
    private fun getActiveChildAtLevel(parentPath: String): NavigationEntry? {
        // Check if current entry is a direct child of this path
        val currentIsChild = currentEntry.path.startsWith("$parentPath/") &&
                !currentEntry.path.substring(parentPath.length + 1).contains('/')

        if (currentIsChild) {
            return currentEntry
        }

        // Find the most recent direct child in backstack
        return backStack.findLast { entry ->
            entry.path.startsWith("$parentPath/") &&
                    !entry.path.substring(parentPath.length + 1).contains('/')
        }
    }
}

@Serializable
data class NavigationEntry(
    val screen: Screen,
    val params: StringAnyMap,
    val id: String? = null,       // Unique identifier for this entry (route)
    @Transient
    var parent: NavigationEntry? = null, // Transient to avoid serialization cycles
    @Transient
    var child: NavigationEntry? = null   // Transient to avoid serialization cycles
) {
    val path: String get() = screen.route

    val parentPath: String get() = PathUtil.getParentPath(path)

    val pathSegments: List<String> get() = PathUtil.getPathSegments(path)

    fun hasChild(): Boolean = child?.id != null

    fun hasParent(): Boolean = parent?.id != null

    fun isDirectChildOf(parentPath: String): Boolean = parent?.path == parentPath

    fun pathStartsWith(prefix: String): Boolean = path == prefix || path.startsWith("$prefix/")

    override fun hashCode(): Int {
        var result = screen.hashCode()
        result = 31 * result + params.hashCode()
        result = 31 * result + path.hashCode()
        // Don't include parent and child references
        return result
    }

    // Override equals to avoid circular references
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NavigationEntry) return false

        if (screen != other.screen) return false
        if (params != other.params) return false
        if (path != other.path) return false
        // Don't compare parent and child references

        return true
    }

}

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
            currentEntry = NavigationEntry(
                screen = rootScreen,
                params = emptyMap()
            ),
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

                // Create new entry
                val newEntry = NavigationEntry(
                    screen = targetScreen,
                    params = params,
                    id = action.route
                )

                // Handle nested navigation - ensure parent paths are in backstack
                val path = targetScreen.route
                val pathSegments = path.split("/")

                // If this is a nested path, ensure parent entries exist in backstack
                if (pathSegments.size > 1) {
                    var currentPath = ""
                    // Ensure all parent paths are in the backstack
                    for (i in 0 until pathSegments.size - 1) {
                        if (i > 0) currentPath += "/"
                        currentPath += pathSegments[i]

                        // Only add if not already in backstack
                        if (newBackStack.none { it.path == currentPath }) {
                            val parentScreen = state.availableScreens[currentPath]
                                ?: error("No screen found for parent path: $currentPath")

                            val parentEntry = NavigationEntry(
                                screen = parentScreen,
                                params = emptyMap(),
                                id = currentPath
                            )

                            newBackStack = newBackStack + parentEntry
                        }
                    }
                }

                // Add the new entry
                newBackStack = newBackStack + newEntry

                // Update state
                state.copy(
                    currentEntry = newEntry,
                    backStack = newBackStack,
                    clearedBackStackWithNavigate = action.clearBackStack
                )
            }

            is NavigationAction.Back -> {
                if (state.backStack.size > 1) {
                    // Handle back navigation based on path structure
                    val currentEntry = state.currentEntry
                    val currentPath = currentEntry.path

                    // Find the previous entry in the backstack
                    val targetIndex = state.backStack.indexOf(currentEntry) - 1

                    if (targetIndex >= 0) {
                        val targetEntry = state.backStack[targetIndex]

                        // Create new backstack without the current entry
                        val newBackStack = state.backStack.filter { it != currentEntry }

                        state.copy(
                            currentEntry = targetEntry,
                            backStack = newBackStack
                        )
                    } else {
                        // Standard back behavior as fallback
                        val newBackStack = state.backStack.dropLast(1)
                        state.copy(
                            currentEntry = newBackStack.last(),
                            backStack = newBackStack
                        )
                    }
                } else {
                    state
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

                    var currentEntry = newBackStack.lastOrNull() ?: state.currentEntry
                    if (action.replaceWith != null) {
                        val replaceScreen = state.availableScreens[action.replaceWith]
                            ?: error("No screen found for route: ${action.replaceWith}")

                        // Create a new entry for the replacement
                        val newEntry = NavigationEntry(
                            screen = replaceScreen,
                            params = action.replaceParams,
                            id = action.replaceWith
                        )

                        newBackStack = newBackStack.dropLast(1)
                        currentEntry = newEntry
                        newBackStack = newBackStack + newEntry
                    }

                    state.copy(
                        currentEntry = currentEntry,
                        backStack = newBackStack
                    )
                } else {
                    state
                }
            }

            is NavigationAction.ClearBackStack -> {
                if (action.root != null) {
                    val rootScreen = state.availableScreens[action.root]
                        ?: error("No screen found for route: ${action.root}")
                    val rootEntry = NavigationEntry(
                        screen = rootScreen,
                        params = action.params,
                        id = action.root
                    )
                    state.copy(
                        currentEntry = rootEntry,
                        backStack = listOf(rootEntry)
                    )
                } else {
                    state.copy(backStack = listOf())
                }
            }

            is NavigationAction.Replace -> {
                val newScreen = state.availableScreens[action.route]
                    ?: error("No screen found for route: ${action.route}")

                // Create new entry
                val newEntry = NavigationEntry(
                    screen = newScreen,
                    params = action.params,
                    id = action.route
                )

                // Replace in the backstack
                val newBackStack = state.backStack.map {
                    if (it == state.currentEntry) newEntry else it
                }

                state.copy(
                    currentEntry = newEntry,
                    backStack = newBackStack
                )
            }

            is NavigationAction.SetLoading -> {
                state.copy(isLoading = action.isLoading)
            }

            is NavigationAction.ClearCurrentScreenParams -> {
                val updatedBackStack = state.backStack.map { entry ->
                    if (entry.screen == state.currentEntry.screen) {
                        entry.copy(params = emptyMap())
                    } else {
                        entry
                    }
                }
                state.copy(
                    currentEntry = state.currentEntry.copy(params = emptyMap()),
                    backStack = updatedBackStack
                )
            }

            is NavigationAction.ClearCurrentScreenParam -> {
                val updatedBackStack = state.backStack.map { entry ->
                    if (entry.screen == state.currentEntry.screen) {
                        entry.copy(params = entry.params - action.key)
                    } else {
                        entry
                    }
                }
                state.copy(
                    currentEntry = state.currentEntry.copy(params = state.currentEntry.params - action.key),
                    backStack = updatedBackStack
                )
            }

            is NavigationAction.ClearScreenParams -> {
                val updatedBackStack = state.backStack.map { entry ->
                    if (entry.screen.route == action.route) {
                        entry.copy(params = emptyMap())
                    } else {
                        entry
                    }
                }
                val updatedCurrentEntry = if (state.currentEntry.screen.route == action.route) {
                    state.currentEntry.copy(params = emptyMap())
                } else {
                    state.currentEntry
                }

                state.copy(
                    currentEntry = updatedCurrentEntry,
                    backStack = updatedBackStack
                )
            }

            is NavigationAction.ClearScreenParam -> {
                val updatedBackStack = state.backStack.map { entry ->
                    if (entry.screen.route == action.route) {
                        entry.copy(params = entry.params - action.key)
                    } else {
                        entry
                    }
                }
                val updatedCurrentEntry = if (state.currentEntry.screen.route == action.route) {
                    state.currentEntry.copy(params = state.currentEntry.params - action.key)
                } else {
                    state.currentEntry
                }

                state.copy(
                    currentEntry = updatedCurrentEntry,
                    backStack = updatedBackStack
                )
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