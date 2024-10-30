import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.runComposeUiTest
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationRender
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.extension.navigate
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import models.TestNavigationModule
import models.homeScreen
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
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
            StoreProvider(store) {
                NavigationRender(
                    modifier = Modifier.fillMaxSize(),
                ) { screen, map, isLoading ->
                    screen.Content(map)
                }
            }
        }

        onNodeWithText("Home Screen").assertIsDisplayed()
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
            StoreProvider(store) {
                NavigationRender(
                    modifier = Modifier.fillMaxSize(),
                ) { screen, param, isLoading ->
                    screen.Content(param)
                    if (isLoading) {
                        Text("Loading...")
                    }
                }
            }
        }

        store.dispatch(NavigationAction.SetLoading(true))
        waitForIdle()
        this.onRoot().printToLog("Thing")
        onNodeWithText("Loading...").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun verifyParamsSurvivingNavigation() = runComposeUiTest {
        val testObject = TestNavigationModule()
        var startMap = mutableMapOf<String, Any>()
        val store = createStore {
            coroutineContext(Dispatchers.Unconfined)
            module(
                createNavigationModule {
                    this.setInitialScreen(homeScreen, true, true)
                    this.addScreen(testObject.profileScreen)
                    this.addScreen(testObject.editScreen)
                }
            )
        }
        setContent {
            StoreProvider(store) {
                NavigationRender(
                    modifier = Modifier.fillMaxSize(),
                ) { screen, map, isLoading ->
                    startMap = map.toMutableMap()
                    screen.Content(map)
                }
            }
        }
        store.launch {
            store.navigate(testObject.profileScreen.route, mapOf("test" to "test123"))
        }
        waitForIdle()
        store.launch {
            store.navigate(testObject.editScreen.route)
        }
        waitForIdle()
        assertFalse { startMap.containsKey("test") }
        store.launch {
            store.navigateBack()
        }
        waitForIdle()
        println("WHAT - startMap: $startMap")
        assertTrue { startMap.containsKey("test") }
    }
}