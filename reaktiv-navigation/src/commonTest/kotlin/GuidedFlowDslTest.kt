import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.GuidedFlow
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.guidedFlow
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

// Test screens for DSL tests
object DslTestScreen1 : Screen {
    override val route = "dsl-test1"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false
    override val titleResource: TitleResource = { "DSL Test Screen 1" }
    @Composable
    override fun Content(params: Map<String, Any>) {}
}

object DslTestScreen2 : Screen {
    override val route = "dsl-test2"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false
    override val titleResource: TitleResource = { "DSL Test Screen 2" }
    @Composable
    override fun Content(params: Map<String, Any>) {}
}

object DslTestScreen3 : Screen {
    override val route = "dsl-test3"
    override val enterTransition = NavTransition.None
    override val exitTransition = NavTransition.None
    override val requiresAuth = false
    override val titleResource: TitleResource = { "DSL Test Screen 3" }
    @Composable
    override fun Content(params: Map<String, Any>) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class GuidedFlowDslTest {
    // Temporarily disabled while fixing other test issues

    private fun createTestNavigationModule() = createNavigationModule {
        rootGraph {
            startScreen(DslTestScreen1)
            screens(DslTestScreen1, DslTestScreen2, DslTestScreen3)
        }
        
        guidedFlow(Route.TestFlow) {
            step<DslTestScreen1>()
            step<DslTestScreen2>()
            step<DslTestScreen3>()
        }
    }

    object Route {
        const val TestFlow = "dsl-test-flow"
    }

    @Test
    fun `should execute multiple operations atomically with DSL`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()

        // Navigate to step 1
        store.dispatch(NavigationAction.NextStep())
        advanceUntilIdle()

        val stateBeforeOperations = store.selectState<NavigationState>().first()
        assertEquals(1, stateBeforeOperations.activeGuidedFlowState?.currentStepIndex)
        assertEquals(DslTestScreen2, stateBeforeOperations.currentEntry.navigatable)

        // Use DSL to perform multiple operations atomically
        store.guidedFlow(Route.TestFlow) {
            // Remove the last step
            removeSteps(listOf(2))
            
            // Update parameters for current step
            updateStepParams(1, mapOf("modified" to true, "timestamp" to 12345))
            
            // Navigate to next step (which should now complete the flow since we removed the last step)
            nextStep()
        }
        advanceUntilIdle()

        val stateAfterOperations = store.selectState<NavigationState>().first()
        
        // Flow should be completed and cleared since we removed the last step and called nextStep
        assertNull(stateAfterOperations.activeGuidedFlowState)
        
        // Flow definition should be modified
        val modifiedDefinition = stateAfterOperations.guidedFlowDefinitions[Route.TestFlow]
        assertNotNull(modifiedDefinition)
        // One step removed
        assertEquals(2, modifiedDefinition.steps.size)
        
        // Step 1 should have updated parameters
        val step1 = modifiedDefinition.steps[1]
        val params = step1.getParams()
        assertEquals(true, params["modified"])
        assertEquals(12345, params["timestamp"])
    }

    @Test
    fun `should handle step modification and navigation in sequence`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()

        // Use DSL to add steps and navigate
        store.guidedFlow(Route.TestFlow) {
            // Add a new step at the beginning
            addSteps(listOf(guidedFlowStep<DslTestScreen3>(mapOf("inserted" to true))), 0)
            
            // Navigate to next step (should go to the newly inserted step)
            nextStep()
        }
        advanceUntilIdle()

        val stateAfterOperations = store.selectState<NavigationState>().first()
        
        // Should be on step 2 (navigated from adjusted position 1 to next step 2)
        assertEquals(2, stateAfterOperations.activeGuidedFlowState?.currentStepIndex)
        assertEquals(DslTestScreen2, stateAfterOperations.currentEntry.navigatable)
        
        // Flow should now have 4 steps total
        val modifiedDefinition = stateAfterOperations.guidedFlowDefinitions[Route.TestFlow]
        assertNotNull(modifiedDefinition)
        assertEquals(4, modifiedDefinition.steps.size)
    }

    @Test
    fun `should support replace step and navigate operations`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
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

        // Use DSL to replace current step and navigate
        store.guidedFlow(Route.TestFlow) {
            // Replace step 2 with a different configuration
            replaceStep(2, guidedFlowStep<DslTestScreen1>(mapOf("replaced" to true, "version" to 2)))
            
            // Navigate to that step
            nextStep()
        }
        advanceUntilIdle()

        val stateAfterOperations = store.selectState<NavigationState>().first()
        
        // Should be on the replaced step (DslTestScreen1 instead of DslTestScreen3)
        assertEquals(2, stateAfterOperations.activeGuidedFlowState?.currentStepIndex)
        assertEquals(DslTestScreen1, stateAfterOperations.currentEntry.navigatable)
        
        // Verify the step was replaced with correct parameters
        val modifiedDefinition = stateAfterOperations.guidedFlowDefinitions[Route.TestFlow]
        assertNotNull(modifiedDefinition)
        val replacedStep = modifiedDefinition.steps[2]
        val params = replacedStep.getParams()
        assertEquals(true, params["replaced"])
        assertEquals(2, params["version"])
    }

    @Test
    fun `should support previous step navigation in DSL`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow and navigate to step 2
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()
        repeat(2) {
            store.dispatch(NavigationAction.NextStep())
            advanceUntilIdle()
        }

        val stateBeforeOperations = store.selectState<NavigationState>().first()
        assertEquals(2, stateBeforeOperations.activeGuidedFlowState?.currentStepIndex)

        // Use DSL to go back and modify
        store.guidedFlow(Route.TestFlow) {
            // Go back to previous step
            previousStep()
            
            // Update parameters for the current step
            updateStepParams(1, mapOf("backtracked" to true))
        }
        advanceUntilIdle()

        val stateAfterOperations = store.selectState<NavigationState>().first()
        
        // Should be back to step 1
        assertEquals(1, stateAfterOperations.activeGuidedFlowState?.currentStepIndex)
        assertEquals(DslTestScreen2, stateAfterOperations.currentEntry.navigatable)
        
        // Step 1 should have updated parameters
        val modifiedDefinition = stateAfterOperations.guidedFlowDefinitions[Route.TestFlow]
        assertNotNull(modifiedDefinition)
        val step1 = modifiedDefinition.steps[1]
        val params = step1.getParams()
        assertEquals(true, params["backtracked"])
    }
}