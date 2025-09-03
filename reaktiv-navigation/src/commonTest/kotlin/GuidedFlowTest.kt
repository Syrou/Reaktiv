import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.GuidedFlow
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.model.ClearModificationBehavior
import io.github.syrou.reaktiv.navigation.model.GuidedFlowContext
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlin.reflect.KClass
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.extension.guidedFlow
import io.github.syrou.reaktiv.navigation.dsl.guidedFlow
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.startGuidedFlow
import io.github.syrou.reaktiv.navigation.extension.nextGuidedFlowStep
import kotlin.test.assertFalse

// Test module for conditional navigation testing
data class UserConditionState(
    val isVip: Boolean = false, 
    val hasCompletedTutorial: Boolean = false,
    val points: Int = 0
) : ModuleState

sealed class UserConditionAction(moduleTag: KClass<*>) : ModuleAction(moduleTag) {
    object MakeVip : UserConditionAction(UserConditionModule::class)
    object CompleteTutorial : UserConditionAction(UserConditionModule::class)
    data class AddPoints(val amount: Int) : UserConditionAction(UserConditionModule::class)
    object Reset : UserConditionAction(UserConditionModule::class)
}

object UserConditionModule : Module<UserConditionState, UserConditionAction> {
    override val initialState = UserConditionState()
    
    override val reducer = { state: UserConditionState, action: UserConditionAction ->
        when (action) {
            is UserConditionAction.MakeVip -> state.copy(isVip = true)
            is UserConditionAction.CompleteTutorial -> state.copy(hasCompletedTutorial = true)
            is UserConditionAction.AddPoints -> state.copy(points = state.points + action.amount)
            is UserConditionAction.Reset -> UserConditionState()
        }
    }
    
    override val createLogic = { _: StoreAccessor -> 
        object : ModuleLogic<UserConditionAction>() {
            override suspend fun invoke(action: ModuleAction) {}
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GuidedFlowTest {

    // Test screens
    object TestWelcomeScreen : Screen {
        override val route = "test-welcome"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false
        override val titleResource: TitleResource = { "Welcome" }
        @Composable
        override fun Content(params: Params) {}
    }

    object TestProfileScreen : Screen {
        override val route = "test-profile"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false
        override val titleResource: TitleResource = { "Profile" }
        @Composable
        override fun Content(params: Params) {}
    }

    object TestPreferencesScreen : Screen {
        override val route = "test-preferences"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false
        override val titleResource: TitleResource = { "Preferences" }
        @Composable
        override fun Content(params: Params) {}
    }

    object TestHomeScreen : Screen {
        override val route = "test-home"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false
        override val titleResource: TitleResource = { "Home" }
        @Composable
        override fun Content(params: Params) {}
    }

    private fun createTestNavigationModule() = createNavigationModule {
        rootGraph {
            startScreen(TestHomeScreen)
            screens(TestHomeScreen, TestWelcomeScreen, TestProfileScreen, TestPreferencesScreen)
        }
        
        guidedFlow("test-flow") {
            step<TestWelcomeScreen>()
            step<TestProfileScreen>()
            step<TestPreferencesScreen>()
            onComplete { storeAccessor ->
                storeAccessor.navigation {
                    navigateTo(TestHomeScreen.route)
                    clearBackStack()
                }
            }
        }
        
        guidedFlow("route-flow") {
            step("test-welcome")
            step("test-profile?source=guided").param("profileType", "basic")
            step("test-preferences")
            onComplete { storeAccessor ->
                storeAccessor.navigation {
                    navigateTo("test-home")
                    clearBackStack()
                }
            }
        }
        
        guidedFlow("builder-flow") {
            step<TestWelcomeScreen>()
            step("test-profile?source=guided").param("profileType", "basic")
            step<TestPreferencesScreen>().param("step", "3")
            onComplete { storeAccessor ->
                storeAccessor.navigation {
                    navigateTo("test-home")
                    clearBackStack()
                }
            }
        }
        
        guidedFlow("mixed-flow") {
            step<TestWelcomeScreen>().param("step", "1")
            step("test-profile?tab=settings")
            step<TestPreferencesScreen>().param("step", "3")
            onComplete { storeAccessor ->
                storeAccessor.navigation {
                    navigateTo("test-home")
                }
            }
        }
    }


    @Test
    fun `should create guided flow definition`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // GuidedFlow definitions are now part of module configuration
        // No need to create them at runtime - they're already configured
        val state = store.selectState<NavigationState>().first()
        
        // Flow should be available for starting
        assertTrue(state.activeGuidedFlowState == null) // No active flow yet
        assertEquals(TestHomeScreen.route, state.currentEntry.navigatable.route) // On start screen
    }

    @Test
    fun `should start guided flow and navigate to first step`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow (definition already configured in module)
        store.startGuidedFlow("test-flow")
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()

        // Should have active guided flow state
        assertNotNull(state.activeGuidedFlowState)
        assertEquals("test-flow", state.activeGuidedFlowState.flowRoute)
        assertEquals(0, state.activeGuidedFlowState.currentStepIndex)
        assertEquals(0.33f, state.activeGuidedFlowState.progress, 0.01f)
        assertTrue(state.activeGuidedFlowState.isOnFinalStep == false)
        assertTrue(state.activeGuidedFlowState.isCompleted == false)

        // Should navigate to first step
        assertEquals(TestWelcomeScreen, state.currentEntry.screen)
        
        // Should have guided flow context
        assertNotNull(state.currentEntry.guidedFlowContext)
        assertEquals("test-flow", state.currentEntry.guidedFlowContext.flowRoute)
        assertEquals(0, state.currentEntry.guidedFlowContext.stepIndex)
        assertEquals(3, state.currentEntry.guidedFlowContext.totalSteps)
    }

    @Test
    fun `should navigate to next step in guided flow`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow (definition already configured in module)
        store.startGuidedFlow("test-flow")
        advanceUntilIdle()

        // Move to next step
        store.nextGuidedFlowStep()
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()

        // Should update flow state
        assertNotNull(state.activeGuidedFlowState)
        assertEquals(1, state.activeGuidedFlowState.currentStepIndex)
        assertEquals(0.67f, state.activeGuidedFlowState.progress, 0.01f)
        assertTrue(state.activeGuidedFlowState.isOnFinalStep == false)

        // Should navigate to second step
        assertEquals(TestProfileScreen, state.currentEntry.screen)
        
        // Should update guided flow context
        assertNotNull(state.currentEntry.guidedFlowContext)
        assertEquals(1, state.currentEntry.guidedFlowContext.stepIndex)
    }

    @Test
    fun `should navigate to previous step in guided flow`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow and move to step 2 (definition already configured in module)
        store.startGuidedFlow("test-flow")
        advanceUntilIdle()
        store.nextGuidedFlowStep()
        advanceUntilIdle()

        // Move to previous step
        store.navigateBack()
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()

        // Should update flow state
        assertNotNull(state.activeGuidedFlowState)
        assertEquals(0, state.activeGuidedFlowState.currentStepIndex)
        assertEquals(0.33f, state.activeGuidedFlowState.progress, 0.01f)

        // Should navigate back to first step
        assertEquals(TestWelcomeScreen, state.currentEntry.screen)
        
