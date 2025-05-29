package io.github.syrou.reaktiv.navigation.dsl

import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.definition.ScreenGroup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.reflect.KClass

class Builder {
    var startScreen: Screen? = null
    var _addInitialScreenToBackStack: Boolean = false
    val screens = mutableListOf<Pair<KClass<out Screen>, Screen>>()
    private var coroutineContext = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    inline fun <reified S : Screen> setInitialScreen(
        screen: S,
        addInitialScreenToAvailableScreens: Boolean = false,
        addInitialScreenToBackStack: Boolean = false
    ) {
        startScreen = screen
        _addInitialScreenToBackStack = addInitialScreenToBackStack
        if (addInitialScreenToAvailableScreens) {
            addScreen(screen)
        }
    }

    inline fun <reified S : Screen> addScreen(screen: S) {
        screens.add(S::class to screen)
    }

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
        return NavigationModule(
            coroutineScope = coroutineContext,
            rootScreen = startScreen,
            screens = screens,
            addRootScreenToBackStack = _addInitialScreenToBackStack,
            rootGraph = null,
            isNestedNavigation = false
        )
    }
}