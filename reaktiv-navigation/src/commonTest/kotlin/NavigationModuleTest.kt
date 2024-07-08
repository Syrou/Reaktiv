import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.navigation.NavTransition
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationModuleTest {
    @Test
    fun `initial state is set correctly`() {
        val homeScreen = object : Screen {
            override val route = "home"
            override val titleResourceId = 0
            override val enterTransition = NavTransition.None
            override val exitTransition = NavTransition.None
            override val requiresAuth = false
            @Composable
            override fun Content(params: Map<String, Any>) {}
        }
        val module = NavigationModule(initialScreen = homeScreen)

        assertEquals(homeScreen, module.initialState.currentScreen)
        assertEquals(1, module.initialState.backStack.size)
        assertEquals(homeScreen, module.initialState.backStack.first().first)
    }

    @Test
    fun `reducer handles Navigate action correctly`() {
        val module = NavigationModule(
            coroutineContext = Dispatchers.Unconfined,
            initialScreen = homeScreen,
            profileScreen
        )
        val initialState = module.initialState
        val action = NavigationAction.Navigate("profile")

        val newState = module.reducer(initialState, action)

        assertEquals(profileScreen, newState.currentScreen)
        assertEquals(2, newState.backStack.size)
        assertEquals(profileScreen, newState.backStack.last().first)
    }

    // Add more tests for other actions (Back, PopUpTo, ClearBackStack, Replace, SetLoading)
}