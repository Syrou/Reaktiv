import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.GuidedFlow
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.model.GuidedFlowContext
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
        override fun Content(params: Map<String, Any>) {}
    }

    object TestProfileScreen : Screen {
        override val route = "test-profile"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false
        override val titleResource: TitleResource = { "Profile" }
        @Composable
        override fun Content(params: Map<String, Any>) {}
    }

    object TestPreferencesScreen : Screen {
        override val route = "test-preferences"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false
        override val titleResource: TitleResource = { "Preferences" }
        @Composable
        override fun Content(params: Map<String, Any>) {}
    }

    object TestHomeScreen : Screen {
        override val route = "test-home"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false
        override val titleResource: TitleResource = { "Home" }
        @Composable
        override fun Content(params: Map<String, Any>) {}
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
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow("test-flow")))
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
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow("test-flow")))
        advanceUntilIdle()

        // Move to next step
        store.dispatch(NavigationAction.NextStep())
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
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow("test-flow")))
        advanceUntilIdle()
        store.dispatch(NavigationAction.NextStep())
        advanceUntilIdle()

        // Move to previous step
        store.dispatch(NavigationAction.PreviousStep)
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
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow("test-flow")))
        advanceUntilIdle()
        store.dispatch(NavigationAction.NextStep()) // Step 1
        advanceUntilIdle()
        store.dispatch(NavigationAction.NextStep()) // Step 2 (final)
        advanceUntilIdle()

        val stateBeforeCompletion = store.selectState<NavigationState>().first()
        assertEquals(TestPreferencesScreen, stateBeforeCompletion.currentEntry.screen)
        assertTrue(stateBeforeCompletion.activeGuidedFlowState?.isOnFinalStep == true)

        // Complete the flow
        store.dispatch(NavigationAction.NextStep())
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
        store.dispatch(NavigationAction.StartGuidedFlow(
            GuidedFlow("test-flow"), 
            mapOf("userId" to "123", "source" to "onboarding")
        ))
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
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow("test-flow")))
        advanceUntilIdle()

        // Move to next step with parameters
        store.dispatch(NavigationAction.NextStep(mapOf("stepData" to "profile_info")))
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()

        // Should navigate to next step with parameters
        assertEquals(TestProfileScreen, state.currentEntry.screen)
        assertTrue(state.currentEntry.params.containsKey("stepData"))
        assertEquals("profile_info", state.currentEntry.params["stepData"])
    }

    @Test
    fun `should not allow previous step from first step`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow (on first step) - definition already configured in module
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow("test-flow")))
        advanceUntilIdle()

        // Try to go to previous step (should be ignored)
        store.dispatch(NavigationAction.PreviousStep)
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()

        // Should remain on first step
        assertEquals(TestWelcomeScreen, state.currentEntry.screen)
        assertEquals(0, state.activeGuidedFlowState?.currentStepIndex)
    }

    @Test
    fun `should calculate progress correctly`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow - definition already configured in module
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow("test-flow")))
        advanceUntilIdle()

        // Step 1 of 3 = 1/3 ≈ 0.33
        var state = store.selectState<NavigationState>().first()
        assertEquals(0.33f, state.activeGuidedFlowState?.progress ?: 0f, 0.01f)

        // Step 2 of 3 = 2/3 ≈ 0.67
        store.dispatch(NavigationAction.NextStep())
        advanceUntilIdle()
        state = store.selectState<NavigationState>().first()
        assertEquals(0.67f, state.activeGuidedFlowState?.progress ?: 0f, 0.01f)

        // Step 3 of 3 = 3/3 = 1.0
        store.dispatch(NavigationAction.NextStep())
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
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow("test-flow")))
        advanceUntilIdle()
        
        var state = store.selectState<NavigationState>().first()
        assertNotNull(state.activeGuidedFlowState?.startedAt)
        assertTrue(state.activeGuidedFlowState?.startedAt!! >= startTime)
        assertNull(state.activeGuidedFlowState?.completedAt)
        assertNull(state.activeGuidedFlowState?.duration)
        assertEquals(false, state.activeGuidedFlowState?.isCompleted)

        // Complete the flow
        store.dispatch(NavigationAction.NextStep()) // Step 1
        advanceUntilIdle()
        store.dispatch(NavigationAction.NextStep()) // Step 2
        advanceUntilIdle()
        store.dispatch(NavigationAction.NextStep()) // Complete
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
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow("route-flow")))
        advanceUntilIdle()

        var state = store.selectState<NavigationState>().first()
        
        // Should navigate to first step (test-welcome)
        assertEquals(TestWelcomeScreen, state.currentEntry.screen)
        
        // Move to next step with query parameters and additional params
        store.dispatch(NavigationAction.NextStep())
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
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow("builder-flow")))
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
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow("mixed-flow")))
        advanceUntilIdle()

        var state = store.selectState<NavigationState>().first()
        
        // First step: typed screen with params
        assertEquals(TestWelcomeScreen, state.currentEntry.screen)
        assertEquals("1", state.currentEntry.params["step"])
        
        // Move to second step: route with query params
        store.dispatch(NavigationAction.NextStep())
        advanceUntilIdle()
        
        state = store.selectState<NavigationState>().first()
        assertEquals(TestProfileScreen, state.currentEntry.screen)
        assertEquals("settings", state.currentEntry.params["tab"])
        
        // Move to third step: typed screen with params
        store.dispatch(NavigationAction.NextStep())
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
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow("conditional-flow")))
        advanceUntilIdle()
        
        // Navigate through flow steps
        store.dispatch(NavigationAction.NextStep()) // To profile
        advanceUntilIdle()
        store.dispatch(NavigationAction.NextStep()) // Complete flow
        advanceUntilIdle()
        
        var state = store.selectState<NavigationState>().first()
        assertEquals(TestHomeScreen, state.currentEntry.screen, "Regular user should navigate to home")
        assertNull(state.activeGuidedFlowState, "Flow should be completed")

        // Test Case 2: User with completed tutorial -> should go to welcome (advanced)
        store.dispatch(UserConditionAction.Reset) // Reset state
        advanceUntilIdle()
        store.dispatch(UserConditionAction.CompleteTutorial)
        advanceUntilIdle()
        
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow("conditional-flow")))
        advanceUntilIdle()
        store.dispatch(NavigationAction.NextStep()) // To profile
        advanceUntilIdle()
        store.dispatch(NavigationAction.NextStep()) // Complete flow
        advanceUntilIdle()
        
        state = store.selectState<NavigationState>().first()
        assertEquals(TestWelcomeScreen, state.currentEntry.screen, "Tutorial completed user should navigate to welcome")

        // Test Case 3: VIP user -> should go to profile
        store.dispatch(UserConditionAction.Reset) // Reset state
        advanceUntilIdle()
        store.dispatch(UserConditionAction.MakeVip)
        advanceUntilIdle()
        
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow("conditional-flow")))
        advanceUntilIdle()
        store.dispatch(NavigationAction.NextStep()) // To profile
        advanceUntilIdle()
        store.dispatch(NavigationAction.NextStep()) // Complete flow
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
        
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow("conditional-flow")))
        advanceUntilIdle()
        store.dispatch(NavigationAction.NextStep()) // To profile
        advanceUntilIdle()
        store.dispatch(NavigationAction.NextStep()) // Complete flow
        advanceUntilIdle()
        
        state = store.selectState<NavigationState>().first()
        assertEquals(TestPreferencesScreen, state.currentEntry.screen, "Premium VIP user should navigate to preferences")
        
        // Verify the user state was correctly accessed
        val finalUserState = store.selectState<UserConditionState>().first()
        assertTrue(finalUserState.isVip, "User should be VIP")
        assertTrue(finalUserState.hasCompletedTutorial, "User should have completed tutorial")
        assertTrue(finalUserState.points >= 100, "User should have high points")
    }
}