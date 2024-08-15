import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.extension.navigate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
        assertEquals(homeScreen, module.initialState.backStack.first().first)
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
        assertTrue { navigationState.backStack.first().first == deleteScreen }

        assertFails {
            store.navigate("edit"){
                clearBackStack()
                replaceWith("profile")
            }
        }
        advanceUntilIdle()
        assertFails {
            store.navigate("edit"){
                clearBackStack()
                popUpTo("delete")
            }
        }
        advanceUntilIdle()
        store.navigate("edit"){
            popUpTo("delete", true)
        }
        advanceUntilIdle()
        navigationState = store.selectState<NavigationState>().first()
        assertTrue { navigationState.backStack.first().first == editScreen }
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
        assertEquals(profileScreen, newState.backStack.last().first)
    }

    // Add more tests for other actions (Back, PopUpTo, ClearBackStack, Replace, SetLoading)
}