package io.github.syrou.reaktiv.navigation

import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.core.util.CustomTypeRegistrar
import io.github.syrou.reaktiv.navigation.definition.NavigationNode
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.dsl.Builder
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

open class ScreenGroup(
    val screens: List<Screen>
) : NavigationNode {
    constructor(vararg screens: Screen) : this(screens.toList())
}

class NavigationModule internal constructor(
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

                val newEntry = NavigationEntry(
                    screen = targetScreen,
                    params = params
                )

                val currentEntry = newBackStack.lastOrNull()
                if (currentEntry == newEntry) {
                    state
                } else {
                    state.copy(
                        currentEntry = newEntry,
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


                    var currentEntry = newBackStack.lastOrNull() ?: state.currentEntry
                    if (action.replaceWith != null) {
                        val replaceScreen = state.availableScreens[action.replaceWith]
                            ?: error("No screen found for route: ${action.replaceWith}")
                        currentEntry = currentEntry.copy(screen = replaceScreen, params = action.replaceParams)
                        newBackStack = newBackStack.dropLast(1) + currentEntry
                    }

                    state.copy(
                        currentEntry = currentEntry,
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
                        currentEntry = newBackStack.last(),
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
                        currentEntry = NavigationEntry(currentScreen, action.params),
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
                    currentEntry = newEntry,
                    backStack = state.backStack.dropLast(1) + newEntry,
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
                state.copy(backStack = updatedBackStack)
            }

            is NavigationAction.ClearCurrentScreenParam -> {
                val updatedBackStack = state.backStack.map { entry ->
                    if (entry.screen == state.currentEntry.screen) {
                        entry.copy(params = entry.params - action.key)
                    } else {
                        entry
                    }
                }
                state.copy(backStack = updatedBackStack)
            }

            is NavigationAction.ClearScreenParams -> {
                val updatedBackStack = state.backStack.map { entry ->
                    if (entry.screen.route == action.route) {
                        entry.copy(params = emptyMap())
                    } else {
                        entry
                    }
                }
                state.copy(backStack = updatedBackStack)
            }

            is NavigationAction.ClearScreenParam -> {
                val updatedBackStack = state.backStack.map { entry ->
                    if (entry.screen.route == action.route) {
                        entry.copy(params = entry.params - action.key)
                    } else {
                        entry
                    }
                }
                state.copy(backStack = updatedBackStack)
            }
        }
    }

    override val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic<NavigationAction> = { storeAccessor ->
        NavigationLogic(coroutineScope, initialState.availableScreens, storeAccessor)
    }

    companion object {
        inline fun create(block: Builder.() -> Unit): NavigationModule {
            return Builder().apply(block).build()
        }
    }
}

fun createNavigationModule(block: Builder.() -> Unit): NavigationModule {
    return NavigationModule.create {
        block.invoke(this)
    }
}