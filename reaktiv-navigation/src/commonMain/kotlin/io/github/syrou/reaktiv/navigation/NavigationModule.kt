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
    val exclusivePathHandlers: Map<String, Boolean> = emptyMap(),
    val persistentHandlers: Set<String> = emptySet()
) : ModuleState {
    /**
     * Determines which entry should be displayed at a specific rendering level.
     * This logic is moved from NavigationRender.
     */
    fun getEntryToDisplay(basePath: String): NavigationEntry? {
        println("DEBUG [getEntryToDisplay] called with basePath: '$basePath'")
        println("DEBUG [getEntryToDisplay] current path: '${currentEntry.path}'")
        println("DEBUG [getEntryToDisplay] backStack: ${backStack.map { it.path }}")
        println("DEBUG [getEntryToDisplay] exclusivePathHandlers: $exclusivePathHandlers")
        println("DEBUG [getEntryToDisplay] persistentHandlers: $persistentHandlers")

        // If we're not at root level, show children at this level
        if (basePath.isNotEmpty()) {
            val activeChild = getActiveChildAtLevel(basePath)
            println("DEBUG [getEntryToDisplay] activeChild for '$basePath': ${activeChild?.path}")
            return activeChild
        }

        // For root level, handle exclusive paths
        val isCurrentEntryHandledExclusively = isPathHandledExclusively(currentEntry.path)
        println("DEBUG [getEntryToDisplay] isCurrentEntryHandledExclusively: $isCurrentEntryHandledExclusively")

        if (isCurrentEntryHandledExclusively) {
            // Get the top-level path that should handle this entry
            val handlerPath = findExclusiveHandler(currentEntry.path)
            println("DEBUG [getEntryToDisplay] handlerPath: $handlerPath")

            if (handlerPath != null) {
                // Return the entry for this path
                val handlerEntry = backStack.find { it.path == handlerPath }
                println("DEBUG [getEntryToDisplay] handlerEntry: ${handlerEntry?.path}")
                return handlerEntry
            }
        }

        // Default: show current entry
        println("DEBUG [getEntryToDisplay] returning default currentEntry: ${currentEntry.path}")
        return currentEntry
    }

    /**
     * Gets the active child at a specific path level.
     */
    private fun getActiveChildAtLevel(parentPath: String): NavigationEntry? {
        println("DEBUG [getActiveChildAtLevel] parentPath: '$parentPath'")

        // Find all direct children
        val allChildren = backStack.filter { entry ->
            val entryParentPath = PathUtil.getParentPath(entry.path)
            val isChild = entryParentPath == parentPath
            println("DEBUG [getActiveChildAtLevel] entry: ${entry.path}, entryParentPath: '$entryParentPath', isChild: $isChild")
            isChild
        }

        // Get the most recent one
        val lastChild = allChildren.lastOrNull()
        println("DEBUG [getActiveChildAtLevel] found ${allChildren.size} children, last: ${lastChild?.path}")

        // If a child is the current entry, prioritize it
        val currentEntryAsChild = if (PathUtil.getParentPath(currentEntry.path) == parentPath) {
            println("DEBUG [getActiveChildAtLevel] current entry is a child of '$parentPath'")
            currentEntry
        } else null

        val result = currentEntryAsChild ?: lastChild
        println("DEBUG [getActiveChildAtLevel] result: ${result?.path}")

        return result
    }

    /**
     * Checks if a path is handled exclusively by a different renderer.
     */
    private fun isPathHandledExclusively(path: String): Boolean {
        val exclusiveHandlers = exclusivePathHandlers.entries
            .filter { it.value } // Only consider exclusive handlers

        println("DEBUG [isPathHandledExclusively] path: '$path', exclusiveHandlers: $exclusiveHandlers")

        val result = exclusiveHandlers.any { (handlerPath, _) ->
            val isHandled = path.startsWith(handlerPath) && path != handlerPath
            println("DEBUG [isPathHandledExclusively] checking handler: '$handlerPath', isHandled: $isHandled")
            isHandled
        }

        println("DEBUG [isPathHandledExclusively] result: $result")
        return result
    }

    /**
     * Finds the exclusive handler for a path.
     */
    private fun findExclusiveHandler(path: String): String? {
        val exclusiveHandlers = exclusivePathHandlers.entries
            .filter { it.value } // Only consider exclusive handlers
            .map { it.key }

        println("DEBUG [findExclusiveHandler] path: '$path', exclusiveHandlers: $exclusiveHandlers")

        val result = exclusiveHandlers.firstOrNull { handlerPath ->
            val isHandler = path.startsWith(handlerPath) && path != handlerPath
            println("DEBUG [findExclusiveHandler] checking handler: '$handlerPath', isHandler: $isHandler")
            isHandler
        }

        println("DEBUG [findExclusiveHandler] result: $result")
        return result
    }

    /**
     * Registers a path handler. If exclusive, it will handle all child paths.
     */
    fun withPathHandler(path: String, exclusive: Boolean, persistent: Boolean = false): NavigationState {
        println("DEBUG [withPathHandler] registering path: '$path', exclusive: $exclusive, persistent: $persistent")
        val updatedHandlers = exclusivePathHandlers.toMutableMap()
        updatedHandlers[path] = exclusive

        val updatedPersistentHandlers = if (persistent) {
            persistentHandlers + path
        } else {
            persistentHandlers
        }

        return copy(
            exclusivePathHandlers = updatedHandlers,
            persistentHandlers = updatedPersistentHandlers
        )
    }

    /**
     * Unregisters a path handler.
     */
    fun withoutPathHandler(path: String): NavigationState {
        println("DEBUG [withoutPathHandler] unregistering path: '$path'")

        // Don't unregister persistent handlers
        if (persistentHandlers.contains(path)) {
            println("DEBUG [withoutPathHandler] path '$path' is persistent, not unregistering")
            return this
        }

        val updatedHandlers = exclusivePathHandlers.toMutableMap()
        updatedHandlers.remove(path)
        return copy(exclusivePathHandlers = updatedHandlers)
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
                println("DEBUG [NavigationReducer] Navigate: route='${action.route}', params=${action.params}")
                println("DEBUG [NavigationReducer] Navigate: parent=${action.parent?.path}")

                var newBackStack = if (action.clearBackStack) {
                    println("DEBUG [NavigationReducer] clearing backstack")
                    listOf()
                } else state.backStack

                // Handle popUpTo
                if (action.popUpTo != null) {
                    println("DEBUG [NavigationReducer] popUpTo: ${action.popUpTo}, inclusive: ${action.inclusive}")
                    val popIndex = newBackStack.indexOfLast { it.screen.route == action.popUpTo }
                    println("DEBUG [NavigationReducer] popIndex: $popIndex")

                    if (popIndex != -1) {
                        newBackStack = if (action.inclusive) {
                            println("DEBUG [NavigationReducer] popping inclusive to index $popIndex")
                            newBackStack.subList(0, popIndex)
                        } else {
                            println("DEBUG [NavigationReducer] popping non-inclusive to index $popIndex")
                            newBackStack.subList(0, popIndex + 1)
                        }
                    }
                }

                val targetScreen = if (action.replaceWith != null) {
                    println("DEBUG [NavigationReducer] replacing with: ${action.replaceWith}")
                    state.availableScreens[action.replaceWith]
                        ?: error("No screen found for route: ${action.replaceWith}")
                } else {
                    println("DEBUG [NavigationReducer] using route: ${action.route}")
                    state.availableScreens[action.route]
                        ?: error("No screen found for route: ${action.route}")
                }

                println("DEBUG [NavigationReducer] targetScreen: ${targetScreen.route}")

                val params: StringAnyMap = if (action.forwardParams) {
                    println("DEBUG [NavigationReducer] forwarding params")
                    val previousParams = newBackStack.lastOrNull()?.params ?: emptyMap()
                    previousParams.plus(action.params)
                } else {
                    action.params
                }

                println("DEBUG [NavigationReducer] final params: $params")

                // Create new entry
                val newEntry = NavigationEntry(
                    screen = targetScreen,
                    params = params,
                    id = action.route
                )

                println("DEBUG [NavigationReducer] newEntry: ${newEntry.path}")
                println("DEBUG [NavigationReducer] current backStack: ${newBackStack.map { it.path }}")
                println("DEBUG [NavigationReducer] adding to backStack: ${newEntry.path}")

                val result = state.copy(
                    currentEntry = newEntry,
                    backStack = newBackStack + newEntry,
                    clearedBackStackWithNavigate = action.clearBackStack
                )

                println("DEBUG [NavigationReducer] new backStack: ${result.backStack.map { it.path }}")
                println("DEBUG [NavigationReducer] new currentEntry: ${result.currentEntry.path}")

                result
            }

            is NavigationAction.Back -> {
                println("DEBUG [NavigationReducer] Back")

                if (state.backStack.size > 1) {
                    println("DEBUG [NavigationReducer] backStack size: ${state.backStack.size}")

                    // Determine what entry to go back to based on paths
                    val currentPath = state.currentEntry.path
                    val parentPath = PathUtil.getParentPath(currentPath)

                    println("DEBUG [NavigationReducer] currentPath: '$currentPath', parentPath: '$parentPath'")

                    // Find the entry to go back to
                    val targetEntry = if (parentPath.isNotEmpty()) {
                        // Try to find parent in backstack
                        val parentEntry = state.backStack.findLast { it.path == parentPath }
                        println("DEBUG [NavigationReducer] found parent entry: ${parentEntry?.path}")

                        parentEntry ?: run {
                            println("DEBUG [NavigationReducer] parent not found, using standard back behavior")
                            state.backStack[state.backStack.lastIndex - 1]
                        }
                    } else {
                        println("DEBUG [NavigationReducer] using standard back behavior (no parent)")
                        state.backStack[state.backStack.lastIndex - 1]
                    }

                    println("DEBUG [NavigationReducer] target entry: ${targetEntry.path}")

                    // Remove current entry and set target as current
                    val newBackStack = state.backStack.filter { it.path != currentPath }
                    println("DEBUG [NavigationReducer] new backStack: ${newBackStack.map { it.path }}")

                    state.copy(
                        currentEntry = targetEntry,
                        backStack = newBackStack
                    )
                } else {
                    println("DEBUG [NavigationReducer] backStack too small, no change")
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

            is NavigationAction.RegisterPathHandler -> {
                println("DEBUG [NavigationReducer] RegisterPathHandler: path='${action.path}', exclusive=${action.exclusive}, persistent=${action.persistent}")
                println("DEBUG [NavigationReducer] current handlers: ${state.exclusivePathHandlers}")
                println("DEBUG [NavigationReducer] current persistent handlers: ${state.persistentHandlers}")

                val result = state.withPathHandler(action.path, action.exclusive, action.persistent)
                println("DEBUG [NavigationReducer] new handlers: ${result.exclusivePathHandlers}")
                println("DEBUG [NavigationReducer] new persistent handlers: ${result.persistentHandlers}")

                result
            }

            is NavigationAction.UnregisterPathHandler -> {
                println("DEBUG [NavigationReducer] UnregisterPathHandler: path='${action.path}'")
                println("DEBUG [NavigationReducer] current handlers: ${state.exclusivePathHandlers}")
                println("DEBUG [NavigationReducer] current persistent handlers: ${state.persistentHandlers}")

                val result = state.withoutPathHandler(action.path)
                println("DEBUG [NavigationReducer] new handlers: ${result.exclusivePathHandlers}")

                result
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