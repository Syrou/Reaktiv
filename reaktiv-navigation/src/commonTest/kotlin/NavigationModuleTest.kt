import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.extension.navigate
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import models.deleteScreen
import models.editScreen
import models.homeScreen
import models.profileScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class NavigationModuleTest {
    @Test
    fun `initial state is set correctly`() {
        val module = createNavigationModule {
            setInitialScreen(homeScreen, true, true)
        }

        assertEquals(homeScreen, module.initialState.currentScreen)
        assertEquals(1, module.initialState.backStack.size)
        assertEquals(homeScreen, module.initialState.backStack.first().screen)
    }

    @Test
    fun `check that navigate and backstack clear works`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val navigationModule = createNavigationModule {
            setInitialScreen(homeScreen, true, true)
            addScreen(profileScreen)
            addScreen(editScreen)
            addScreen(deleteScreen)
        }
        val store = createStore {
            module(navigationModule)
            coroutineContext(Dispatchers.Unconfined)
        }

        store.navigate("profile")
        advanceUntilIdle()
        store.navigate("edit")
        advanceUntilIdle()
        var navigationState = store.selectState<NavigationState>().first()
        assertEquals(3, navigationState.backStack.size)
        store.navigate("delete") {
            clearBackStack()
        }
        advanceUntilIdle()
        navigationState = store.selectState<NavigationState>().first()
        assertEquals(1, navigationState.backStack.size)
        assertTrue { navigationState.backStack.first().screen == deleteScreen }

        assertFails {
            store.navigate("edit") {
                clearBackStack()
                replaceWith("profile")
            }
        }
        advanceUntilIdle()
        assertFails {
            store.navigate("edit") {
                clearBackStack()
                popUpTo("delete")
            }
        }
        advanceUntilIdle()
        store.navigate("edit") {
            popUpTo("delete", true)
        }
        advanceUntilIdle()
        navigationState = store.selectState<NavigationState>().first()
        assertTrue { navigationState.backStack.first().screen == editScreen }
    }

    @Test
    fun `test that params survive navigation`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val navigationModule = createNavigationModule {
            setInitialScreen(homeScreen, true, true)
            addScreen(profileScreen)
            addScreen(editScreen)
            addScreen(deleteScreen)
        }
        val store = createStore {
            module(navigationModule)
            coroutineContext(Dispatchers.Unconfined)
        }

        store.navigate(profileScreen.route, mapOf("test" to "test123"))
        store.navigate(editScreen.route)
        var navigationState = store.selectState<NavigationState>().first()
        println("CURRENT BACK STACK: ${navigationState.backStack}")
        store.navigateBack()
        navigationState = store.selectState<NavigationState>().first()
        println("CURRENT NAVIGATION: ${navigationState}")
        assertTrue { navigationState.backStack.first { it.screen == navigationState.currentScreen }.params.isNotEmpty() }
    }

    @Test
    fun `reducer handles Navigate action correctly`() {
        val module = createNavigationModule {
            setInitialScreen(homeScreen, addInitialScreenToAvailableScreens = true, addInitialScreenToBackStack = true)
            addScreen(profileScreen)
            coroutineContext(Dispatchers.Unconfined)
        }
        val initialState = module.initialState
        val action = NavigationAction.Navigate("profile")

        val newState = module.reducer(initialState, action)

        assertEquals(profileScreen, newState.currentScreen)
        assertEquals(2, newState.backStack.size)
        assertEquals(profileScreen, newState.backStack.last().screen)
    }

    // Add more tests for other actions (Back, PopUpTo, ClearBackStack, Replace, SetLoading)
}