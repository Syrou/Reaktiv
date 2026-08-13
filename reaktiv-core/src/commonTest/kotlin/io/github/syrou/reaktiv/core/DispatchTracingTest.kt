package io.github.syrou.reaktiv.core

import io.github.syrou.reaktiv.core.util.currentTimeMillis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Serializable
data class DispatchTraceState(val count: Int = 0) : ModuleState

sealed class DispatchTraceAction : ModuleAction(DispatchTraceModule::class) {
    data object Increment : DispatchTraceAction()
}

object DispatchTraceModule : Module<DispatchTraceState, DispatchTraceAction> {
    override val initialState = DispatchTraceState()
    override val reducer: (DispatchTraceState, DispatchTraceAction) -> DispatchTraceState = { state, action ->
        when (action) {
            DispatchTraceAction.Increment -> state.copy(count = state.count + 1)
        }
    }
    override val createLogic: (StoreAccessor) -> ModuleLogic = { object : ModuleLogic() {} }
}

@Serializable
data class SlowReducerState(val count: Int = 0) : ModuleState

sealed class SlowReducerAction : ModuleAction(SlowReducerModule::class) {
    data object Increment : SlowReducerAction()
}

object SlowReducerModule : Module<SlowReducerState, SlowReducerAction> {
    override val initialState = SlowReducerState()
    override val reducer: (SlowReducerState, SlowReducerAction) -> SlowReducerState = { state, action ->
        when (action) {
            SlowReducerAction.Increment -> {
                burnMillis(10)
                state.copy(count = state.count + 1)
            }
        }
    }
    override val createLogic: (StoreAccessor) -> ModuleLogic = { object : ModuleLogic() {} }
}

internal fun burnMillis(ms: Long) {
    val start = currentTimeMillis()
    var spin = 0L
    while (currentTimeMillis() - start < ms) {
        spin += 1
    }
    check(spin >= 0)
}

@OptIn(ExperimentalCoroutinesApi::class)
class DispatchTracingTest {

    private class RecordingInstrumentation : DispatchInstrumentation {
        class Started(val actionType: String?, val queueWaitMs: Long, val queueDepth: Long)

        val started = mutableListOf<Started>()
        val completed = mutableListOf<Pair<String, Boolean>>()
        val phases = mutableListOf<Pair<String, Long>>()
        private var tokenCounter = 0

        override suspend fun onDispatchStarted(
            action: ModuleAction,
            queueWaitMs: Long,
            queueDepth: Long
        ): String {
            started.add(Started(action::class.simpleName, queueWaitMs, queueDepth))
            tokenCounter += 1
            return "token-$tokenCounter"
        }

        override fun onDispatchCompleted(token: String, applied: Boolean, durationMs: Long) {
            completed.add(token to applied)
        }

        override fun onDispatchFailed(token: String, error: Throwable, durationMs: Long) {}

        override suspend fun onDispatchDropped(action: ModuleAction) {}

        override fun newDispatchDecorator(): DispatchStepDecorator =
            DispatchStepDecorator { name, step ->
                { action ->
                    val startedAt = currentTimeMillis()
                    step(action)
                    phases.add(name to currentTimeMillis() - startedAt)
                }
            }

        override suspend fun onExternalControlChanged(enabled: Boolean) {}
    }

    @Test
    fun `processed dispatch reports queue metrics and completion`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(DispatchTraceModule)
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            val instrumentation = RecordingInstrumentation()
            store.setDispatchInstrumentation(instrumentation)

            store.dispatch(DispatchTraceAction.Increment)
            advanceUntilIdle()

            assertEquals(1, instrumentation.started.size)
            assertEquals("Increment", instrumentation.started[0].actionType)
            assertTrue(instrumentation.started[0].queueDepth >= 1)

            assertEquals(1, instrumentation.completed.size)
            assertEquals(true, instrumentation.completed[0].second)
            store.cleanup()
        }

    @Test
    fun `blocked dispatch reports an unapplied completion`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val blocking: Middleware = { action, _, _, updatedState ->
                if (action !is DispatchTraceAction.Increment) {
                    updatedState(action)
                }
            }
            val store = createStore {
                module(DispatchTraceModule)
                middlewares(blocking)
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            val instrumentation = RecordingInstrumentation()
            store.setDispatchInstrumentation(instrumentation)

            store.dispatch(DispatchTraceAction.Increment)
            advanceUntilIdle()

            assertEquals(1, instrumentation.completed.size)
            assertEquals(false, instrumentation.completed[0].second)
            store.cleanup()
        }

    @Test
    fun `a slow reducer reports its phase self time`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(SlowReducerModule)
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            val instrumentation = RecordingInstrumentation()
            store.setDispatchInstrumentation(instrumentation)

            store.dispatch(SlowReducerAction.Increment)
            advanceUntilIdle()

            val reducerPhase = instrumentation.phases.single { it.first == "reducer" }
            assertTrue(reducerPhase.second >= 5L, "Reducer self time was ${reducerPhase.second}")
            store.cleanup()
        }

    @Test
    fun `a slow middleware reports a named phase excluding inner chain time`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val slow: Middleware = { action, _, _, updatedState ->
                burnMillis(10)
                updatedState(action)
            }
            val store = createStore {
                module(SlowReducerModule)
                middlewares(slow)
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            val instrumentation = RecordingInstrumentation()
            store.setDispatchInstrumentation(instrumentation)

            store.dispatch(SlowReducerAction.Increment)
            advanceUntilIdle()

            val middlewarePhase = instrumentation.phases.single { it.first.contains("[0]") }
            assertTrue(middlewarePhase.second >= 5L)
            val reducerPhase = instrumentation.phases.single { it.first == "reducer" }
            assertTrue(reducerPhase.second >= 5L)
            assertTrue(middlewarePhase.second >= reducerPhase.second)
            store.cleanup()
        }

    @Test
    fun `no instrumentation means dispatch runs untimed`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(DispatchTraceModule)
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.dispatch(DispatchTraceAction.Increment)
            advanceUntilIdle()

            val instrumentation = RecordingInstrumentation()
            store.setDispatchInstrumentation(instrumentation)
            assertEquals(0, instrumentation.started.size)
            store.cleanup()
        }
}
