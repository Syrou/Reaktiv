import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.GuidedFlow
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.definition.ScreenGroup
import io.github.syrou.reaktiv.navigation.model.FlowModification
import io.github.syrou.reaktiv.navigation.model.GuidedFlowDefinition
import io.github.syrou.reaktiv.navigation.model.GuidedFlowStep
import io.github.syrou.reaktiv.navigation.model.guidedFlowStep
import io.github.syrou.reaktiv.navigation.model.getParams
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

// Test screens for flow modification tests
object TestScreen1 : Screen {
    override val route = "test1"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false
    override val titleResource: TitleResource = { "Test Screen 1" }
    @Composable
    override fun Content(params: Map<String, Any>) {}
}

object TestScreen2 : Screen {
    override val route = "test2"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false
    override val titleResource: TitleResource = { "Test Screen 2" }
    @Composable
    override fun Content(params: Map<String, Any>) {}
}

object TestScreen3 : Screen {
    override val route = "test3"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false
    override val titleResource: TitleResource = { "Test Screen 3" }
    @Composable
    override fun Content(params: Map<String, Any>) {}
}

object TestScreen4 : Screen {
    override val route = "test4"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false
    override val titleResource: TitleResource = { "Test Screen 4" }
    @Composable
    override fun Content(params: Map<String, Any>) {}
}

object TestScreenGroup : ScreenGroup(TestScreen4)

@OptIn(ExperimentalCoroutinesApi::class)
class FlowModificationTest {

    private fun createTestNavigationModule() = createNavigationModule {
        rootGraph {
            startScreen(TestScreen1)
            screens(TestScreen1, TestScreen2, TestScreen3)
            screenGroup(TestScreenGroup)
        }
        
        guidedFlow(Route.TestFlow) {
            step<TestScreen1>()
            step<TestScreen2>()
            step<TestScreen3>()
        }
    }

    object Route {
        const val TestFlow = "test-flow"
    }

    @Test
    fun `should resolve screens from nested graphs in guided flows`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Modify flow to include screen from nested group
        store.dispatch(
            NavigationAction.ModifyGuidedFlow(
                flowRoute = Route.TestFlow,
                modification = FlowModification.AddSteps(
                    steps = listOf(guidedFlowStep<TestScreen4>()),
                    // Append at end
                    insertIndex = -1
                )
            )
        )
        advanceUntilIdle()

        // Start guided flow
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()

        // Navigate to the added step from nested group
        repeat(3) {
            store.dispatch(NavigationAction.NextStep())
            advanceUntilIdle()
        }

        val state = store.selectState<NavigationState>().first()
        
