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
import io.github.syrou.reaktiv.navigation.param.SerializableParam
import io.github.syrou.reaktiv.navigation.param.typed
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.serialization.Serializable
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

    @Test
    fun `should find step by type correctly`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()

        // Test finding steps by type - we can't test return values inside the DSL,
        // but we can test that the operations work correctly
        var indices: Triple<Int, Int, Int>? = null
        store.guidedFlow(Route.TestFlow) {
            val screen1Index = findStepByType<DslTestScreen1>()
            val screen2Index = findStepByType<DslTestScreen2>()
            val screen3Index = findStepByType<DslTestScreen3>()
            indices = Triple(screen1Index, screen2Index, screen3Index)
            
            // Just add some no-op operations to complete the DSL block
            nextStep(mapOf("test" to true))
        }
        advanceUntilIdle()
        
        // Verify the indices were found correctly
        assertNotNull(indices)
        val (screen1Index, screen2Index, screen3Index) = indices!!
        assertEquals(0, screen1Index)
        assertEquals(1, screen2Index)
        assertEquals(2, screen3Index)
    }

    @Test
    fun `should remove step by type`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()

        val stateBefore = store.selectState<NavigationState>().first()
        val definitionBefore = stateBefore.guidedFlowDefinitions[Route.TestFlow]
        assertNotNull(definitionBefore)
        assertEquals(3, definitionBefore.steps.size)

        // Remove DslTestScreen2 by type
        store.guidedFlow(Route.TestFlow) {
            removeStep<DslTestScreen2>()
        }
        advanceUntilIdle()

        val stateAfter = store.selectState<NavigationState>().first()
        val definitionAfter = stateAfter.guidedFlowDefinitions[Route.TestFlow]
        assertNotNull(definitionAfter)
        assertEquals(2, definitionAfter.steps.size)
        
        // Verify DslTestScreen2 is no longer in the flow
        val remainingSteps = definitionAfter.steps.map { 
            when (it) {
                is GuidedFlowStep.TypedScreen -> it.screenClass
                is GuidedFlowStep.Route -> it.route
            }
        }
        assertTrue(DslTestScreen1::class.qualifiedName in remainingSteps)
        assertTrue(DslTestScreen2::class.qualifiedName !in remainingSteps)
        assertTrue(DslTestScreen3::class.qualifiedName in remainingSteps)
    }

    @Test
    fun `should replace step by type`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()

        // Replace DslTestScreen2 with DslTestScreen1 that has parameters
        store.guidedFlow(Route.TestFlow) {
            replaceStep<DslTestScreen2>(guidedFlowStep<DslTestScreen1>(mapOf("replaced" to true, "version" to 3)))
        }
        advanceUntilIdle()

        val stateAfter = store.selectState<NavigationState>().first()
        val definitionAfter = stateAfter.guidedFlowDefinitions[Route.TestFlow]
        assertNotNull(definitionAfter)
        assertEquals(3, definitionAfter.steps.size)
        
        // Verify the replacement at index 1 (original DslTestScreen2 position)
        val replacedStep = definitionAfter.steps[1]
        assertTrue(replacedStep is GuidedFlowStep.TypedScreen)
        assertEquals(DslTestScreen1::class.qualifiedName, replacedStep.screenClass)
        val params = replacedStep.getParams()
        assertEquals(true, params["replaced"])
        assertEquals(3, params["version"])
    }

    @Test
    fun `should update step params by type with raw parameters`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()

        // Update DslTestScreen2 parameters by type with raw parameters
        store.guidedFlow(Route.TestFlow) {
            updateStepParams<DslTestScreen2>(mapOf("userId" to "123", "timestamp" to 99999))
        }
        advanceUntilIdle()

        val stateAfter = store.selectState<NavigationState>().first()
        val definitionAfter = stateAfter.guidedFlowDefinitions[Route.TestFlow]
        assertNotNull(definitionAfter)
        
        // Verify DslTestScreen2 has the updated parameters
        val updatedStep = definitionAfter.steps[1] // DslTestScreen2 is at index 1
        val params = updatedStep.getParams()
        assertEquals("123", params["userId"])
        assertEquals(99999, params["timestamp"])
    }

    @Test
    fun `should update step params by type with typed parameters`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()

        // Create test data object for serialization
        @Serializable
        data class UserProfile(val id: String, val name: String, val age: Int)
        val testUser = UserProfile("user123", "Test User", 25)

        // Update DslTestScreen3 parameters by type with typed parameters
        store.guidedFlow(Route.TestFlow) {
            updateStepParams<DslTestScreen3> {
                put("user", testUser)
                putString("action", "view")
                putInt("retryCount", 3)
                putBoolean("isActive", true)
                param("rawData", mapOf("key" to "value"))
            }
        }
        advanceUntilIdle()

        val stateAfter = store.selectState<NavigationState>().first()
        val definitionAfter = stateAfter.guidedFlowDefinitions[Route.TestFlow]
        assertNotNull(definitionAfter)
        
        // Verify DslTestScreen3 has the updated typed parameters
        val updatedStep = definitionAfter.steps[2] // DslTestScreen3 is at index 2
        val params = updatedStep.getParams()
        
        // Check typed parameter (should be SerializableParam)
        assertTrue(params["user"] is SerializableParam<*>)
        val userParam = params["user"] as SerializableParam<*>
        assertEquals(testUser, userParam.value)
        
        // Check raw parameters
        assertEquals("view", params["action"])
        assertEquals(3, params["retryCount"])
        assertEquals(true, params["isActive"])
        assertEquals(mapOf("key" to "value"), params["rawData"])
    }

    @Test
    fun `should handle type-based operations when step not found using IfExists methods`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()

        val stateBefore = store.selectState<NavigationState>().first()
        val definitionBefore = stateBefore.guidedFlowDefinitions[Route.TestFlow]
        assertNotNull(definitionBefore)
        val originalStepsCount = definitionBefore.steps.size

        // Create a dummy screen class that doesn't exist in our flow
        class NonExistentScreen : Screen {
            override val route = "non-existent"
            override val enterTransition = NavTransition.None
            override val exitTransition = NavTransition.None
            override val requiresAuth = false
            override val titleResource: TitleResource = { "Non Existent" }
            @Composable
            override fun Content(params: Map<String, Any>) {}
        }

        // Try operations on non-existent screen type using IfExists methods (should not fail)
        var operationResults: Triple<Boolean, Boolean, Boolean>? = null
        store.guidedFlow(Route.TestFlow) {
            val removeResult = removeStepIfExists<NonExistentScreen>()
            val updateResult = updateStepParamsIfExists<NonExistentScreen>(mapOf("test" to "value"))
            val replaceResult = replaceStepIfExists<NonExistentScreen>(guidedFlowStep<DslTestScreen1>())
            operationResults = Triple(removeResult, updateResult, replaceResult)
            // Add a real operation so the DSL validation passes
            nextStep()
        }
        advanceUntilIdle()

        val stateAfter = store.selectState<NavigationState>().first()
        val definitionAfter = stateAfter.guidedFlowDefinitions[Route.TestFlow]
        assertNotNull(definitionAfter)
        
        // Flow should be unchanged since the screen type doesn't exist
        assertEquals(originalStepsCount, definitionAfter.steps.size)
        
        // All operations should return false indicating the type was not found
        assertNotNull(operationResults)
        val (removeResult, updateResult, replaceResult) = operationResults!!
        assertEquals(false, removeResult, "removeStepIfExists should return false for non-existent type")
        assertEquals(false, updateResult, "updateStepParamsIfExists should return false for non-existent type")
        assertEquals(false, replaceResult, "replaceStepIfExists should return false for non-existent type")
    }

    @Test
    fun `should throw exceptions when type-based operations cannot find step type`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()

        // Create a dummy screen class that doesn't exist in our flow
        class NonExistentScreen : Screen {
            override val route = "non-existent"
            override val enterTransition = NavTransition.None
            override val exitTransition = NavTransition.None
            override val requiresAuth = false
            override val titleResource: TitleResource = { "Non Existent" }
            @Composable
            override fun Content(params: Map<String, Any>) {}
        }

        // Test that removeStep throws exception
        var removeException: Exception? = null
        try {
            store.guidedFlow(Route.TestFlow) {
                removeStep<NonExistentScreen>()
                nextStep() // Add operation to pass validation
            }
            advanceUntilIdle()
        } catch (e: Exception) {
            removeException = e
        }
        
        assertNotNull(removeException)
        assertTrue(removeException is IllegalArgumentException)
        assertTrue(removeException.message!!.contains("NonExistentScreen not found"))

        // Test that replaceStep throws exception
        var replaceException: Exception? = null
        try {
            store.guidedFlow(Route.TestFlow) {
                replaceStep<NonExistentScreen>(guidedFlowStep<DslTestScreen1>())
                nextStep() // Add operation to pass validation
            }
            advanceUntilIdle()
        } catch (e: Exception) {
            replaceException = e
        }
        
        assertNotNull(replaceException)
        assertTrue(replaceException is IllegalArgumentException)
        assertTrue(replaceException.message!!.contains("NonExistentScreen not found"))

        // Test that updateStepParams throws exception
        var updateException: Exception? = null
        try {
            store.guidedFlow(Route.TestFlow) {
                updateStepParams<NonExistentScreen>(mapOf("test" to "value"))
                nextStep() // Add operation to pass validation
            }
            advanceUntilIdle()
        } catch (e: Exception) {
            updateException = e
        }
        
        assertNotNull(updateException)
        assertTrue(updateException is IllegalArgumentException)
        assertTrue(updateException.message!!.contains("NonExistentScreen not found"))
    }

    @Test
    fun `should verify successful type-based operations return true with IfExists methods`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Start guided flow
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()

        // Test that operations return true when they successfully find and operate on types
        var operationResults: Triple<Boolean, Boolean, Boolean>? = null
        store.guidedFlow(Route.TestFlow) {
            val updateResult = updateStepParamsIfExists<DslTestScreen2>(mapOf("test" to "value"))
            val replaceResult = replaceStepIfExists<DslTestScreen3>(guidedFlowStep<DslTestScreen1>(mapOf("replaced" to true)))
            val removeResult = removeStepIfExists<DslTestScreen1>()
            operationResults = Triple(updateResult, replaceResult, removeResult)
            nextStep()
        }
        advanceUntilIdle()

        // All operations should return true indicating success
        assertNotNull(operationResults)
        val (updateResult, replaceResult, removeResult) = operationResults!!
        assertEquals(true, updateResult, "updateStepParamsIfExists should return true for existing type")
        assertEquals(true, replaceResult, "replaceStepIfExists should return true for existing type")
        assertEquals(true, removeResult, "removeStepIfExists should return true for existing type")

        // Verify the operations actually took effect
        val stateAfter = store.selectState<NavigationState>().first()
        val definitionAfter = stateAfter.guidedFlowDefinitions[Route.TestFlow]
        assertNotNull(definitionAfter)
        
        // Should have 2 steps now (original 3 - 1 removed)
        assertEquals(2, definitionAfter.steps.size)
        
        // DslTestScreen2 should have updated parameters
        val updatedStep = definitionAfter.steps[0] // DslTestScreen2 is now at index 0 after DslTestScreen1 was removed
        assertEquals("value", updatedStep.getParams()["test"])
        
        // DslTestScreen3 should have been replaced with DslTestScreen1
        val replacedStep = definitionAfter.steps[1] // The replaced step
        assertTrue(replacedStep is GuidedFlowStep.TypedScreen)
        assertEquals(DslTestScreen1::class.qualifiedName, replacedStep.screenClass)
        assertEquals(true, replacedStep.getParams()["replaced"])
    }

    @Test
    fun `should verify full round-trip of typed parameters with serialization and deserialization`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Create complex test data objects
        @Serializable
        data class UserProfile(val id: String, val name: String, val age: Int, val isActive: Boolean)
        
        @Serializable
        data class Settings(val theme: String, val notifications: Boolean, val preferences: Map<String, String>)

        val testUser = UserProfile("user123", "John Doe", 30, true)
        val testSettings = Settings("dark", true, mapOf("language" to "en", "region" to "US"))

        // Start guided flow
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()

        // Update step parameters with complex typed data
        store.guidedFlow(Route.TestFlow) {
            updateStepParams<DslTestScreen2> {
                put("user", testUser)
                put("settings", testSettings)
                putString("sessionId", "session-456")
                putInt("retryCount", 3)
                putBoolean("debugMode", true)
                putDouble("version", 1.5)
                put("tags", listOf("important", "user-data", "test"))
                param("rawMetadata", mapOf("source" to "test", "timestamp" to 1234567890))
            }
        }
        advanceUntilIdle()

        // Verify the parameters were stored correctly in the flow definition
        val stateAfter = store.selectState<NavigationState>().first()
        val definitionAfter = stateAfter.guidedFlowDefinitions[Route.TestFlow]
        assertNotNull(definitionAfter)
        
        val updatedStep = definitionAfter.steps[1] // DslTestScreen2 is at index 1
        val params = updatedStep.getParams()

        // Verify typed parameters exist as SerializableParam objects
        assertTrue(params["user"] is SerializableParam<*>, "User parameter should be SerializableParam")
        assertTrue(params["settings"] is SerializableParam<*>, "Settings parameter should be SerializableParam")
        assertTrue(params["tags"] is SerializableParam<*>, "Tags parameter should be SerializableParam")
        
        // Verify raw parameters are stored directly
        assertEquals("session-456", params["sessionId"])
        assertEquals(3, params["retryCount"])
        assertEquals(true, params["debugMode"])
        assertEquals(1.5, params["version"])
        assertEquals(mapOf("source" to "test", "timestamp" to 1234567890), params["rawMetadata"])

        // Now test the critical part: can we deserialize the typed parameters correctly?
        val userParam = params["user"] as SerializableParam<*>
        val settingsParam = params["settings"] as SerializableParam<*>
        val tagsParam = params["tags"] as SerializableParam<*>

        // Verify the original values are preserved
        assertEquals(testUser, userParam.value, "User object should be preserved exactly")
        assertEquals(testSettings, settingsParam.value, "Settings object should be preserved exactly")
        assertEquals(listOf("important", "user-data", "test"), tagsParam.value, "List should be preserved exactly")

        // Verify serializers are correct types (this tests the reified generic worked correctly)
        assertNotNull(userParam.serializer, "User serializer should not be null")
        assertNotNull(settingsParam.serializer, "Settings serializer should not be null")
        assertNotNull(tagsParam.serializer, "Tags serializer should not be null")

        // Test navigation to the screen to ensure parameters work in practice
        // Navigate to step 1 (DslTestScreen2) which now has our updated parameters
        store.dispatch(NavigationAction.NextStep()) // Goes to step 1 (DslTestScreen2)
        advanceUntilIdle()

        val navigationState = store.selectState<NavigationState>().first()
        assertEquals(DslTestScreen2, navigationState.currentEntry.navigatable, "Should be on DslTestScreen2")
        assertEquals(1, navigationState.activeGuidedFlowState?.currentStepIndex, "Should be at step index 1")
        
        // Verify the current screen receives the parameters correctly
        // This is the critical test: do the typed parameters work when the screen actually receives them?
        val currentParams = navigationState.currentEntry.params
        assertNotNull(currentParams["user"], "User parameter should be present")
        assertNotNull(currentParams["settings"], "Settings parameter should be present")  
        assertNotNull(currentParams["tags"], "Tags parameter should be present")
        assertEquals("session-456", currentParams["sessionId"], "String parameter should match")
        assertEquals(3, currentParams["retryCount"], "Int parameter should match")
        assertEquals(true, currentParams["debugMode"], "Boolean parameter should match")
        assertEquals(1.5, currentParams["version"], "Double parameter should match")
    }


    @Test
    fun `should fail gracefully when trying to serialize non-serializable types`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        // Create a non-serializable class (missing @Serializable annotation)
        data class NonSerializableUser(val id: String, val name: String)
        val nonSerializableUser = NonSerializableUser("123", "John")

        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()

        // This should fail at compile time or runtime when trying to create the serializer
        var exceptionThrown = false
        try {
            store.guidedFlow(Route.TestFlow) {
                updateStepParams<DslTestScreen2> {
                    put("badUser", nonSerializableUser) // This should fail
                }
            }
            advanceUntilIdle()
        } catch (e: Exception) {
            exceptionThrown = true
            assertTrue(e.message?.contains("Serializer") == true || e.message?.contains("serializable") == true,
                "Exception should mention serialization issue: ${e.message}")
        }
        
        assertTrue(exceptionThrown, "Should throw exception for non-serializable type")
    }

    @Test
    fun `should verify typed parameters work in actual navigation scenario`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        // This is the critical test: Can we modify a step with typed parameters 
        // and then successfully read them when we navigate to that step?
        
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            module(createTestNavigationModule())
            coroutineContext(testDispatcher)
        }

        @Serializable
        data class UserData(val id: String, val name: String, val preferences: Map<String, String>)
        
        val userData = UserData(
            id = "user123", 
            name = "John Doe", 
            preferences = mapOf("theme" to "dark", "language" to "en")
        )

        // Start guided flow
        store.dispatch(NavigationAction.StartGuidedFlow(GuidedFlow(Route.TestFlow)))
        advanceUntilIdle()

        // Step 1: Use typed parameters to modify DslTestScreen2 step
        store.guidedFlow(Route.TestFlow) {
            updateStepParams<DslTestScreen2> {
                put("userData", userData)                    // Typed parameter
                putString("sessionId", "session-456")       // Raw string parameter  
                putInt("version", 2)                         // Raw int parameter
                putBoolean("isDebug", true)                  // Raw boolean parameter
            }
        }
        advanceUntilIdle()

        // Step 2: Navigate to the modified step
        store.dispatch(NavigationAction.NextStep()) // Goes to step 1 (DslTestScreen2)
        advanceUntilIdle()

        // Step 3: Verify we can read the parameters from the actual navigation state
        val navigationState = store.selectState<NavigationState>().first()
        assertEquals(DslTestScreen2, navigationState.currentEntry.navigatable, "Should be on DslTestScreen2")
        
        // Use .typed() to access parameters correctly through ParameterRetriever
        val typedParams = navigationState.currentEntry.params.typed()
        
        // Verify typed parameter can be accessed and matches original
        val receivedUserData = typedParams.get<UserData>("userData")
        assertNotNull(receivedUserData, "userData parameter should be present")
        
        // The critical test: can we correctly retrieve the typed parameter?
        assertEquals(userData, receivedUserData, "Typed parameter should match original userData")
        
        // Additional verification: does the screen actually have access to the nested properties?
        assertEquals("user123", receivedUserData!!.id)
        assertEquals("John Doe", receivedUserData.name)
        assertEquals("dark", receivedUserData.preferences["theme"])
        assertEquals("en", receivedUserData.preferences["language"])
        
        // Verify raw parameters work as expected
        assertEquals("session-456", typedParams.getString("sessionId"))
        assertEquals(2, typedParams.getInt("version"))
        assertEquals(true, typedParams.getBoolean("isDebug"))
    }
}