package io.syrou.reaktiv

import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StateSelector
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.createStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StateSelectorTest {

    // Test state
    data class TestState(val value: Int, val name: String) : ModuleState

    // Test actions
    sealed class TestAction : ModuleAction(TestModule::class) {
        data object Increment : TestAction()
        data class SetName(val name: String) : TestAction()
    }

    // Test module
    object TestModule : Module<TestState, TestAction> {
        override val initialState = TestState(0, "initial")

        override val reducer: (TestState, TestAction) -> TestState = { state, action ->
            when (action) {
                is TestAction.Increment -> state.copy(value = state.value + 1)
                is TestAction.SetName -> state.copy(name = action.name)
            }
        }

        override val createLogic: (StoreAccessor) -> ModuleLogic<TestAction> = { _ ->
            ModuleLogic { _ -> }
        }
    }

    data class SecondState(val count: Int) : ModuleState

    sealed class SecondAction : ModuleAction(SecondModule::class) {
        data object Decrement : SecondAction()
    }

    object SecondModule : Module<SecondState, SecondAction> {
        override val initialState = SecondState(100)
        override val reducer: (SecondState, SecondAction) -> SecondState = { state, action ->
            when (action) {
                is SecondAction.Decrement -> state.copy(count = state.count - 1)
            }
        }
        override val createLogic: (StoreAccessor) -> ModuleLogic<SecondAction> = { _ ->
            ModuleLogic { _ -> }
        }
    }

    @Test
    fun `stateSelector is automatically available on module`() {
        // Verify that the default implementation provides a selector
        val selector = TestModule.stateSelector
        assertNotNull(selector, "StateSelector should be automatically available")
    }

    @Test
    fun `stateSelector select() returns correct StateFlow`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            coroutineContext(testDispatcher)
            module(TestModule)
        }

        // Get selector from module
        val selector = TestModule.stateSelector

        // Use selector to get StateFlow
        val stateFlow = selector.select(store)
        assertNotNull(stateFlow, "StateFlow should not be null")

        // Verify initial state
        val initialState = stateFlow.first()
        assertEquals(0, initialState.value)
        assertEquals("initial", initialState.name)

        store.cleanup()
    }

    @Test
    fun `stateSelector selectNow() works without suspend`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            coroutineContext(testDispatcher)
            module(TestModule)
        }

        // Wait for store to initialize
        advanceUntilIdle()

        // Get selector and use non-suspend version
        val selector = TestModule.stateSelector
        val stateFlow = selector.selectNow(store)

        // Verify it works
        val state = stateFlow.value
        assertEquals(0, state.value)
        assertEquals("initial", state.name)

        store.cleanup()
    }

    @Test
    fun `stateSelector getValue() returns current state value`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            coroutineContext(testDispatcher)
            module(TestModule)
        }

        // Wait for store to initialize
        advanceUntilIdle()

        // Get current value
        val selector = TestModule.stateSelector
        val currentState = selector.getValue(store)

        assertEquals(0, currentState.value)
        assertEquals("initial", currentState.name)

        store.cleanup()
    }

    @Test
    fun `stateSelector observes state changes after actions`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            coroutineContext(testDispatcher)
            module(TestModule)
        }

        val selector = TestModule.stateSelector
        val stateFlow = selector.select(store)

        // Collect states
        val states = mutableListOf<TestState>()
        val job = backgroundScope.launch {
            stateFlow.collect { states.add(it) }
        }

        advanceUntilIdle()

        // Dispatch actions
        store.dispatch(TestAction.Increment)
        advanceUntilIdle()

        store.dispatch(TestAction.Increment)
        advanceUntilIdle()

        store.dispatch(TestAction.SetName("updated"))
        advanceUntilIdle()

        job.cancel()

        // Verify we collected all state changes
        assertEquals(4, states.size) // initial + 3 updates
        assertEquals(0, states[0].value)
        assertEquals(1, states[1].value)
        assertEquals(2, states[2].value)
        assertEquals(2, states[3].value)
        assertEquals("updated", states[3].name)

        store.cleanup()
    }

    @Test
    fun `stateSelector getValue() reflects latest state after actions`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            coroutineContext(testDispatcher)
            module(TestModule)
        }

        val selector = TestModule.stateSelector

        // Initial value
        advanceUntilIdle()
        assertEquals(0, selector.getValue(store).value)

        // After increment
        store.dispatch(TestAction.Increment)
        advanceUntilIdle()
        assertEquals(1, selector.getValue(store).value)

        // After another increment
        store.dispatch(TestAction.Increment)
        advanceUntilIdle()
        assertEquals(2, selector.getValue(store).value)

        // After name change
        store.dispatch(TestAction.SetName("test"))
        advanceUntilIdle()
        assertEquals("test", selector.getValue(store).name)

        store.cleanup()
    }

    @Test
    fun `multiple selectors for different modules work independently`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            coroutineContext(testDispatcher)
            module(TestModule)
            module(SecondModule)
        }

        // Get selectors for both modules
        val selector1 = TestModule.stateSelector
        val selector2 = SecondModule.stateSelector

        advanceUntilIdle()

        // Verify initial states
        assertEquals(0, selector1.getValue(store).value)
        assertEquals(100, selector2.getValue(store).count)

        // Dispatch actions to both modules
        store.dispatch(TestAction.Increment)
        store.dispatch(SecondAction.Decrement)
        advanceUntilIdle()

        // Verify states changed independently
        assertEquals(1, selector1.getValue(store).value)
        assertEquals(99, selector2.getValue(store).count)

        store.cleanup()
    }

    @Test
    fun `stateSelector can be used multiple times on same module`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            coroutineContext(testDispatcher)
            module(TestModule)
        }

        // Get selector multiple times
        val selector1 = TestModule.stateSelector
        val selector2 = TestModule.stateSelector

        advanceUntilIdle()

        // Both should return the same state
        val state1 = selector1.getValue(store)
        val state2 = selector2.getValue(store)

        assertEquals(state1.value, state2.value)
        assertEquals(state1.name, state2.name)

        // Dispatch action
        store.dispatch(TestAction.Increment)
        advanceUntilIdle()

        // Both should see the update
        assertEquals(1, selector1.getValue(store).value)
        assertEquals(1, selector2.getValue(store).value)

        store.cleanup()
    }
}