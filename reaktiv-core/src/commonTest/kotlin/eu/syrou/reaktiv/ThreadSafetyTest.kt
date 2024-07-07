package eu.syrou.reaktiv

import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.createStore
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

// Define test module, state, and actions

object TestModule : Module<TestModule.TestState, TestModule.Action> {
    data class TestState(val value: Int) : ModuleState

    override val initialState = TestState(0)

    sealed class Action : ModuleAction(TestModule::class) {
        data object IncrementAction : Action()
        data object DecrementAction : Action()
    }

    override val reducer: (TestState, ModuleAction) -> TestState = { state, action ->
        when (action) {
            is Action.IncrementAction -> {
                state.copy(value = state.value + 1)
            }

            is Action.DecrementAction -> state.copy(value = state.value - 1)
            else -> state
        }
    }
    override val logic: ModuleLogic<Action> = ModuleLogic() { _, _ -> }
}


object TestModule2 : Module<TestModule2.TestState2, TestModule2.Action> {

    data class TestState2(val value: String) : ModuleState
    sealed class Action : ModuleAction(TestState2::class) {
        data class UpdateAction(val newValue: String) : Action()
    }

    override val initialState = TestState2("")
    override val reducer: (TestState2, ModuleAction) -> TestState2 = { state, action ->
        when (action) {
            is Action.UpdateAction -> state.copy(value = state.value + action.newValue)
            else -> state
        }
    }
    override val logic: ModuleLogic<Action> = ModuleLogic { _, _ -> }
}

// Additional data classes for the new tests
object ComplexModule : Module<ComplexModule.ComplexState, ComplexModule.ComplexAction> {
    data class ComplexState(val count: Int, val text: String) : ModuleState
    sealed class ComplexAction : ModuleAction(ComplexModule::class) {
        data class UpdateBoth(val increment: Int, val append: String) : ComplexAction()
    }

    override val initialState = ComplexState(0, "")
    override val reducer: (ComplexState, ModuleAction) -> ComplexState = { state, action ->
        when (action) {
            is ComplexAction.UpdateBoth -> state.copy(
                count = state.count + action.increment,
                text = state.text + action.append
            )

            else -> state
        }
    }
    override val logic: ModuleLogic<ComplexAction> = ModuleLogic { _, _ -> }
}

object LargeStateModule : Module<LargeStateModule.LargeState, LargeStateModule.LargeAction> {
    data class LargeState(val items: List<Int>) : ModuleState
    sealed class LargeAction : ModuleAction(LargeStateModule::class) {
        data class AddItem(val item: Int) : LargeAction()
    }

    override val initialState = LargeState(List(10000) { it })
    override val reducer: (LargeState, ModuleAction) -> LargeState = { state, action ->
        when (action) {
            is LargeAction.AddItem -> {
                state.copy(items = state.items + action.item)
            }

            else -> state
        }
    }
    override val logic: ModuleLogic<LargeAction> = ModuleLogic { _, _ -> }
}

class StoreTest {
        @Test
    fun testConcurrentDispatches() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            modules(TestModule)
            coroutineContext(testDispatcher)
        }

        val jobs = List(1000) { index ->
            launch {
                if (index % 2 == 0) {
                    store.dispatchSuspend(TestModule.Action.IncrementAction)
                } else {
                    store.dispatchSuspend(TestModule.Action.DecrementAction)
                }
            }
        }

        jobs.joinAll()

        val finalState = store.selectState<TestModule.TestState>().value
        assertEquals(0, finalState.value, "Final state should be 0 after equal increments and decrements")
    }


    @Test
    fun testRapidStateChanges() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            modules(TestModule)
            coroutineContext(testDispatcher)
        }

        val stateChanges = mutableListOf<Int>()
        val job = launch {
            store.selectState<TestModule.TestState>().collect {
                stateChanges.add(it.value)
            }
        }

        repeat(100) {
            store.dispatchSuspend(TestModule.Action.IncrementAction)
            println(it)
        }

        job.cancelAndJoin()
        assertEquals(100, stateChanges.size, "Should have captured all state changes")
        assertTrue(stateChanges.zipWithNext().all { (a, b) -> b == a + 1 }, "Each state change should increment by 1")
        store.cleanup()
    }

    @Test
    fun testConcurrentStateReads() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            modules(TestModule)
            coroutineContext(testDispatcher)
        }

        val readerJobs = List(100) {
            launch {
                repeat(100) {
                    store.selectState<TestModule.TestState>().value
                }
            }
        }

        val writerJob = launch {
            repeat(1000) {
                store.dispatchSuspend(TestModule.Action.IncrementAction)
            }
        }

        readerJobs.joinAll()
        writerJob.join()

        val finalState = store.selectState<TestModule.TestState>().value
        assertEquals(1000, finalState.value, "Final state should reflect all increments")
    }

    @Test
    fun testMiddlewareExecution() = runTest {
        var middlewareExecutions = 0
        val testMiddleware: Middleware = { action, _, next ->
            middlewareExecutions++
            next(action)
        }
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            modules(TestModule)
            middlewares(testMiddleware)
            coroutineContext(testDispatcher)
        }

        repeat(100) {
            store.dispatchSuspend(TestModule.Action.IncrementAction)
        }

        assertEquals(100, middlewareExecutions, "Middleware should be executed for each action")
        assertEquals(
            100,
            store.selectState<TestModule.TestState>().value.value,
            "State should reflect all increments"
        )
    }

    @Test
    fun testMultipleModuleConcurrency() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            modules(TestModule, TestModule2)
            coroutineContext(testDispatcher)
        }
        val jobs = List(1000) { index ->
            launch {
                if (index % 2 == 0) {
                    store.dispatchSuspend(TestModule.Action.IncrementAction)
                } else {
                    store.dispatchSuspend(TestModule2.Action.UpdateAction("a"))
                }
            }
        }

        jobs.joinAll()

        val finalState1 = store.selectState<TestModule.TestState>().value
        val finalState2 = store.selectState<TestModule2.TestState2>().value

        assertEquals(500, finalState1.value, "TestModule state should reflect all increments")
        assertEquals(500, finalState2.value.length, "TestModule2 state should reflect all updates")
    }


    @Test
    fun testComplexStateChanges() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            modules(ComplexModule)
            coroutineContext(testDispatcher)
        }

        repeat(100) { i ->
            store.dispatchSuspend(ComplexModule.ComplexAction.UpdateBoth(i, i.toString()))
        }

        val finalState = store.selectState<ComplexModule.ComplexState>().value
        assertEquals(4950, finalState.count) // Sum of numbers from 0 to 99
        assertEquals(
            "0123456789101112131415161718192021222324252627282930313233343536373839404142434445464748495051525354555657585960616263646566676869707172737475767778798081828384858687888990919293949596979899",
            finalState.text
        )
    }

    @Test
    fun testStoreLifecycle() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            modules(TestModule)
            coroutineContext(testDispatcher)
        }

        store.dispatchSuspend(TestModule.Action.IncrementAction)
        assertEquals(1, store.selectState<TestModule.TestState>().value.value)

        store.cleanup()

        assertFails {
            store.dispatchSuspend(TestModule.Action.IncrementAction)
        }
    }

    @Test
    fun testLargeStateChanges() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = createStore {
            modules(LargeStateModule)
            coroutineContext(testDispatcher)
        }

        repeat(1000) { i ->
            store.dispatchSuspend(LargeStateModule.LargeAction.AddItem(10000 + i))
        }

        val finalState = store.selectState<LargeStateModule.LargeState>().value
        assertEquals(11000, finalState.items.size)
        assertEquals(10999, finalState.items.last())
    }
}