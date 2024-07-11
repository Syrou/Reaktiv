import androidx.compose.material.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationRender
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.navigate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.setMain
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class NavHostTest {

    @BeforeTest
    fun before() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun navHostDisplaysCurrentScreen() = runComposeUiTest {
        val store = createStore {
            coroutineContext(Dispatchers.Unconfined)
            modules(TestNavigationModule())
        }
        setContent {
            NavigationRender(store = store, isAuthenticated = true)
        }

        onNodeWithText("Home Screen").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun navHostHandlesAuthenticationRequirement() = runComposeUiTest {
        val store = createStore {
            coroutineContext(Dispatchers.Unconfined)
            modules(
                NavigationModule(Dispatchers.Unconfined, homeScreen, profileScreen)
            )
        }
        var authenticationCalled = false
        setContent {
            NavigationRender(
                store = store,
                isAuthenticated = false,
                onAuthenticationRequired = { authenticationCalled = true }
            )
        }
        store.navigate(profileScreen)
        waitForIdle()

        assertTrue(authenticationCalled)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun navHostDisplaysLoadingContent() = runComposeUiTest {
        val store = createStore {
            coroutineContext(Dispatchers.Unconfined)
            modules(
                NavigationModule(Dispatchers.Unconfined, homeScreen)
            )
        }
        setContent {
            NavigationRender(
                store = store,
                isAuthenticated = true,
                loadingContent = { Text("Loading...") }
            )
        }

        store.selectLogic<NavigationLogic>().dispatch(NavigationAction.SetLoading(true))
        waitForIdle()

        onNodeWithText("Loading...").assertIsDisplayed()
    }
}