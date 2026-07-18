package io.github.syrou.reaktiv.core

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.tracing.LogicObserver
import io.github.syrou.reaktiv.core.tracing.LogicTracer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
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

@OptIn(ExperimentalCoroutinesApi::class)
class DispatchTracingTest {

    private class RecordingObserver : LogicObserver {
        val started = mutableListOf<LogicMethodStart>()
        val completed = mutableListOf<LogicMethodCompleted>()
        val failed = mutableListOf<LogicMethodFailed>()

        override fun onMethodStart(event: LogicMethodStart) {
            started.add(event)
        }

        override fun onMethodCompleted(event: LogicMethodCompleted) {
            completed.add(event)
        }

        override fun onMethodFailed(event: LogicMethodFailed) {
            failed.add(event)
        }

        fun dispatchStarts() = started.filter { it.logicClass == "StoreDispatch" }

        fun dispatchCompletions() = completed.filter { completion ->
            dispatchStarts().any { it.callId == completion.callId }
        }
    }

    @AfterTest
    fun tearDown() {
        LogicTracer.clearObservers()
    }

    @Test
    fun `processed dispatch is traced with queue metrics`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(DispatchTraceModule)
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            val observer = RecordingObserver()
            LogicTracer.addObserver(observer)

            store.dispatch(DispatchTraceAction.Increment)
            advanceUntilIdle()

            val starts = observer.dispatchStarts()
            assertEquals(1, starts.size)
            assertEquals("Increment", starts[0].methodName)
            assertTrue(starts[0].params.containsKey("queueWaitMs"))
            assertTrue((starts[0].params["queueDepth"]?.toInt() ?: 0) >= 1)

            val completions = observer.dispatchCompletions()
            assertEquals(1, completions.size)
            assertEquals("Processed", completions[0].result)
            store.cleanup()
        }

    @Test
    fun `blocked dispatch reports Blocked`() =
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

            val observer = RecordingObserver()
            LogicTracer.addObserver(observer)

            store.dispatch(DispatchTraceAction.Increment)
            advanceUntilIdle()

            val completions = observer.dispatchCompletions()
            assertEquals(1, completions.size)
            assertEquals("Blocked", completions[0].result)
            store.cleanup()
        }

    @Test
    fun `no dispatch trace without an observer`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(DispatchTraceModule)
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            store.dispatch(DispatchTraceAction.Increment)
            advanceUntilIdle()

            val observer = RecordingObserver()
            LogicTracer.addObserver(observer)
            assertEquals(0, observer.dispatchStarts().size)
            store.cleanup()
        }
}