        // Should successfully navigate to screen from nested group
        assertEquals(TestScreen4, state.currentEntry.navigatable)
        assertEquals(3, state.activeGuidedFlowState?.currentStepIndex)
    }

    @Test
    fun `should handle RemoveSteps modification correctly`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()

        // Navigate to step 1 (TestScreen2)
        store.dispatch(NavigationAction.NextStep())
        advanceUntilIdle()

        val stateBeforeModification = store.selectState<NavigationState>().first()
        assertEquals(1, stateBeforeModification.activeGuidedFlowState?.currentStepIndex)
        assertEquals(TestScreen2, stateBeforeModification.currentEntry.navigatable)

        // Remove step 2 (TestScreen3) - should adjust current index
        store.dispatch(
            NavigationAction.ModifyGuidedFlow(
                flowRoute = Route.TestFlow,
                modification = FlowModification.RemoveSteps(stepIndices = listOf(2))
            )
        )
        advanceUntilIdle()

        val stateAfterModification = store.selectState<NavigationState>().first()
        
        // Current step index should remain 1, but now there are only 2 steps total
        assertEquals(1, stateAfterModification.activeGuidedFlowState?.currentStepIndex)
        assertEquals(2, stateAfterModification.guidedFlowDefinitions[Route.TestFlow]?.steps?.size)
        
        // Should now be on final step
        assertTrue(stateAfterModification.activeGuidedFlowState?.isOnFinalStep == true)
    }

    @Test
    fun `should adjust current step index when removing steps before current position`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow and navigate to step 2 (TestScreen3)
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()
        
        repeat(2) {
            store.dispatch(NavigationAction.NextStep())
            advanceUntilIdle()
        }

        val stateBeforeModification = store.selectState<NavigationState>().first()
        assertEquals(2, stateBeforeModification.activeGuidedFlowState?.currentStepIndex)
        assertEquals(TestScreen3, stateBeforeModification.currentEntry.navigatable)

        // Remove step 0 (TestScreen1) - should adjust current index down by 1
        store.dispatch(
            NavigationAction.ModifyGuidedFlow(
                flowRoute = Route.TestFlow,
                modification = FlowModification.RemoveSteps(stepIndices = listOf(0))
            )
        )
        advanceUntilIdle()

        val stateAfterModification = store.selectState<NavigationState>().first()
        
        // Current step index should be adjusted from 2 to 1
        assertEquals(1, stateAfterModification.activeGuidedFlowState?.currentStepIndex)
        assertEquals(2, stateAfterModification.guidedFlowDefinitions[Route.TestFlow]?.steps?.size)
        
        // Should still be on TestScreen3, but now it's at index 1
        assertEquals(TestScreen3, stateAfterModification.currentEntry.navigatable)
    }

    @Test
    fun `should handle AddSteps modification correctly`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow and navigate to step 1
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()
        store.dispatch(NavigationAction.NextStep())
        advanceUntilIdle()

        val stateBeforeModification = store.selectState<NavigationState>().first()
        assertEquals(1, stateBeforeModification.activeGuidedFlowState?.currentStepIndex)

        // Add a step at index 0 - should adjust current index up by 1
        store.dispatch(
            NavigationAction.ModifyGuidedFlow(
                flowRoute = Route.TestFlow,
                modification = FlowModification.AddSteps(
                    steps = listOf(guidedFlowStep<TestScreen4>()),
                    insertIndex = 0
                )
            )
        )
        advanceUntilIdle()

        val stateAfterModification = store.selectState<NavigationState>().first()
        
        // Current step index should be adjusted from 1 to 2
        assertEquals(2, stateAfterModification.activeGuidedFlowState?.currentStepIndex)
        assertEquals(4, stateAfterModification.guidedFlowDefinitions[Route.TestFlow]?.steps?.size)
    }

    @Test
    fun `should handle UpdateStepParams modification correctly`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()

        // Update parameters for step 1
        val newParams: StringAnyMap = mapOf("userId" to "123", "test" to true)
        store.dispatch(
            NavigationAction.ModifyGuidedFlow(
                flowRoute = Route.TestFlow,
                modification = FlowModification.UpdateStepParams(
                    stepIndex = 1,
                    newParams = newParams
                )
            )
        )
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        val updatedDefinition = state.guidedFlowDefinitions[Route.TestFlow]
        assertNotNull(updatedDefinition)
        
        // Step 1 should have updated parameters
        val step1 = updatedDefinition.steps[1]
        assertEquals(newParams, step1.getParams())
    }

    @Test
    fun `should handle ReplaceStep modification correctly`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()

        // Replace step 1 with TestScreen4
        store.dispatch(
            NavigationAction.ModifyGuidedFlow(
                flowRoute = Route.TestFlow,
                modification = FlowModification.ReplaceStep(
                    stepIndex = 1,
                    newStep = guidedFlowStep<TestScreen4>(mapOf("replaced" to true))
                )
            )
        )
        advanceUntilIdle()

        val state = store.selectState<NavigationState>().first()
        val updatedDefinition = state.guidedFlowDefinitions[Route.TestFlow]
        assertNotNull(updatedDefinition)
        
        // Step 1 should be replaced with TestScreen4
        val step1 = updatedDefinition.steps[1]
        when (step1) {
            is GuidedFlowStep.TypedScreen -> {
                assertEquals(TestScreen4::class.qualifiedName, step1.screenClass)
                assertEquals(mapOf("replaced" to true), step1.params)
            }
            else -> throw AssertionError("Expected TypedScreen step")
        }
    }

    @Test
    fun `should handle removal that puts current step out of bounds`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow and navigate to final step (index 2)
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()
        repeat(2) {
            store.dispatch(NavigationAction.NextStep())
            advanceUntilIdle()
        }

        val stateBeforeModification = store.selectState<NavigationState>().first()
        assertEquals(2, stateBeforeModification.activeGuidedFlowState?.currentStepIndex)

        // Remove the last two steps, leaving only step 0
        store.dispatch(
            NavigationAction.ModifyGuidedFlow(
                flowRoute = Route.TestFlow,
                modification = FlowModification.RemoveSteps(stepIndices = listOf(1, 2))
            )
        )
        advanceUntilIdle()

        val stateAfterModification = store.selectState<NavigationState>().first()
        
        // Current step index should be clamped to 0 (the only remaining step)
        assertEquals(0, stateAfterModification.activeGuidedFlowState?.currentStepIndex)
        assertEquals(1, stateAfterModification.guidedFlowDefinitions[Route.TestFlow]?.steps?.size)
        
        // Should be on final step since there's only one step left
        assertTrue(stateAfterModification.activeGuidedFlowState?.isOnFinalStep == true)
    }

    @Test
    fun `should complete flow when NextStep is called after removal makes current step final`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow and navigate to step 1
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()
        store.dispatch(NavigationAction.NextStep())
        advanceUntilIdle()

        val stateBeforeModification = store.selectState<NavigationState>().first()
        assertEquals(1, stateBeforeModification.activeGuidedFlowState?.currentStepIndex)
        assertEquals(false, stateBeforeModification.activeGuidedFlowState?.isOnFinalStep)

        // Remove the last step, making current step the final one
        store.dispatch(
            NavigationAction.ModifyGuidedFlow(
                flowRoute = Route.TestFlow,
                modification = FlowModification.RemoveSteps(stepIndices = listOf(2))
            )
        )
        advanceUntilIdle()

        val stateAfterModification = store.selectState<NavigationState>().first()
        assertEquals(1, stateAfterModification.activeGuidedFlowState?.currentStepIndex)
        assertTrue(stateAfterModification.activeGuidedFlowState?.isOnFinalStep == true)

        // Calling NextStep should now complete the flow
        store.dispatch(NavigationAction.NextStep())
        advanceUntilIdle()

        val finalState = store.selectState<NavigationState>().first()
        // Flow should be completed and cleared
        assertNull(finalState.activeGuidedFlowState)
    }
}