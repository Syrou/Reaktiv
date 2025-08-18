import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.GuidedFlow
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.model.GuidedFlowContext
import io.github.syrou.reaktiv.navigation.model.GuidedFlowDefinition
import io.github.syrou.reaktiv.navigation.model.GuidedFlowStep
import io.github.syrou.reaktiv.navigation.model.guidedFlowStep
import io.github.syrou.reaktiv.navigation.model.toGuidedFlowStep
import io.github.syrou.reaktiv.navigation.dsl.guidedFlow
import io.github.syrou.reaktiv.navigation.dsl.step
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
    }

    private fun createTestGuidedFlowDefinition() = GuidedFlowDefinition(
        guidedFlow = GuidedFlow("test-flow"),
        steps = listOf(
            guidedFlowStep<TestWelcomeScreen>(),
            guidedFlowStep<TestProfileScreen>(),
            guidedFlowStep<TestPreferencesScreen>()
        ),
        onComplete = {
            navigateTo(TestHomeScreen.route)
            clearBackStack()
        }
    )

    private fun createRouteBasedGuidedFlowDefinition() = GuidedFlowDefinition(
        guidedFlow = GuidedFlow("route-flow"),
        steps = listOf(
            "test-welcome".toGuidedFlowStep(),
            "test-profile?source=guided".toGuidedFlowStep(mapOf("profileType" to "basic")),
            "test-preferences".toGuidedFlowStep()
        ),
        onComplete = {
            navigateTo("test-home")
            clearBackStack()
        }
    )

    @Test
    fun `should create guided flow definition`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        val definition = createTestGuidedFlowDefinition()
        store.dispatch(NavigationAction.CreateGuidedFlow(definition))
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        val storedDefinition = state.guidedFlowDefinitions["test-flow"]

        assertNotNull(storedDefinition)
        assertEquals("test-flow", storedDefinition.guidedFlow.route)
        assertEquals(3, storedDefinition.steps.size)
        assertTrue(storedDefinition.steps[0] is GuidedFlowStep.TypedScreen)
        assertTrue(storedDefinition.steps[1] is GuidedFlowStep.TypedScreen)
        assertTrue(storedDefinition.steps[2] is GuidedFlowStep.TypedScreen)
    }

    @Test
    fun `should start guided flow and navigate to first step`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Create and start guided flow
        val definition = createTestGuidedFlowDefinition()
        store.dispatch(NavigationAction.CreateGuidedFlow(definition))
        advanceUntilIdle()
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

        // Start guided flow
        val definition = createTestGuidedFlowDefinition()
        store.dispatch(NavigationAction.CreateGuidedFlow(definition))
        advanceUntilIdle()
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

        // Start guided flow and move to step 2
        val definition = createTestGuidedFlowDefinition()
        store.dispatch(NavigationAction.CreateGuidedFlow(definition))
        advanceUntilIdle()
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

        // Start guided flow and navigate to final step
        val definition = createTestGuidedFlowDefinition()
        store.dispatch(NavigationAction.CreateGuidedFlow(definition))
        advanceUntilIdle()
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

        // Start guided flow with parameters
        val definition = createTestGuidedFlowDefinition()
        store.dispatch(NavigationAction.CreateGuidedFlow(definition))
        advanceUntilIdle()
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

        // Start guided flow
        val definition = createTestGuidedFlowDefinition()
        store.dispatch(NavigationAction.CreateGuidedFlow(definition))
        advanceUntilIdle()
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

        // Start guided flow (on first step)
        val definition = createTestGuidedFlowDefinition()
        store.dispatch(NavigationAction.CreateGuidedFlow(definition))
        advanceUntilIdle()
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

        val definition = createTestGuidedFlowDefinition()
        store.dispatch(NavigationAction.CreateGuidedFlow(definition))
        advanceUntilIdle()
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
        val definition = createTestGuidedFlowDefinition()
        store.dispatch(NavigationAction.CreateGuidedFlow(definition))
        advanceUntilIdle()
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

        // Create route-based guided flow
        val definition = createRouteBasedGuidedFlowDefinition()
        store.dispatch(NavigationAction.CreateGuidedFlow(definition))
        advanceUntilIdle()
        
        // Start the flow
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

        // Create guided flow using the builder DSL
        val definition = guidedFlow("builder-flow") {
            step<TestWelcomeScreen>()
            step("test-profile?source=guided").param("profileType", "basic")
            step<TestPreferencesScreen>().param("step", "3")
            onComplete { 
                navigateTo("test-home")
                clearBackStack()
            }
        }
        
        store.dispatch(NavigationAction.CreateGuidedFlow(definition))
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        val storedDefinition = state.guidedFlowDefinitions["builder-flow"]

        assertNotNull(storedDefinition)
        assertEquals("builder-flow", storedDefinition.guidedFlow.route)
        assertEquals(3, storedDefinition.steps.size)
        
        // Verify step types and parameters
        val steps = storedDefinition.steps
        assertTrue(steps[0] is GuidedFlowStep.TypedScreen)
        assertTrue(steps[1] is GuidedFlowStep.Route)
        assertTrue(steps[2] is GuidedFlowStep.TypedScreen)
        
        // Verify step parameters
        assertEquals("basic", (steps[1] as GuidedFlowStep.Route).params["profileType"])
        assertEquals("3", (steps[2] as GuidedFlowStep.TypedScreen).params["step"])
    }

    @Test
    fun `should handle mixed typed and route steps`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Create mixed flow with both typed screens and routes
        val definition = GuidedFlowDefinition(
            guidedFlow = GuidedFlow("mixed-flow"),
            steps = listOf(
                guidedFlowStep<TestWelcomeScreen>(mapOf("step" to "1")),
                "test-profile?tab=settings".toGuidedFlowStep(),
                guidedFlowStep<TestPreferencesScreen>(mapOf("step" to "3"))
            ),
            onComplete = {
                navigateTo("test-home")
            }
        )
        
        store.dispatch(NavigationAction.CreateGuidedFlow(definition))
        advanceUntilIdle()
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
}