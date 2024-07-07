import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import io.github.syrou.reaktiv.navigation.NavigationStack
import io.github.syrou.reaktiv.navigation.Navigation
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

    val navigationStack = NavigationStack(HomeScreen)
    val navigation = Navigation(navigationStack)

    @AfterTest
    fun clear() {
        navigationStack.clear()
        navigation.clear()
    }

    @Test
    fun testNormalNavigation() {
        navigation.addScreenGroup(UserManagementScreens)
        navigation.navigate("user/delete/{456}")
        assertNotEquals(navigationStack.currentScreen.value.first, UserManagementScreens.EditUser)
        assertEquals(navigationStack.currentScreen.value.first, UserManagementScreens.DeleteUser)
        assertEquals("id", navigationStack.currentScreen.value.second.keys.first())
        assertEquals("456", navigationStack.currentScreen.value.second.values.first())
    }

    @Test
    fun testThrowWhenMalformedPathParamsNavigation() {
        navigation.addScreenGroup(UserManagementScreens)
        assertFails { navigation.navigate("user/delete/{456") }
    }

    @Test
    fun testDeepLinkExtractionAndNavigation() {
        navigation.addScreenGroup(UserManagementScreens)
        navigation.handleDeepLink("https://example.com/navigation/user/edit/456")
        assertEquals(navigationStack.currentScreen.value.first, UserManagementScreens.EditUser)
        assertTrue { navigationStack.currentScreen.value.second.containsKey("id") }
        assertTrue { navigationStack.currentScreen.value.second.containsValue("456") }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun testParamsEndsUpPresentedInComposable() = runComposeUiTest {
        setContent {
            val isAuthRequired by remember { mutableStateOf(false) }
            NavHost(navigationStack, isAuthRequired) {
                //No need to navigate to auth
            }
        }
        navigation.addScreenGroup(UserManagementScreens)
        navigation.navigate("user/edit/{456}")
        onNodeWithTag("id").assertTextEquals("456")
    }
}