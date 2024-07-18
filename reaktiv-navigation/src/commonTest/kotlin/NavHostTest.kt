import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runComposeUiTest
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.compose.selectState
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationRender
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.extension.navigate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import models.TestNavigationModule
import models.homeScreen
import models.profileScreen
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class NavHostTest {

    @BeforeTest
    fun before() {
        Dispatchers.setMain(Dispatchers.Default)
    }

    @AfterTest
    fun after() {
        Dispatchers.resetMain()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun navHostDisplaysCurrentScreen() = runComposeUiTest {
        val testObject = TestNavigationModule()
        val store = createStore {
            coroutineContext(Dispatchers.Default)
            module(
                createNavigationModule {
                    this.startScreen = testObject.homeScreen
                    this.addScreen(testObject.homeScreen)
                    this.addScreen(testObject.profileScreen)
                }
            )
        }
        setContent {
            NavigationRender(modifier = Modifier.fillMaxSize(), isAuthenticated = true) { screen, map ->
                screen.Content(map)
            }
        }

        onNodeWithText("Home Screen").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun navHostHandlesAuthenticationRequirement() = runComposeUiTest {
        println("===============================")
        val coroutineContext = Dispatchers.Unconfined
        val store = createStore {
            coroutineContext(coroutineContext)
            module(
                createNavigationModule {
                    coroutineContext(coroutineContext)
                    startScreen = homeScreen
                    addScreen(homeScreen)
                    addScreen(profileScreen)
                }
            )
        }
        var authenticationCalled = false
        store.navigate(profileScreen)
        setContent {
            StoreProvider(store) {
                val navigationState by selectState<NavigationState>().collectAsState(Dispatchers.Main)
                NavigationRender(
                    modifier = Modifier.fillMaxSize(),
                    store = store,
                    isAuthenticated = false,
                    onAuthenticationRequired = { authenticationCalled = true }
                ) { screen, params ->
                    screen.Content(params)
                }
            }
        }
        store.navigate(profileScreen)

        waitUntil { authenticationCalled }
        assertTrue(authenticationCalled)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun navHostDisplaysLoadingContent() = runComposeUiTest {
        val store = createStore {
            coroutineContext(Dispatchers.Default)
            module(
                createNavigationModule {
                    startScreen = homeScreen
                    addScreen(homeScreen)
                }
            )
        }
        setContent {
            NavigationRender(
                modifier = Modifier.fillMaxSize(),
                store = store,
                isAuthenticated = true,
                loadingContent = {
                    Text("Loading...")
                }
            ) { screen, param ->
                screen.Content(param)
            }
        }

        store.selectLogic<NavigationLogic>().dispatch(NavigationAction.SetLoading(true))
        waitForIdle()
        onNodeWithText("Loading...").assertIsDisplayed()
    }
}