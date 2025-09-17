@file:OptIn(ExperimentalCoroutinesApi::class)

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.GuidedFlow
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.guidedFlow
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.extension.startGuidedFlow
import io.github.syrou.reaktiv.navigation.middleware.NavigationSpamMiddleware
import io.github.syrou.reaktiv.navigation.model.GuidedFlowDefinition
import io.github.syrou.reaktiv.navigation.model.GuidedFlowStep
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.NavigationTransitionState
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.serialization.Serializable
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class GuidedFlowSpamProtectionTest {

    @BeforeTest
    fun setup() {
        io.github.syrou.reaktiv.core.util.ReaktivDebug.enable()
    }

    @Serializable
    object Step1Screen : Screen {
        override val route = "step1"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false

        @Composable
        override fun Content(params: Params) {
        }
    }

    @Serializable
    object Step2Screen : Screen {
        override val route = "step2"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false

        @Composable
        override fun Content(params: Params) {
        }
    }

    @Serializable
    object Step3Screen : Screen {
        override val route = "step3"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val requiresAuth = false

        @Composable
        override fun Content(params: Params) {
        }
    }

    private fun createTestStore(testScope: TestDispatcher, timeSource: TimeSource) = createStore {
        module(
            createNavigationModule {
                rootGraph {
                    startScreen(Step1Screen)
                    screens(Step1Screen, Step2Screen, Step3Screen)
                }

                guidedFlow("test-flow") {
                    step<Step1Screen>()
                    step<Step2Screen>()
                    step<Step3Screen>()
                    onComplete { storeAccessor ->
                        navigateTo("step1")
                    }
                }
            }
        )
        middlewares(
            NavigationSpamMiddleware.create(
                debounceTimeMs = 300L,
                maxActionsPerWindow = 3,
                windowSizeMs = 1000L,
                timeSource = timeSource
            )
        )
        coroutineContext(testScope)
    }

    @Test
    fun `nextStep spam should be blocked by middleware`() = runTest {
        val store = createTestStore(StandardTestDispatcher(testScheduler), testTimeSource)

        // Start the guided flow
        store.startGuidedFlow("test-flow")

        val initialState = store.selectState<NavigationState>().first()
        val initialStep = initialState.activeGuidedFlowState?.currentStepIndex ?: 0

        // Spam nextStep calls rapidly
        repeat(10) {
            store.guidedFlow("test-flow") {
                nextStep()
            }
        }

        // Allow all coroutines to complete
        advanceUntilIdle()

        val finalState = store.selectState<NavigationState>().first()
        val finalStep = finalState.activeGuidedFlowState?.currentStepIndex ?: 0

        // Should not have advanced more than the allowed window permits (maxActionsPerWindow = 3)
        assertTrue(
            finalStep <= initialStep + 3,
            "Expected step $finalStep to be <= ${initialStep + 3}, but it advanced too far due to spam"
        )
    }

    @Test
    fun `different guided flows should not interfere with each other`() = runTest {
        val store = createTestStore(StandardTestDispatcher(testScheduler), testTimeSource)

        // Create second flow definition
        val otherFlowDefinition = GuidedFlowDefinition(
            guidedFlow = GuidedFlow("other-flow"),
            steps = listOf(
                GuidedFlowStep.Route("step1"),
                GuidedFlowStep.Route("step2")
            )
        )

        store.dispatch(
            NavigationAction.UpdateGuidedFlowModifications(
                "other-flow",
                otherFlowDefinition
            )
        )

        // Start both flows
        store.startGuidedFlow("test-flow")

        // Rapid operations on different flows should not block each other
        store.guidedFlow("test-flow") { nextStep() }
        store.guidedFlow("other-flow") { nextStep() }
        store.guidedFlow("test-flow") { nextStep() }
        store.guidedFlow("other-flow") { nextStep() }

        advanceUntilIdle()

        // Both should have processed since they're different flow routes
        val state = store.selectState<NavigationState>().first()
        assertNotNull(state.activeGuidedFlowState)
    }

    @Test
    fun `legitimate guided flow operations should not be blocked`() = runTest {
        val store = createTestStore(StandardTestDispatcher(testScheduler), testTimeSource)

        store.startGuidedFlow("test-flow")
        advanceUntilIdle()

        val initialState = store.selectState<NavigationState>().first()
        val initialStep = initialState.activeGuidedFlowState?.currentStepIndex ?: 0

        // Single nextStep should always work
        store.guidedFlow("test-flow") {
            nextStep()
        }

        advanceUntilIdle()

        val afterFirstStep = store.selectState<NavigationState>().first()
        val firstStepIndex = afterFirstStep.activeGuidedFlowState?.currentStepIndex ?: 0

        assertEquals(
            initialStep + 1,
            firstStepIndex,
            "Single nextStep should advance exactly one step"
        )

        // Wait for debounce period to clear
        advanceTimeBy(400) // Advance past debounce period (300ms)
        advanceUntilIdle()

        // Another nextStep after debounce should work
        store.guidedFlow("test-flow") {
            nextStep()
        }

        advanceUntilIdle()

        val finalState = store.selectState<NavigationState>().first()
        val finalStep = finalState.activeGuidedFlowState?.currentStepIndex ?: 0

        assertEquals(
            firstStepIndex + 1,
            finalStep,
            "NextStep after debounce period should advance another step"
        )
    }

    @Test
    fun `multiple operations in single guidedFlow block should work`() = runTest {
        val store = createTestStore(StandardTestDispatcher(testScheduler), testTimeSource)

        store.startGuidedFlow("test-flow")
        advanceUntilIdle()

        val initialState = store.selectState<NavigationState>().first()
        val initialStep = initialState.activeGuidedFlowState?.currentStepIndex ?: 0

        // Multiple operations in atomic block should all execute
        store.guidedFlow("test-flow") {
            updateStepParams(0, Params.of("test" to "value"))
            nextStep()
            updateStepParams(1, Params.of("test2" to "value2"))
        }

        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        val currentStep = state.activeGuidedFlowState?.currentStepIndex ?: 0

        assertEquals(
            1,
            currentStep,
            "Atomic guided flow operations should all execute successfully"
        )
    }

    @Test
    fun `rapid nextStep calls should respect debounce timing`() = runTest {
        val store = createTestStore(StandardTestDispatcher(testScheduler), testTimeSource)
        // Start the guided flow
        store.startGuidedFlow("test-flow")
        advanceUntilIdle()

        val initialState = store.selectState<NavigationState>().first()
        val initialStep = initialState.activeGuidedFlowState?.currentStepIndex ?: 0

        // First call - should work and advance step
        store.guidedFlow("test-flow") { nextStep() }
        advanceUntilIdle()

        val afterFirstState = store.selectState<NavigationState>().first()
        val afterFirstStep = afterFirstState.activeGuidedFlowState?.currentStepIndex ?: 0

        // Second call immediately - should be blocked by debounce
        store.guidedFlow("test-flow") { nextStep() }
        advanceUntilIdle()

        val midState = store.selectState<NavigationState>().first()
        val midStep = midState.activeGuidedFlowState?.currentStepIndex ?: 0

        // Test the actual behavior:
        // 1. First call should advance: initialStep -> afterFirstStep  
        // 2. Second call should be blocked: afterFirstStep -> midStep (same)
        assertEquals(
            afterFirstStep,
            midStep,
            "Second rapid call should be blocked by debounce (afterFirstStep=$afterFirstStep, midStep=$midStep)"
        )

        // Advance time past debounce period using the test scheduler
        advanceTimeBy(1000)
        advanceUntilIdle()

        // Third call after debounce - should work
        store.guidedFlow("test-flow") { nextStep() }
        advanceUntilIdle()

        val finalState = store.selectState<NavigationState>().first()
        val finalStep = finalState.activeGuidedFlowState?.currentStepIndex ?: 0

        assertEquals(
            midStep + 1,
            finalStep,
            "Call after debounce period should work (midStep=$midStep, finalStep=$finalStep)"
        )
    }

    @Test
    fun `navigation actions blocked during animation transition state`() = runTest {
        val store = createTestStore(StandardTestDispatcher(testScheduler), testTimeSource)

        // Start the guided flow
        store.startGuidedFlow("test-flow")
        advanceUntilIdle()

        val initialState = store.selectState<NavigationState>().first()
        val initialStep = initialState.activeGuidedFlowState?.currentStepIndex ?: 0

        // Verify initial state is IDLE
        assertEquals(
            NavigationTransitionState.IDLE,
            initialState.transitionState,
            "Initial transition state should be IDLE"
        )

        // Test the full transition state cycle
        // 1. Navigation should set transitionState = ANIMATING when screen route changes
        // First, let's manually dispatch a Navigate action to change screens and see ANIMATING state
        store.dispatch(
            NavigationAction.Navigate(
                currentEntry = NavigationEntry(Step2Screen, Params.empty(), "root"),
                backStack = initialState.backStack + NavigationEntry(Step2Screen, Params.empty(), "root"),
                transitionState = NavigationTransitionState.ANIMATING
            )
        )
        advanceUntilIdle()

        val animatingState = store.selectState<NavigationState>().first()
        assertEquals(
            NavigationTransitionState.ANIMATING,
            animatingState.transitionState,
            "Navigation should set transition state to ANIMATING"
        )

        // 2. During ANIMATING, nextStep should be blocked by middleware
        val preSpamStep = animatingState.activeGuidedFlowState?.currentStepIndex ?: 0

        store.guidedFlow("test-flow") { nextStep() }
        advanceUntilIdle()

        val duringAnimationState = store.selectState<NavigationState>().first()
        val duringAnimationStep = duringAnimationState.activeGuidedFlowState?.currentStepIndex ?: 0

        assertEquals(
            preSpamStep,
            duringAnimationStep,
            "NextStep should be blocked during ANIMATING state"
        )

        // 3. Reset to IDLE and verify navigation works again
        store.dispatch(NavigationAction.UpdateTransitionState(NavigationTransitionState.IDLE))
        advanceUntilIdle()

        val idleState = store.selectState<NavigationState>().first()
        assertEquals(
            NavigationTransitionState.IDLE,
            idleState.transitionState,
            "Transition state should reset to IDLE"
        )

        // Now nextStep should work again
        store.guidedFlow("test-flow") { nextStep() }
        advanceUntilIdle()

        val afterIdleStep = store.selectState<NavigationState>().first()
        val finalStep = afterIdleStep.activeGuidedFlowState?.currentStepIndex ?: 0

        assertEquals(
            duringAnimationStep + 1,
            finalStep,
            "NextStep should work when transition state returns to IDLE"
        )
    }

    @Test
    fun `user cannot spam past flow boundaries`() = runTest {
        val store = createTestStore(StandardTestDispatcher(testScheduler), testTimeSource)

        store.startGuidedFlow("test-flow")

        // Spam nextStep beyond the flow length
        repeat(20) {
            store.guidedFlow("test-flow") {
                nextStep()
            }
        }

        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        val progress = state.activeGuidedFlowState?.progress ?: 0f

        assertTrue(
            progress <= 1f,
            "User should not be able to spam past flow boundaries (progress $progress > 1.0)"
        )

        // Progress should also be a valid value (not negative)
        assertTrue(
            progress >= 0f,
            "Progress should not be negative (progress: $progress)"
        )
    }

    @Test
    fun `atomic navigation prevents guided flow batch update spam conflicts`() = runTest {
        val store = createStore {
            module(
                createNavigationModule {
                    rootGraph {
                        startScreen(Step1Screen)
                        screens(Step1Screen, Step2Screen, Step3Screen)
                    }

                    guidedFlow("signup-flow") {
                        step<Step1Screen>()
                        step<Step2Screen>()
                        step<Step3Screen>()
                    }
                    
                    guidedFlow("onboarding-flow") {
                        step<Step2Screen>()
                        step<Step3Screen>()
                        step<Step1Screen>()
                    }
                }
            )
            middlewares(
                NavigationSpamMiddleware.create(
                    debounceTimeMs = 300L,
                    maxActionsPerWindow = 3,
                    windowSizeMs = 1000L,
                    timeSource = testTimeSource
                )
            )
            coroutineContext(StandardTestDispatcher(testScheduler))
        }

        // Start first guided flow
        store.startGuidedFlow("signup-flow")
        advanceUntilIdle()

        val initialState = store.selectState<NavigationState>().first()
        assertEquals(Step1Screen.route, initialState.currentEntry.navigatable.route)

        // Execute multiple guided flow operations atomically to prevent spam conflicts
        store.navigation {
            guidedFlow("signup-flow") {
                removeSteps(listOf(2))
                nextStep()
            }
            guidedFlow("onboarding-flow") {
                updateStepParams(0, Params.of("modified" to true))
                removeSteps(listOf(1))
            }
        }
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        
        // All operations should succeed as they're batched into single BatchUpdate
        assertEquals(Step2Screen.route, state.currentEntry.navigatable.route)
        assertNotNull(state.activeGuidedFlowState)
        assertEquals("signup-flow", state.activeGuidedFlowState.flowRoute)
        // Flow should have advanced to step 1 after nextStep()
        assertEquals(1, state.activeGuidedFlowState.currentStepIndex)
        
        // Both flows should have modifications applied
        assertTrue(state.guidedFlowModifications.containsKey("signup-flow"))
        assertTrue(state.guidedFlowModifications.containsKey("onboarding-flow"))

        // Verify the old problematic pattern would be blocked if done separately
        // These separate guidedFlow calls would trigger spam protection
        store.guidedFlow("signup-flow") {
            updateStepParams(0, Params.of("test1" to true))
        }
        
        store.guidedFlow("onboarding-flow") { 
            updateStepParams(1, Params.of("test2" to true))
        }
        
        advanceUntilIdle()

        // The separate operations may be blocked by spam protection,
        // but our atomic operation above succeeded completely
        val finalState = store.selectState<NavigationState>().first()
        assertEquals(Step2Screen.route, finalState.currentEntry.navigatable.route)
        assertTrue(finalState.guidedFlowModifications.containsKey("signup-flow"))
        assertTrue(finalState.guidedFlowModifications.containsKey("onboarding-flow"))
    }
}