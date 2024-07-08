import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.*
import io.github.syrou.reaktiv.navigation.NavTransition
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.RouteNotFoundException
import io.github.syrou.reaktiv.navigation.Screen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NavigationLogicTest {
    private lateinit var logic: NavigationLogic
    private lateinit var availableScreens: Map<String, Screen>

    @BeforeTest
    fun setup() {
        availableScreens = mapOf(
            "home" to object : Screen {
                override val route = "home"
                override val titleResourceId = 0
                override val enterTransition = NavTransition.None
                override val exitTransition = NavTransition.None
                override val requiresAuth = false
                @Composable
                override fun Content(params: Map<String, Any>) {}
            },
            "profile" to object : Screen {
                override val route = "profile"
                override val titleResourceId = 0
                override val enterTransition = NavTransition.None
                override val exitTransition = NavTransition.None
                override val requiresAuth = true
                @Composable
                override fun Content(params: Map<String, Any>) {}
            }
        )
        logic = NavigationLogic(kotlinx.coroutines.Dispatchers.Unconfined, availableScreens)
    }

    @Test
    fun `navigate to valid route succeeds`() = runTest {
        var dispatchedAction: NavigationAction? = null
        logic.dispatch = { dispatchedAction = it as NavigationAction }

        logic.navigate("home")

        assertIs<NavigationAction.Navigate>(dispatchedAction)
        assertEquals("home", (dispatchedAction as NavigationAction.Navigate).route)
    }

    @Test
    fun `navigate to invalid route throws RouteNotFoundException`() {
        assertFails {
            logic.navigate("invalid")
        }
    }

    @Test
    fun `popUpTo with valid route succeeds`() = runTest {
        var dispatchedAction: NavigationAction? = null
        logic.dispatch = { dispatchedAction = it as NavigationAction }

        logic.popUpTo("home")

        assertIs<NavigationAction.PopUpTo>(dispatchedAction)
        assertEquals("home", (dispatchedAction as NavigationAction.PopUpTo)?.route)
    }

    @Test
    fun `popUpTo with invalid route throws RouteNotFoundException`() {
        assertFails {
            logic.popUpTo("invalid")
        }
    }

    @Test
    fun `navigateWithValidation succeeds when validation passes`() = runTest {
        var dispatchedActions = mutableListOf<NavigationAction>()
        logic.dispatch = { dispatchedActions.add(it as NavigationAction) }
        val store = createStore { modules(TestModule()) }

        logic.navigateWithValidation("home", emptyMap(), store) { _, _ -> true }

        assertTrue(dispatchedActions.any { it is NavigationAction.SetLoading })
        assertTrue(dispatchedActions.any { it is NavigationAction.Navigate })
    }

    @Test
    fun `navigateWithValidation navigates back when validation fails`() = runTest {
        var dispatchedActions = mutableListOf<NavigationAction>()
        logic.dispatch = { dispatchedActions.add(it as NavigationAction) }
        val store = createStore { modules(TestModule()) }
        logic.navigateWithValidation("home", emptyMap(), store) { _, _ -> false }
        assertTrue(dispatchedActions.any { it is NavigationAction.Back })
    }
}