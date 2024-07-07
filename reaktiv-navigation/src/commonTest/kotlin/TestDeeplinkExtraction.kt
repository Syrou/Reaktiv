import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import io.github.syrou.reaktiv.navigation.NavController
import io.github.syrou.reaktiv.navigation.NavGraph
import io.github.syrou.reaktiv.navigation.NavHost
import io.github.syrou.reaktiv.navigation.NavTransition
import io.github.syrou.reaktiv.navigation.Screen
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TestDeeplinkExtraction {
    object HomeScreen : Screen {
        override val route = "home"
        override val titleResourceId: Int = 0
        override val enterTransition = NavTransition.FADE
        override val exitTransition = NavTransition.FADE
        override val requiresAuth = false

        @Composable
        fun HomeScreenContent() { /* Implementation */
        }

        @Composable
        override fun Content(params: Map<String, Any>) {
            HomeScreenContent()
        }
    }

    val navController = NavController(HomeScreen)
    val navGraph = NavGraph(navController)

    @AfterTest
    fun clear() {
        navController.clear()
        navGraph.clear()
    }

    @Test
    fun testNormalNavigation() {
        navGraph.addScreenGroup(UserManagementScreens)
        navGraph.navigate("user/delete/{456}")
        assertNotEquals(navController.currentScreen.value.first, UserManagementScreens.EditUser)
        assertEquals(navController.currentScreen.value.first, UserManagementScreens.DeleteUser)
        assertEquals("id", navController.currentScreen.value.second.keys.first())
        assertEquals("456", navController.currentScreen.value.second.values.first())
    }

    @Test
    fun testThrowWhenMalformedPathParamsNavigation() {
        navGraph.addScreenGroup(UserManagementScreens)
        assertFails { navGraph.navigate("user/delete/{456") }
    }

    @Test
    fun testDeepLinkExtractionAndNavigation() {
        navGraph.addScreenGroup(UserManagementScreens)
        navGraph.handleDeepLink("https://example.com/navigation/user/edit/456")
        assertEquals(navController.currentScreen.value.first, UserManagementScreens.EditUser)
        assertTrue { navController.currentScreen.value.second.containsKey("id") }
        assertTrue { navController.currentScreen.value.second.containsValue("456") }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun testParamsEndsUpPresentedInComposable() = runComposeUiTest {
        setContent {
            val isAuthRequired by remember { mutableStateOf(false) }
            NavHost(navController, isAuthRequired) {
                //No need to navigate to auth
            }
        }
        navGraph.addScreenGroup(UserManagementScreens)
        navGraph.navigate("user/edit/{456}")
        onNodeWithTag("id").assertTextEquals("456")
    }
}