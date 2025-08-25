import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.GuidedFlow
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.guidedFlow
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

// Test screens
object BugTestSignUpScreen : Screen {
    override val route = "bug-signup"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false
    override val titleResource: TitleResource = { "Sign Up" }
    @Composable
    override fun Content(params: Params) {}
}

object BugTestWelcomeScreen : Screen {
    override val route = "bug-welcome"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false
    override val titleResource: TitleResource = { "Welcome" }
    @Composable
    override fun Content(params: Params) {}
}

object BugTestHomeScreen : Screen {
    override val route = "bug-home"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false
    override val titleResource: TitleResource = { "Home" }
    @Composable
    override fun Content(params: Params) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class GuidedFlowModificationBugTest {

    private fun createBugTestNavigationModule() = createNavigationModule {
        rootGraph {
            startScreen(BugTestWelcomeScreen)
            screens(BugTestWelcomeScreen, BugTestSignUpScreen, BugTestHomeScreen)
        }
        
        guidedFlow("bug-signup-flow") {
            step<BugTestSignUpScreen>()
            onComplete { storeAccessor ->
                // Original onComplete - should not be called if modified
                println("❌ Original onComplete called - this is wrong!")
            }
        }
    }

    @Test
    fun `should use modified step parameters and onComplete handler`() = runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        var modifiedOnCompleteTriggered = false
        
        val store = createStore {
            module(createBugTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        val uri = "https://example.com/invite"
        
        // Modify the guided flow with step parameters AND onComplete handler
        store.guidedFlow("bug-signup-flow") {
            updateStepParams<BugTestSignUpScreen> {
                putString("DEEPLINK", uri)
            }
            updateOnComplete { storeAccessor ->
                // Modified onComplete handler - this should be called
                modifiedOnCompleteTriggered = true
                
                // Perform navigation like user's code
                storeAccessor.navigation {
                    navigateTo("bug-home")
                }
            }
        }
        advanceUntilIdle()

        // Start the guided flow 
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow("bug-signup-flow")))
        advanceUntilIdle()

        // Check that BugTestSignUpScreen receives the modified parameters
        val stateAfterStart = store.selectState<NavigationState>().first()
        assertEquals(BugTestSignUpScreen, stateAfterStart.currentEntry.navigatable)
        
        // Verify modified parameters are applied
        val deeplinkParam = stateAfterStart.currentEntry.params.getString("DEEPLINK")
        assertEquals(uri, deeplinkParam, "SignUpScreen should receive modified DEEPLINK parameter")

        // Complete the flow (navigate past the final step)
        store.dispatch(NavigationAction.NextStep())
        advanceUntilIdle()

        // Verify modified onComplete handler was called
        assertTrue(modifiedOnCompleteTriggered, "Modified onComplete handler should be triggered")
        
        val finalState = store.selectState<NavigationState>().first()
        assertEquals(BugTestHomeScreen, finalState.currentEntry.navigatable, "Should navigate to HomeScreen from modified onComplete")
        assertEquals(null, finalState.activeGuidedFlowState, "Guided flow should be cleared after completion")
    }
}