        // Should update guided flow context
        assertNotNull(state.currentEntry.guidedFlowContext)
        assertEquals(0, state.currentEntry.guidedFlowContext.stepIndex)
    }

    @Test
    fun `should complete guided flow on final step`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow and navigate to final step (definition already configured in module)
        store.startGuidedFlow("test-flow")
        advanceUntilIdle()
        store.nextGuidedFlowStep() // Step 1
        advanceUntilIdle()
        store.nextGuidedFlowStep() // Step 2 (final)
        advanceUntilIdle()

        val stateBeforeCompletion = store.selectState<NavigationState>().first()
        assertEquals(TestPreferencesScreen, stateBeforeCompletion.currentEntry.screen)
        assertTrue(stateBeforeCompletion.activeGuidedFlowState?.isOnFinalStep == true)

        // Complete the flow
        store.nextGuidedFlowStep()
        advanceUntilIdle()

        val stateAfterCompletion = store.selectState<NavigationState>().first()

        // Should clear guided flow state
        assertNull(stateAfterCompletion.activeGuidedFlowState)

        // Should navigate to completion target (TestHomeScreen via onComplete block)
        assertEquals(TestHomeScreen, stateAfterCompletion.currentEntry.screen)
        
        // Should clear guided flow context
        assertNull(stateAfterCompletion.currentEntry.guidedFlowContext)
    }

    @Test
    fun `should handle guided flow with parameters`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow with parameters (definition already configured in module)
        store.startGuidedFlow(
            "test-flow", 
            Params.of("userId" to "123", "source" to "onboarding")
        )
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()

        // Should navigate with parameters
        assertEquals(TestWelcomeScreen, state.currentEntry.screen)
        assertTrue(state.currentEntry.params.containsKey("userId"))
        assertEquals("123", state.currentEntry.params["userId"])
        assertTrue(state.currentEntry.params.containsKey("source"))
        assertEquals("onboarding", state.currentEntry.params["source"])
    }

    @Test
    fun `should handle next step with parameters`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow (definition already configured in module)
        store.startGuidedFlow("test-flow")
        advanceUntilIdle()

        // Move to next step with parameters
        store.nextGuidedFlowStep(Params.of("stepData" to "profile_info"))
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()

        // Should navigate to next step with parameters
        assertEquals(TestProfileScreen, state.currentEntry.screen)
        assertTrue(state.currentEntry.params.containsKey("stepData"))
        assertEquals("profile_info", state.currentEntry.params["stepData"])
    }

    @Test
    fun `should exit guided flow when calling previous step from first step`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow (on first step) - definition already configured in module
        store.startGuidedFlow("test-flow")
        advanceUntilIdle()

        // Try to go to previous step (should exit the guided flow)
        store.navigateBack()
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()

        // Should have exited the guided flow and returned to previous screen
        assertNull(state.activeGuidedFlowState)
        // Should be back on the screen that was before the guided flow started
        assertEquals(TestHomeScreen, state.currentEntry.screen)
    }

    @Test
    fun `should calculate progress correctly`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow - definition already configured in module
        store.startGuidedFlow("test-flow")
        advanceUntilIdle()

        // Step 1 of 3 = 1/3 ≈ 0.33
        var state = store.selectState<NavigationState>().first()
        assertEquals(0.33f, state.activeGuidedFlowState?.progress ?: 0f, 0.01f)

        // Step 2 of 3 = 2/3 ≈ 0.67
        store.nextGuidedFlowStep()
        advanceUntilIdle()
        state = store.selectState<NavigationState>().first()
        assertEquals(0.67f, state.activeGuidedFlowState?.progress ?: 0f, 0.01f)

        // Step 3 of 3 = 3/3 = 1.0
        store.nextGuidedFlowStep()
        advanceUntilIdle()
        state = store.selectState<NavigationState>().first()
        assertEquals(1.0f, state.activeGuidedFlowState?.progress ?: 0f, 0.01f)
    }

    @Test
    fun `should track completion timing`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        val startTime = Clock.System.now()
        
        // Start and complete guided flow
        // Start guided flow - definition already configured in module
        store.startGuidedFlow("test-flow")
        advanceUntilIdle()
        
        var state = store.selectState<NavigationState>().first()
        assertNotNull(state.activeGuidedFlowState?.startedAt)
        assertTrue(state.activeGuidedFlowState?.startedAt!! >= startTime)
        assertNull(state.activeGuidedFlowState?.completedAt)
        assertNull(state.activeGuidedFlowState?.duration)
        assertEquals(false, state.activeGuidedFlowState?.isCompleted)

        // Complete the flow
        store.nextGuidedFlowStep() // Step 1
        advanceUntilIdle()
        store.nextGuidedFlowStep() // Step 2
        advanceUntilIdle()
        store.nextGuidedFlowStep() // Complete
        advanceUntilIdle()

        // Flow state should be cleared after completion, but we can test the timing logic
        // by checking that the flow was properly completed before clearing
        val finalState = store.selectState<NavigationState>().first()
        assertNull(finalState.activeGuidedFlowState) // Should be cleared
    }

    @Test
    fun `should handle route-based guided flow with query parameters`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start route-based guided flow - definition already configured in module
        store.startGuidedFlow("route-flow")
        advanceUntilIdle()

        var state = store.selectState<NavigationState>().first()
        
        // Should navigate to first step (test-welcome)
        assertEquals(TestWelcomeScreen, state.currentEntry.screen)
        
        // Move to next step with query parameters and additional params
        store.nextGuidedFlowStep()
        advanceUntilIdle()
        
        state = store.selectState<NavigationState>().first()
        
        // Should navigate to profile with query params and step params
        assertEquals(TestProfileScreen, state.currentEntry.screen)
        assertTrue(state.currentEntry.params.containsKey("source"))
        assertEquals("guided", state.currentEntry.params["source"]) // From query string
        assertTrue(state.currentEntry.params.containsKey("profileType"))
        assertEquals("basic", state.currentEntry.params["profileType"]) // From step params
    }

    @Test
    fun `should create guided flow using builder DSL`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // GuidedFlow using builder DSL is now configured at module creation time
        // Test that the flow can be started successfully
        store.startGuidedFlow("builder-flow")
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        
        // Should start the flow and navigate to first step
        assertNotNull(state.activeGuidedFlowState)
        assertEquals("builder-flow", state.activeGuidedFlowState.flowRoute)
        assertEquals(0, state.activeGuidedFlowState.currentStepIndex)
        assertEquals(TestWelcomeScreen, state.currentEntry.navigatable)
    }

    @Test
    fun `should handle mixed typed and route steps`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start mixed flow - definition already configured in module
        store.startGuidedFlow("mixed-flow")
        advanceUntilIdle()

        var state = store.selectState<NavigationState>().first()
        
        // First step: typed screen with params
        assertEquals(TestWelcomeScreen, state.currentEntry.screen)
        assertEquals("1", state.currentEntry.params["step"])
        
        // Move to second step: route with query params
        store.nextGuidedFlowStep()
        advanceUntilIdle()
        
        state = store.selectState<NavigationState>().first()
        assertEquals(TestProfileScreen, state.currentEntry.screen)
        assertEquals("settings", state.currentEntry.params["tab"])
        
        // Move to third step: typed screen with params
        store.nextGuidedFlowStep()
        advanceUntilIdle()
        
        state = store.selectState<NavigationState>().first()
        assertEquals(TestPreferencesScreen, state.currentEntry.screen)
        assertEquals("3", state.currentEntry.params["step"])
    }

    @Test
    fun `should access state in onComplete for conditional navigation`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        
        val conditionalNavigationModule = createNavigationModule {
            rootGraph {
                startScreen(TestHomeScreen)
                screens(TestHomeScreen, TestWelcomeScreen, TestProfileScreen, TestPreferencesScreen)
            }
            
            // Flow with conditional navigation based on user state
            guidedFlow("conditional-flow") {
                step<TestWelcomeScreen>()
                step<TestProfileScreen>()
                onComplete { storeAccessor ->
                    // Access user condition state for conditional navigation
                    val userState = storeAccessor.selectState<UserConditionState>().first()
                    
                    storeAccessor.navigation {
                        when {
                            userState.isVip && userState.hasCompletedTutorial && userState.points >= 100 -> {
                                // Premium VIP users with high points go to preferences
                                navigateTo(TestPreferencesScreen.route)
                            }
                            userState.isVip -> {
                                // VIP users go to profile  
                                navigateTo(TestProfileScreen.route)
                            }
                            userState.hasCompletedTutorial -> {
                                // Users who completed tutorial go to welcome (advanced section)
                                navigateTo(TestWelcomeScreen.route)
                            }
                            else -> {
                                // Regular users go to home
                                navigateTo(TestHomeScreen.route)
                            }
                        }
                        clearBackStack()
                    }
                }
            }
        }
        
        val store = createStore {
            module(UserConditionModule)
            module(conditionalNavigationModule)
            coroutineContext(testDispatcher)
        }

        // Test Case 1: Regular user (no special conditions) -> should go to home
        store.startGuidedFlow("conditional-flow")
        advanceUntilIdle()
        
        // Navigate through flow steps
        store.nextGuidedFlowStep() // To profile
        advanceUntilIdle()
        store.nextGuidedFlowStep() // Complete flow
        advanceUntilIdle()
        
        var state = store.selectState<NavigationState>().first()
        assertEquals(TestHomeScreen, state.currentEntry.screen, "Regular user should navigate to home")
        assertNull(state.activeGuidedFlowState, "Flow should be completed")

        // Test Case 2: User with completed tutorial -> should go to welcome (advanced)
        store.dispatch(UserConditionAction.Reset) // Reset state
        advanceUntilIdle()
        store.dispatch(UserConditionAction.CompleteTutorial)
        advanceUntilIdle()
        
        store.startGuidedFlow("conditional-flow")
        advanceUntilIdle()
        store.nextGuidedFlowStep() // To profile
        advanceUntilIdle()
        store.nextGuidedFlowStep() // Complete flow
        advanceUntilIdle()
        
        state = store.selectState<NavigationState>().first()
        assertEquals(TestWelcomeScreen, state.currentEntry.screen, "Tutorial completed user should navigate to welcome")

        // Test Case 3: VIP user -> should go to profile
        store.dispatch(UserConditionAction.Reset) // Reset state
        advanceUntilIdle()
        store.dispatch(UserConditionAction.MakeVip)
        advanceUntilIdle()
        
        store.startGuidedFlow("conditional-flow")
        advanceUntilIdle()
        store.nextGuidedFlowStep() // To profile
        advanceUntilIdle()
        store.nextGuidedFlowStep() // Complete flow
        advanceUntilIdle()
        
        state = store.selectState<NavigationState>().first()
        assertEquals(TestProfileScreen, state.currentEntry.screen, "VIP user should navigate to profile")

        // Test Case 4: Premium VIP user (VIP + tutorial + high points) -> should go to preferences
        store.dispatch(UserConditionAction.Reset) // Reset state
        advanceUntilIdle()
        store.dispatch(UserConditionAction.MakeVip)
        advanceUntilIdle()
        store.dispatch(UserConditionAction.CompleteTutorial)
        advanceUntilIdle()
        store.dispatch(UserConditionAction.AddPoints(150))
        advanceUntilIdle()
        
        store.startGuidedFlow("conditional-flow")
        advanceUntilIdle()
        store.nextGuidedFlowStep() // To profile
        advanceUntilIdle()
        store.nextGuidedFlowStep() // Complete flow
        advanceUntilIdle()
        
        state = store.selectState<NavigationState>().first()
        assertEquals(TestPreferencesScreen, state.currentEntry.screen, "Premium VIP user should navigate to preferences")
        
        // Verify the user state was correctly accessed
        val finalUserState = store.selectState<UserConditionState>().first()
        assertTrue(finalUserState.isVip, "User should be VIP")
        assertTrue(finalUserState.hasCompletedTutorial, "User should have completed tutorial")
        assertTrue(finalUserState.points >= 100, "User should have high points")
    }

    @Test
    fun `test guided flow parameters with content URI preserves encoding`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val contentUri = "content://com.google.android.apps.docs.storage/document/acc%3D9%3Bdoc%3Dencoded%3Dq3kaiKNyhntVpRozJ-eI-R1HG6TXaN_W7vafc216hOscCol3D2WPTL4kvwY%3D"
        
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }
        
        // Start guided flow with content URI as a parameter
        store.startGuidedFlow(
            "test-flow",
            Params.of("documentUri" to contentUri, "action" to "upload")
        )
        advanceUntilIdle()
        
        var state = store.selectState<NavigationState>().first()
        val firstStepEntry = state.currentEntry
        
        // Verify the content URI parameter was preserved correctly
        assertEquals(TestWelcomeScreen, firstStepEntry.screen)
        
        // Use the proper parameter access method that handles decoding
        assertEquals(contentUri, firstStepEntry.params.getString("documentUri"))
        assertEquals("upload", firstStepEntry.params.getString("action"))
        
        // Verify the URI structure is intact using the decoded value
        val retrievedUri = firstStepEntry.params.getString("documentUri")!!
        assertTrue(retrievedUri.startsWith("content://"))
        assertTrue(retrievedUri.contains("acc%3D9%3Bdoc%3Dencoded%3D"))
        assertTrue(retrievedUri.contains("%3D")) // Equals signs remain encoded in original URI
        assertTrue(retrievedUri.contains("%3B")) // Semicolons remain encoded in original URI
        
        // Move to next step with additional content URI parameters
        store.nextGuidedFlowStep(Params.of("documentUri" to contentUri, "step" to "process"))
        advanceUntilIdle()
        
        state = store.selectState<NavigationState>().first()
        val secondStepEntry = state.currentEntry
        
        // Verify second step also preserves the content URI using proper decoding
        assertEquals(TestProfileScreen, secondStepEntry.screen)
        assertEquals(contentUri, secondStepEntry.params.getString("documentUri"))
        assertEquals("process", secondStepEntry.params.getString("step"))
        
        // Final verification that the URI remains intact throughout the flow
        val finalUri = secondStepEntry.params.getString("documentUri")!!
        assertEquals(contentUri, finalUri)
    }

    @Test
    fun `should use modified step parameters and onComplete handler`() = runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        var modifiedOnCompleteTriggered = false
        
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        val uri = "https://example.com/invite"
        
        // Modify the guided flow with step parameters AND onComplete handler
        store.guidedFlow("test-flow") {
            updateStepParams<TestWelcomeScreen> {
                putString("DEEPLINK", uri)
            }
            updateOnComplete { storeAccessor ->
                modifiedOnCompleteTriggered = true
                
                storeAccessor.navigation {
                    navigateTo(TestPreferencesScreen.route)
                }
            }
        }
        advanceUntilIdle()

        // Start the guided flow 
        store.startGuidedFlow("test-flow")
        advanceUntilIdle()

        // Check that TestWelcomeScreen receives the modified parameters
        val stateAfterStart = store.selectState<NavigationState>().first()
        assertEquals(TestWelcomeScreen, stateAfterStart.currentEntry.navigatable)
        
        // Verify modified parameters are applied
        val deeplinkParam = stateAfterStart.currentEntry.params.getString("DEEPLINK")
        assertEquals(uri, deeplinkParam, "TestWelcomeScreen should receive modified DEEPLINK parameter")

        // Complete the flow (navigate past all steps)
        store.nextGuidedFlowStep() // To TestProfileScreen
        advanceUntilIdle()
        store.nextGuidedFlowStep() // To TestPreferencesScreen
        advanceUntilIdle()
        store.nextGuidedFlowStep() // Complete the flow
        advanceUntilIdle()

        // Verify modified onComplete handler was called
        assertTrue(modifiedOnCompleteTriggered, "Modified onComplete handler should be triggered")
        
        val finalState = store.selectState<NavigationState>().first()
        assertEquals(TestPreferencesScreen, finalState.currentEntry.navigatable, "Should navigate to TestPreferencesScreen from modified onComplete")
        assertEquals(null, finalState.activeGuidedFlowState, "Guided flow should be cleared after completion")
    }

    @Test
    fun `should reset to original flow definition after completion and work correctly on subsequent runs`() = runTest(timeout = 15.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        var firstRunOnCompleteTriggered = false
        var secondRunOnCompleteTriggered = false
        
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        val uri = "https://example.com/invite"
        
        // FIRST RUN: Modify the guided flow with step parameters AND onComplete handler
        store.guidedFlow("test-flow") {
            updateStepParams<TestWelcomeScreen> {
                putString("DEEPLINK", uri)
                putString("RUN", "FIRST")
            }
            updateOnComplete { storeAccessor ->
                firstRunOnCompleteTriggered = true
                storeAccessor.navigation {
                    navigateTo(TestPreferencesScreen.route)
                }
            }
        }
        advanceUntilIdle()

        // Start and complete the first guided flow run
        store.startGuidedFlow("test-flow")
        advanceUntilIdle()

        // Verify first run has modified parameters
        val stateAfterFirstStart = store.selectState<NavigationState>().first()
        assertEquals(TestWelcomeScreen, stateAfterFirstStart.currentEntry.navigatable)
        assertEquals(uri, stateAfterFirstStart.currentEntry.params.getString("DEEPLINK"), "First run should have modified DEEPLINK")
        assertEquals("FIRST", stateAfterFirstStart.currentEntry.params.getString("RUN"), "First run should have RUN=FIRST")

        // Complete the first flow (navigate through all steps)
        store.nextGuidedFlowStep() // To TestProfileScreen
        advanceUntilIdle()
        store.nextGuidedFlowStep() // To TestPreferencesScreen
        advanceUntilIdle()
        store.nextGuidedFlowStep() // Complete the flow
        advanceUntilIdle()

        // Verify first run completed correctly
        assertTrue(firstRunOnCompleteTriggered, "First run modified onComplete should be triggered")
        val stateAfterFirstCompletion = store.selectState<NavigationState>().first()
        assertEquals(TestPreferencesScreen, stateAfterFirstCompletion.currentEntry.navigatable, "First run should navigate to TestPreferencesScreen")
        assertEquals(null, stateAfterFirstCompletion.activeGuidedFlowState, "Flow should be cleared after first completion")

        // SECOND RUN: Start the same guided flow again WITHOUT any modifications
        // This should use the original, unmodified definition
        store.startGuidedFlow("test-flow")
        advanceUntilIdle()

        // Verify second run has NO modifications (clean slate)
        val stateAfterSecondStart = store.selectState<NavigationState>().first()
        assertEquals(TestWelcomeScreen, stateAfterSecondStart.currentEntry.navigatable)
        assertEquals(null, stateAfterSecondStart.currentEntry.params.getString("DEEPLINK"), "Second run should NOT have DEEPLINK parameter")
        assertEquals(null, stateAfterSecondStart.currentEntry.params.getString("RUN"), "Second run should NOT have RUN parameter")

        // Complete the second flow - should use original onComplete (navigates to TestHomeScreen)
        store.nextGuidedFlowStep() // To TestProfileScreen
        advanceUntilIdle()
        store.nextGuidedFlowStep() // To TestPreferencesScreen
        advanceUntilIdle()
        store.nextGuidedFlowStep() // Complete the flow
        advanceUntilIdle()

        // Verify second run used original onComplete handler (navigates to TestHomeScreen with clearBackStack)
        assertFalse(secondRunOnCompleteTriggered, "Second run should NOT trigger modified onComplete")
        val stateAfterSecondCompletion = store.selectState<NavigationState>().first()
        assertEquals(null, stateAfterSecondCompletion.activeGuidedFlowState, "Flow should be cleared after second completion")

        // The second run should navigate to TestHomeScreen (original onComplete behavior)
        assertEquals(TestHomeScreen, stateAfterSecondCompletion.currentEntry.navigatable, "Second run should navigate to TestHomeScreen (original behavior)")

        // THIRD RUN: Apply different modifications and verify they work
        store.guidedFlow("test-flow") {
            updateStepParams<TestWelcomeScreen> {
                putString("DEEPLINK", "https://different.com")
                putString("RUN", "THIRD")
            }
            updateOnComplete { storeAccessor ->
                secondRunOnCompleteTriggered = true
                storeAccessor.navigation {
                    navigateTo(TestProfileScreen.route) // Navigate to different screen
                }
            }
        }
        advanceUntilIdle()

        // Start and complete third run
        store.startGuidedFlow("test-flow")
        advanceUntilIdle()

        // Verify third run has new modifications
        val stateAfterThirdStart = store.selectState<NavigationState>().first()
        assertEquals("https://different.com", stateAfterThirdStart.currentEntry.params.getString("DEEPLINK"), "Third run should have new DEEPLINK")
        assertEquals("THIRD", stateAfterThirdStart.currentEntry.params.getString("RUN"), "Third run should have RUN=THIRD")

        // Complete third run
        store.nextGuidedFlowStep() // To TestProfileScreen
        advanceUntilIdle()
        store.nextGuidedFlowStep() // To TestPreferencesScreen
        advanceUntilIdle()
        store.nextGuidedFlowStep() // Complete the flow
        advanceUntilIdle()

        // Verify third run completed with new modifications
        assertTrue(secondRunOnCompleteTriggered, "Third run modified onComplete should be triggered")
        val finalState = store.selectState<NavigationState>().first()
        assertEquals(TestProfileScreen, finalState.currentEntry.navigatable, "Third run should navigate to TestProfileScreen")
        assertEquals(null, finalState.activeGuidedFlowState, "Flow should be cleared after third completion")
    }
    
    @Test
    fun `should apply modifications and navigate in same DSL call - reproduce user bug`() = runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        var modifiedOnCompleteTriggered = false
        
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        val uri = "https://example.com/deeplink"
        
        // FIRST: Setup the modifications (simulate setupLoggedOutDeeplinkFlows)
        store.guidedFlow("test-flow") {
            updateStepParams<TestProfileScreen> {  // This is step 1 in our 3-step flow
                putString("DEEPLINK", uri)
            }
            updateOnComplete { storeAccessor ->
                modifiedOnCompleteTriggered = true
                storeAccessor.navigation {
                    navigateTo(TestPreferencesScreen.route)
                }
            }
        }
        advanceUntilIdle()

        // SECOND: Start the guided flow 
        store.startGuidedFlow("test-flow")
        advanceUntilIdle()

        // Should be on TestWelcomeScreen (step 0)
        var state = store.selectState<NavigationState>().first()
        assertEquals(TestWelcomeScreen, state.currentEntry.navigatable)
        assertEquals(0, state.activeGuidedFlowState?.currentStepIndex)

        // THIRD: This is the key part - simulate being on step 0 and calling nextStep with the flow
        // This simulates your scenario: from SignUpPreCheckScreen calling storeAccessor.guidedFlow(Route.SignupFlow){ nextStep() }
        store.guidedFlow("test-flow") {
            nextStep()  // Should navigate to TestProfileScreen WITH the modified DEEPLINK parameter
        }
        advanceUntilIdle()

        // VERIFY: TestProfileScreen should receive the modified DEEPLINK parameter
        state = store.selectState<NavigationState>().first()
        assertEquals(TestProfileScreen, state.currentEntry.navigatable, "Should navigate to TestProfileScreen (step 1)")
        assertEquals(1, state.activeGuidedFlowState?.currentStepIndex)
        
        // This is the critical assertion - the DEEPLINK parameter should be present
        val deeplinkParam = state.currentEntry.params.getString("DEEPLINK")
        assertEquals(uri, deeplinkParam, "TestProfileScreen should receive the modified DEEPLINK parameter from the guided flow DSL")
    }
    
    
    @Test
    fun `should apply modified step parameters to all steps - reproduce user exact scenario`() = runTest(timeout = 15.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        val uri = "https://example.com/deeplink"
        val sessionId = "session-123"
        val userId = "user-456"
        
        // STEP 1: From another place, modify the guided flow to add parameters to multiple steps
        // This simulates the user's setupLoggedOutDeeplinkFlows scenario
        store.guidedFlow("test-flow") {
            updateStepParams<TestWelcomeScreen> {  // Step 0 - first step
                putString("DEEPLINK", uri)
                putString("SESSION_ID", sessionId)
            }
            updateStepParams<TestProfileScreen> {  // Step 1 - middle step  
                putString("USER_ID", userId)
                putString("DEEPLINK", uri)
            }
            updateStepParams<TestPreferencesScreen> {  // Step 2 - last step
                putString("FINAL_STEP", "true")
                putString("DEEPLINK", uri)
            }
        }
        advanceUntilIdle()

        // STEP 2: Start the guided flow (simulates StartGuidedFlow from Start screen)
        store.startGuidedFlow("test-flow")
        advanceUntilIdle()

        // STEP 3: Verify first step (TestWelcomeScreen) receives modified parameters
        var state = store.selectState<NavigationState>().first()
        assertEquals(TestWelcomeScreen, state.currentEntry.navigatable)
        assertEquals(0, state.activeGuidedFlowState?.currentStepIndex)
        
        // This should work but currently doesn't - the core bug
        val welcomeDeeplink = state.currentEntry.params.getString("DEEPLINK")
        val welcomeSessionId = state.currentEntry.params.getString("SESSION_ID")
        assertEquals(uri, welcomeDeeplink, "TestWelcomeScreen should receive modified DEEPLINK parameter")
        assertEquals(sessionId, welcomeSessionId, "TestWelcomeScreen should receive modified SESSION_ID parameter")

        // STEP 4: Navigate to second step (TestProfileScreen)
        store.nextGuidedFlowStep()
        advanceUntilIdle()
        
        // Verify second step receives modified parameters
        state = store.selectState<NavigationState>().first()
        assertEquals(TestProfileScreen, state.currentEntry.navigatable)
        assertEquals(1, state.activeGuidedFlowState?.currentStepIndex)
        
        val profileUserId = state.currentEntry.params.getString("USER_ID")
        val profileDeeplink = state.currentEntry.params.getString("DEEPLINK")
        assertEquals(userId, profileUserId, "TestProfileScreen should receive modified USER_ID parameter")
        assertEquals(uri, profileDeeplink, "TestProfileScreen should receive modified DEEPLINK parameter")

        // STEP 5: Navigate to third step (TestPreferencesScreen)  
        store.nextGuidedFlowStep()
        advanceUntilIdle()
        
        // Verify third step receives modified parameters
        state = store.selectState<NavigationState>().first()
        assertEquals(TestPreferencesScreen, state.currentEntry.navigatable)
        assertEquals(2, state.activeGuidedFlowState?.currentStepIndex)
        
        val prefFinalStep = state.currentEntry.params.getString("FINAL_STEP")
        val prefDeeplink = state.currentEntry.params.getString("DEEPLINK")
        assertEquals("true", prefFinalStep, "TestPreferencesScreen should receive modified FINAL_STEP parameter")
        assertEquals(uri, prefDeeplink, "TestPreferencesScreen should receive modified DEEPLINK parameter")
    }

    @Test
    fun `should handle multiple flows with different modifications simultaneously`() = runTest(timeout = 15.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        var signupOnCompleteTriggered = false
        var onboardingOnCompleteTriggered = false
        
        // Create navigation module with CLEAR_NONE flows for this test
        val testNavigationModule = createNavigationModule {
            rootGraph {
                startScreen(TestHomeScreen)
                screens(TestHomeScreen, TestWelcomeScreen, TestProfileScreen, TestPreferencesScreen)
            }
            
            guidedFlow("test-flow") {
                step<TestWelcomeScreen>()
                step<TestProfileScreen>()
                step<TestPreferencesScreen>()
                clearModificationsOnComplete(ClearModificationBehavior.CLEAR_NONE)
                onComplete { storeAccessor ->
                    storeAccessor.navigation {
                        navigateTo(TestHomeScreen.route)
                        clearBackStack()
                    }
                }
            }
            
            guidedFlow("builder-flow") {
                step<TestWelcomeScreen>()
                step("test-profile?source=guided").param("profileType", "basic")
                step<TestPreferencesScreen>().param("step", "3")
                clearModificationsOnComplete(ClearModificationBehavior.CLEAR_NONE)
                onComplete { storeAccessor ->
                    storeAccessor.navigation {
                        navigateTo("test-home")
                        clearBackStack()
                    }
                }
            }
        }
        
        val store = createStore {
            module(testNavigationModule)
            coroutineContext(testDispatcher)
        }

        val signupDeeplink = "https://example.com/signup"
        val onboardingDeeplink = "https://example.com/onboarding"
        
        // SCENARIO: Modify two different flows with same screen but different parameters
        
        // Modify signup-flow - modifications will persist based on flow definition
        store.guidedFlow("test-flow") {
            updateStepParams<TestWelcomeScreen> {
                putString("DEEPLINK", signupDeeplink)
                putString("FLOW_TYPE", "SIGNUP")
                putString("USER_ID", "signup-123")
            }
            updateOnComplete { storeAccessor ->
                signupOnCompleteTriggered = true
                storeAccessor.navigation {
                    navigateTo(TestProfileScreen.route)
                }
            }
        }
        advanceUntilIdle()

        // Modify builder-flow (different flow, same screen type but different params)
        store.guidedFlow("builder-flow") {
            updateStepParams<TestWelcomeScreen> {
                putString("DEEPLINK", onboardingDeeplink)
                putString("FLOW_TYPE", "ONBOARDING")  
                putString("USER_ID", "onboarding-456")
            }
            updateOnComplete { storeAccessor ->
                onboardingOnCompleteTriggered = true
                storeAccessor.navigation {
                    navigateTo(TestPreferencesScreen.route)
                }
            }
        }
        advanceUntilIdle()

        // Verify both flows have stored their modifications separately
        val stateAfterModifications = store.selectState<NavigationState>().first()
        assertTrue(stateAfterModifications.guidedFlowModifications.containsKey("test-flow"), "Should have modifications for test-flow")
        assertTrue(stateAfterModifications.guidedFlowModifications.containsKey("builder-flow"), "Should have modifications for builder-flow")

        // TEST 1: Start and complete signup flow - should use signup modifications
        store.startGuidedFlow("test-flow")
        advanceUntilIdle()

        var state = store.selectState<NavigationState>().first()
        assertEquals(TestWelcomeScreen, state.currentEntry.navigatable, "Should start on TestWelcomeScreen")
        
        // Verify signup-specific parameters
        assertEquals(signupDeeplink, state.currentEntry.params.getString("DEEPLINK"), "Should have signup deeplink")
        assertEquals("SIGNUP", state.currentEntry.params.getString("FLOW_TYPE"), "Should have signup flow type")
        assertEquals("signup-123", state.currentEntry.params.getString("USER_ID"), "Should have signup user ID")

        // Complete signup flow
        store.nextGuidedFlowStep() // To TestProfileScreen
        advanceUntilIdle()
        store.nextGuidedFlowStep() // To TestPreferencesScreen
        advanceUntilIdle()
        store.nextGuidedFlowStep() // Complete flow
        advanceUntilIdle()

        // Verify signup completion
        assertTrue(signupOnCompleteTriggered, "Signup onComplete should be triggered")
        assertFalse(onboardingOnCompleteTriggered, "Onboarding onComplete should NOT be triggered yet")
        
        state = store.selectState<NavigationState>().first()
        assertEquals(TestProfileScreen, state.currentEntry.navigatable, "Should navigate to TestProfileScreen (signup completion)")
        assertEquals(null, state.activeGuidedFlowState, "Signup flow should be cleared")

        // TEST 2: Start and complete onboarding flow - should use onboarding modifications
        store.startGuidedFlow("builder-flow")
        advanceUntilIdle()

        state = store.selectState<NavigationState>().first()
        assertEquals(TestWelcomeScreen, state.currentEntry.navigatable, "Should start on TestWelcomeScreen for onboarding")
        
        // Verify onboarding-specific parameters (different from signup!)
        assertEquals(onboardingDeeplink, state.currentEntry.params.getString("DEEPLINK"), "Should have onboarding deeplink")
        assertEquals("ONBOARDING", state.currentEntry.params.getString("FLOW_TYPE"), "Should have onboarding flow type")
        assertEquals("onboarding-456", state.currentEntry.params.getString("USER_ID"), "Should have onboarding user ID")

        // Complete onboarding flow
        store.nextGuidedFlowStep() // To TestProfileScreen  
        advanceUntilIdle()
        store.nextGuidedFlowStep() // To TestPreferencesScreen
        advanceUntilIdle()
        store.nextGuidedFlowStep() // Complete flow
        advanceUntilIdle()

        // Verify onboarding completion
        assertTrue(onboardingOnCompleteTriggered, "Onboarding onComplete should be triggered")
        
        state = store.selectState<NavigationState>().first()
        assertEquals(TestPreferencesScreen, state.currentEntry.navigatable, "Should navigate to TestPreferencesScreen (onboarding completion)")
        assertEquals(null, state.activeGuidedFlowState, "Onboarding flow should be cleared")

        // TEST 3: Verify modifications are preserved since both flows use CLEAR_NONE
        val finalState = store.selectState<NavigationState>().first()
        assertTrue(finalState.guidedFlowModifications.containsKey("test-flow"), "test-flow modifications should be preserved with CLEAR_NONE")
        assertTrue(finalState.guidedFlowModifications.containsKey("builder-flow"), "builder-flow modifications should be preserved with CLEAR_NONE")
    }

    @Test
    fun `should handle multiple flows with same screen type but different step parameters`() = runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        
        // Create navigation module with CLEAR_NONE flows for this test
        val testNavigationModule = createNavigationModule {
            rootGraph {
                startScreen(TestHomeScreen)
                screens(TestHomeScreen, TestWelcomeScreen, TestProfileScreen, TestPreferencesScreen)
            }
            
            guidedFlow("test-flow") {
                step<TestWelcomeScreen>()
                step<TestProfileScreen>()
                step<TestPreferencesScreen>()
                clearModificationsOnComplete(ClearModificationBehavior.CLEAR_NONE)
                onComplete { storeAccessor ->
                    storeAccessor.navigation {
                        navigateTo(TestHomeScreen.route)
                        clearBackStack()
                    }
                }
            }
            
            guidedFlow("builder-flow") {
                step<TestWelcomeScreen>()
                step("test-profile?source=guided").param("profileType", "basic")
                step<TestPreferencesScreen>().param("step", "3")
                clearModificationsOnComplete(ClearModificationBehavior.CLEAR_NONE)
                onComplete { storeAccessor ->
                    storeAccessor.navigation {
                        navigateTo("test-home")
                        clearBackStack()
                    }
                }
            }
        }
        
        val store = createStore {
            module(testNavigationModule)
            coroutineContext(testDispatcher)
        }

        // SCENARIO: Two flows both have TestProfileScreen but with different step-level modifications

        // Flow 1: Modify TestProfileScreen for test-flow
        store.guidedFlow("test-flow") {
            updateStepParams<TestProfileScreen> {  // This is step 1 in test-flow
                putString("SCREEN_TYPE", "SIGNUP_PROFILE")
                putString("THEME", "DARK")
                putInt("VERSION", 1)
            }
        }
        advanceUntilIdle()

        // Flow 2: Modify TestProfileScreen for builder-flow  
        store.guidedFlow("builder-flow") {
            updateStepParams<TestProfileScreen> {  // This is step 1 in builder-flow
                putString("SCREEN_TYPE", "ONBOARDING_PROFILE")
                putString("THEME", "LIGHT")
                putInt("VERSION", 2)
                putBoolean("SHOW_TUTORIAL", true)
            }
        }
        advanceUntilIdle()

        // Verify both modifications are stored separately
        val stateAfterMods = store.selectState<NavigationState>().first()
        assertEquals(2, stateAfterMods.guidedFlowModifications.size, "Should have modifications for both flows")

        // TEST: Start test-flow and verify it gets test-flow's parameters
        store.startGuidedFlow("test-flow")
        advanceUntilIdle()
        store.nextGuidedFlowStep() // Move to TestProfileScreen (step 1)
        advanceUntilIdle()

        var state = store.selectState<NavigationState>().first()
        assertEquals(TestProfileScreen, state.currentEntry.navigatable, "Should be on TestProfileScreen")
        assertEquals("SIGNUP_PROFILE", state.currentEntry.params.getString("SCREEN_TYPE"), "Should have test-flow's screen type")
        assertEquals("DARK", state.currentEntry.params.getString("THEME"), "Should have test-flow's theme")
        assertEquals(1, state.currentEntry.params.getInt("VERSION"), "Should have test-flow's version")
        assertEquals(null, state.currentEntry.params.getBoolean("SHOW_TUTORIAL"), "Should NOT have route-flow's tutorial flag")

        // Complete test-flow
        store.nextGuidedFlowStep() // To TestPreferencesScreen
        advanceUntilIdle()
        store.nextGuidedFlowStep() // Complete
        advanceUntilIdle()

        // TEST: Start builder-flow and verify it gets builder-flow's parameters
        store.startGuidedFlow("builder-flow")
        advanceUntilIdle()
        store.nextGuidedFlowStep() // Move to TestProfileScreen (step 1)
        advanceUntilIdle()

        state = store.selectState<NavigationState>().first()
        assertEquals(TestProfileScreen, state.currentEntry.navigatable, "Should be on TestProfileScreen")
        assertEquals("ONBOARDING_PROFILE", state.currentEntry.params.getString("SCREEN_TYPE"), "Should have builder-flow's screen type")
        assertEquals("LIGHT", state.currentEntry.params.getString("THEME"), "Should have builder-flow's theme")
        assertEquals(2, state.currentEntry.params.getInt("VERSION"), "Should have builder-flow's version")
        assertEquals(true, state.currentEntry.params.getBoolean("SHOW_TUTORIAL"), "Should have builder-flow's tutorial flag")
    }

    @Test
    fun `should allow modifying multiple flows simultaneously without interference`() = runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        
        // Create navigation module with CLEAR_NONE flows for this test
        val testNavigationModule = createNavigationModule {
            rootGraph {
                startScreen(TestHomeScreen)
                screens(TestHomeScreen, TestWelcomeScreen, TestProfileScreen, TestPreferencesScreen)
            }
            
            guidedFlow("test-flow") {
                step<TestWelcomeScreen>()
                step<TestProfileScreen>()
                step<TestPreferencesScreen>()
                clearModificationsOnComplete(ClearModificationBehavior.CLEAR_NONE)
                onComplete { storeAccessor ->
                    storeAccessor.navigation {
                        navigateTo(TestHomeScreen.route)
                        clearBackStack()
                    }
                }
            }
            
            guidedFlow("route-flow") {
                step("test-welcome")
                step("test-profile?source=guided").param("profileType", "basic")
                step("test-preferences")
                clearModificationsOnComplete(ClearModificationBehavior.CLEAR_NONE)
                onComplete { storeAccessor ->
                    storeAccessor.navigation {
                        navigateTo("test-home")
                        clearBackStack()
                    }
                }
            }
            
            guidedFlow("builder-flow") {
                step<TestWelcomeScreen>()
                step("test-profile?source=guided").param("profileType", "basic")
                step<TestPreferencesScreen>().param("step", "3")
                clearModificationsOnComplete(ClearModificationBehavior.CLEAR_NONE)
                onComplete { storeAccessor ->
                    storeAccessor.navigation {
                        navigateTo("test-home")
                        clearBackStack()
                    }
                }
            }
        }
        
        val store = createStore {
            module(testNavigationModule)
            coroutineContext(testDispatcher)
        }

        // SCENARIO: Rapidly modify multiple flows and verify they don't interfere with each other

        // Modify multiple flows in quick succession
        store.guidedFlow("test-flow") {
            updateStepParams<TestWelcomeScreen> { putString("FLOW_ID", "TEST_FLOW_1") }
            updateStepParams<TestProfileScreen> { putString("PROFILE_TYPE", "TEST") }
        }
        
        store.guidedFlow("route-flow") {
            updateStepParams<TestWelcomeScreen> { putString("FLOW_ID", "ROUTE_FLOW_1") }
            updateStepParams<TestProfileScreen> { putString("PROFILE_TYPE", "ROUTE") }
        }
        
        store.guidedFlow("builder-flow") {
            updateStepParams<TestWelcomeScreen> { putString("FLOW_ID", "BUILDER_FLOW_1") }
            updateStepParams<TestProfileScreen> { putString("PROFILE_TYPE", "BUILDER") }
        }
        
        advanceUntilIdle()

        // Verify all three flows have their modifications stored
        val state = store.selectState<NavigationState>().first()
        assertEquals(3, state.guidedFlowModifications.size, "Should have modifications for all 3 flows")
        assertTrue(state.guidedFlowModifications.containsKey("test-flow"), "Should have test-flow modifications")
        assertTrue(state.guidedFlowModifications.containsKey("route-flow"), "Should have route-flow modifications") 
        assertTrue(state.guidedFlowModifications.containsKey("builder-flow"), "Should have builder-flow modifications")

        // TEST: Start each flow and verify they have their own parameters

        // Test flow 1
        store.startGuidedFlow("test-flow")
        advanceUntilIdle()
        var currentState = store.selectState<NavigationState>().first()
        assertEquals("TEST_FLOW_1", currentState.currentEntry.params.getString("FLOW_ID"), "test-flow should have its own ID")
        
        // Complete first flow
        repeat(3) {
            store.nextGuidedFlowStep()
            advanceUntilIdle()
        }

        // Test flow 2
        store.startGuidedFlow("route-flow")
        advanceUntilIdle()
        currentState = store.selectState<NavigationState>().first()
        assertEquals("ROUTE_FLOW_1", currentState.currentEntry.params.getString("FLOW_ID"), "route-flow should have its own ID")
        
        // Navigate to profile step and verify profile-specific params
        store.nextGuidedFlowStep()
        advanceUntilIdle()
        currentState = store.selectState<NavigationState>().first()
        assertEquals("ROUTE", currentState.currentEntry.params.getString("PROFILE_TYPE"), "route-flow should have its own profile type")
        
        // Complete second flow
        repeat(2) {
            store.nextGuidedFlowStep()
            advanceUntilIdle()
        }

        // Test flow 3
        store.startGuidedFlow("builder-flow")
        advanceUntilIdle()
        currentState = store.selectState<NavigationState>().first()
        assertEquals("BUILDER_FLOW_1", currentState.currentEntry.params.getString("FLOW_ID"), "builder-flow should have its own ID")
        
        // Navigate to profile step and verify profile-specific params
        store.nextGuidedFlowStep()
        advanceUntilIdle()
        currentState = store.selectState<NavigationState>().first()
        assertEquals("BUILDER", currentState.currentEntry.params.getString("PROFILE_TYPE"), "builder-flow should have its own profile type")
    }

    @Test
    fun `should support configurable modification clearing behavior`() = runTest(timeout = 15.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        
        // Create a test navigation module with flows that have different clearing behaviors
        val testNavigationModule = createNavigationModule {
            rootGraph {
                startScreen(TestHomeScreen)
                screens(TestHomeScreen, TestWelcomeScreen, TestProfileScreen, TestPreferencesScreen)
            }
            
            // Flow 1: Uses CLEAR_SPECIFIC - only clears its own modifications
            guidedFlow("flow-clear-specific") {
                step<TestWelcomeScreen>()
                step<TestProfileScreen>()
                step<TestPreferencesScreen>()
                clearModificationsOnComplete(ClearModificationBehavior.CLEAR_SPECIFIC)
                onComplete { storeAccessor ->
                    storeAccessor.navigation {
                        navigateTo(TestHomeScreen.route)
                    }
                }
            }
            
            // Flow 2: Uses CLEAR_ALL - clears all modifications when it completes
            guidedFlow("flow-clear-all") {
                step<TestWelcomeScreen>()
                step<TestProfileScreen>()
                step<TestPreferencesScreen>()
                clearModificationsOnComplete(ClearModificationBehavior.CLEAR_ALL)
                onComplete { storeAccessor ->
                    storeAccessor.navigation {
                        navigateTo(TestHomeScreen.route)
                    }
                }
            }
            
            // Flow 3: Uses CLEAR_NONE - doesn't clear any modifications
            guidedFlow("flow-clear-none") {
                step<TestWelcomeScreen>()
                step<TestProfileScreen>()
                step<TestPreferencesScreen>()
                clearModificationsOnComplete(ClearModificationBehavior.CLEAR_NONE)
                onComplete { storeAccessor ->
                    storeAccessor.navigation {
                        navigateTo(TestHomeScreen.route)
                    }
                }
            }
        }
        
        val store = createStore {
            module(testNavigationModule)
            coroutineContext(testDispatcher)
        }

        // SCENARIO: Test different clearing behaviors
        
        // Apply runtime modifications to all flows (this should be allowed)
        store.guidedFlow("flow-clear-specific") {
            updateStepParams<TestWelcomeScreen> {
                putString("FLOW_TYPE", "CLEAR_SPECIFIC")
            }
        }
        
        store.guidedFlow("flow-clear-all") {
            updateStepParams<TestWelcomeScreen> {
                putString("FLOW_TYPE", "CLEAR_ALL")
            }
        }
        
        store.guidedFlow("flow-clear-none") {
            updateStepParams<TestWelcomeScreen> {
                putString("FLOW_TYPE", "CLEAR_NONE")
            }
        }
        
        advanceUntilIdle()

        // Verify all three flows have their modifications stored
        var state = store.selectState<NavigationState>().first()
        assertEquals(3, state.guidedFlowModifications.size, "Should have modifications for all 3 flows")
        assertTrue(state.guidedFlowModifications.containsKey("flow-clear-specific"), "Should have flow-clear-specific modifications")
        assertTrue(state.guidedFlowModifications.containsKey("flow-clear-all"), "Should have flow-clear-all modifications")
        assertTrue(state.guidedFlowModifications.containsKey("flow-clear-none"), "Should have flow-clear-none modifications")

        // TEST 1: Complete flow-clear-specific - should only clear its own modifications
        store.startGuidedFlow("flow-clear-specific")
        advanceUntilIdle()
        
        // Complete the flow
        repeat(3) {
            store.nextGuidedFlowStep()
            advanceUntilIdle()
        }

        state = store.selectState<NavigationState>().first()
        assertEquals(2, state.guidedFlowModifications.size, "Should have 2 modifications after CLEAR_SPECIFIC")
        assertFalse(state.guidedFlowModifications.containsKey("flow-clear-specific"), "flow-clear-specific modifications should be cleared")
        assertTrue(state.guidedFlowModifications.containsKey("flow-clear-all"), "flow-clear-all modifications should remain")
        assertTrue(state.guidedFlowModifications.containsKey("flow-clear-none"), "flow-clear-none modifications should remain")

        // TEST 2: Complete flow-clear-all - should clear ALL modifications
        store.startGuidedFlow("flow-clear-all")
        advanceUntilIdle()
        
        // Complete the flow
        repeat(3) {
            store.nextGuidedFlowStep()
            advanceUntilIdle()
        }

        state = store.selectState<NavigationState>().first()
        assertEquals(0, state.guidedFlowModifications.size, "Should have 0 modifications after CLEAR_ALL")
        assertFalse(state.guidedFlowModifications.containsKey("flow-clear-all"), "flow-clear-all modifications should be cleared")
        assertFalse(state.guidedFlowModifications.containsKey("flow-clear-none"), "flow-clear-none modifications should also be cleared by CLEAR_ALL")

        // TEST 3: Set up flow-clear-none again and verify CLEAR_NONE behavior
        store.guidedFlow("flow-clear-none") {
            updateStepParams<TestWelcomeScreen> {
                putString("FLOW_TYPE", "CLEAR_NONE_AGAIN")
            }
        }
        advanceUntilIdle()

        store.startGuidedFlow("flow-clear-none")
        advanceUntilIdle()
        
        // Complete the flow
        repeat(3) {
            store.nextGuidedFlowStep()
            advanceUntilIdle()
        }

        state = store.selectState<NavigationState>().first()
        assertEquals(1, state.guidedFlowModifications.size, "Should have 1 modification after CLEAR_NONE")
        assertTrue(state.guidedFlowModifications.containsKey("flow-clear-none"), "flow-clear-none modifications should remain with CLEAR_NONE")
    }
    
    @Test
    fun `should prevent starting concurrent guided flows`() = runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start first guided flow
        store.startGuidedFlow("test-flow")
        advanceUntilIdle()

        var state = store.selectState<NavigationState>().first()
        assertNotNull(state.activeGuidedFlowState, "First flow should be active")
        assertEquals("test-flow", state.activeGuidedFlowState?.flowRoute)
        assertEquals(TestWelcomeScreen, state.currentEntry.navigatable, "Should be on first step of first flow")

        // Try to start second guided flow while first is still active
        store.startGuidedFlow("test-flow", Params.of("userId" to "123"))
        advanceUntilIdle()

        state = store.selectState<NavigationState>().first()
        assertNotNull(state.activeGuidedFlowState, "Active flow should still be the first one")
        assertEquals("test-flow", state.activeGuidedFlowState?.flowRoute, "Active flow should still be the first one")
        assertEquals(TestWelcomeScreen, state.currentEntry.navigatable, "Should still be on first step of first flow")
        assertEquals(0, state.activeGuidedFlowState?.currentStepIndex, "Should still be on first step")

        // Advance the first flow to make sure it's still working normally
        store.nextGuidedFlowStep()
        advanceUntilIdle()

        state = store.selectState<NavigationState>().first()
        assertEquals(TestProfileScreen, state.currentEntry.navigatable, "First flow should advance to next step")
        assertEquals(1, state.activeGuidedFlowState?.currentStepIndex, "Should be on second step of first flow")

        // Complete the first flow
        store.nextGuidedFlowStep() // To TestPreferencesScreen
        advanceUntilIdle()
        store.nextGuidedFlowStep() // Complete flow
        advanceUntilIdle()

        state = store.selectState<NavigationState>().first()
        assertNull(state.activeGuidedFlowState, "Flow should be completed and cleared")

        // Now try to start another flow - this should succeed
        store.startGuidedFlow("test-flow", Params.of("userId" to "456"))
        advanceUntilIdle()

        state = store.selectState<NavigationState>().first()
        assertNotNull(state.activeGuidedFlowState, "New flow should be active")
        assertEquals("test-flow", state.activeGuidedFlowState?.flowRoute)
        assertEquals(TestWelcomeScreen, state.currentEntry.navigatable, "Should be on first step of new flow")
        assertEquals(0, state.activeGuidedFlowState?.currentStepIndex, "Should be on first step of new flow")
        
        // Verify the new flow received the parameters
        assertEquals("456", state.currentEntry.params.getString("userId"), "New flow should receive the parameters")
    }
}