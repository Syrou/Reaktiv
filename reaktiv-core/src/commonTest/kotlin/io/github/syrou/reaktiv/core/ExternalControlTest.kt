package io.github.syrou.reaktiv.core

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.tracing.LogicObserver
import io.github.syrou.reaktiv.core.tracing.LogicTracer
import io.github.syrou.reaktiv.core.util.selectState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Serializable
data class ExternalControlState(
    val plainCount: Int = 0,
    val exemptCount: Int = 0
) : ModuleState

sealed class ExternalControlAction : ModuleAction(ExternalControlModule::class) {
    data object Plain : ExternalControlAction()
    data object Exempt : ExternalControlAction(), ExternalControlExempt
}

class ExternalControlLogic : ModuleLogic() {
    val transitions = mutableListOf<Boolean>()

    override suspend fun onExternalControlChanged(externallyDriven: Boolean) {
        transitions.add(externallyDriven)
    }
}

object ExternalControlModule : ModuleWithLogic<ExternalControlState, ExternalControlAction, ExternalControlLogic> {
    var lastLogic: ExternalControlLogic? = null

    override val initialState = ExternalControlState()

    override val reducer: (ExternalControlState, ExternalControlAction) -> ExternalControlState = { state, action ->
        when (action) {
            ExternalControlAction.Plain -> state.copy(plainCount = state.plainCount + 1)
            ExternalControlAction.Exempt -> state.copy(exemptCount = state.exemptCount + 1)
        }
    }

    override val createLogic: (StoreAccessor) -> ExternalControlLogic = {
        ExternalControlLogic().also { lastLogic = it }
    }
}

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalReaktivApi::class)
class ExternalControlTest {

    private class RecordingObserver : LogicObserver {
        val started = mutableListOf<LogicMethodStart>()
        val completed = mutableListOf<LogicMethodCompleted>()

        override fun onMethodStart(event: LogicMethodStart) {
            started.add(event)
        }

        override fun onMethodCompleted(event: LogicMethodCompleted) {
            completed.add(event)
        }

        override fun onMethodFailed(event: LogicMethodFailed) {}
    }

    @AfterTest
    fun tearDown() {
        LogicTracer.clearObservers()
        ExternalControlModule.lastLogic = null
    }

    @Test
    fun `plain actions are dropped and exempt actions still apply under external control`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(ExternalControlModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.beginExternalControl()
            advanceUntilIdle()

            assertEquals(DispatchResult.Blocked, store.dispatchAndAwait(ExternalControlAction.Plain))
            assertEquals(DispatchResult.Processed, store.dispatchAndAwait(ExternalControlAction.Exempt))
            advanceUntilIdle()

            val state = store.selectState<ExternalControlState>().first()
            assertEquals(0, state.plainCount)
            assertEquals(1, state.exemptCount)
            store.cleanup()
        }

    @Test
    fun `external state projection still applies while gated`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(ExternalControlModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.beginExternalControl()
            advanceUntilIdle()

            store.applyExternalStates(
                mapOf(
                    ExternalControlState::class.qualifiedName!! to ExternalControlState(plainCount = 42)
                )
            )
            advanceUntilIdle()

            assertEquals(42, store.selectState<ExternalControlState>().first().plainCount)
            store.cleanup()
        }

    @Test
    fun `logic is notified entering and leaving external control`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(ExternalControlModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()
            val logic = ExternalControlModule.lastLogic!!

            store.beginExternalControl()
            store.beginExternalControl()
            store.endExternalControl()
            advanceUntilIdle()

            assertContentEquals(listOf(true, false), logic.transitions)
            assertFalse(store.isExternallyDriven)
            store.cleanup()
        }

    @Test
    fun `dispatch works again after leaving external control`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(ExternalControlModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.beginExternalControl()
            store.dispatchAndAwait(ExternalControlAction.Plain)
            store.endExternalControl()
            store.dispatchAndAwait(ExternalControlAction.Plain)
            advanceUntilIdle()

            assertEquals(1, store.selectState<ExternalControlState>().first().plainCount)
            store.cleanup()
        }

    @Test
    fun `reset clears external control`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(ExternalControlModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.beginExternalControl()
            advanceUntilIdle()
            assertTrue(store.isExternallyDriven)

            store.reset()
            advanceUntilIdle()

            assertFalse(store.isExternallyDriven)
            assertEquals(DispatchResult.Processed, store.dispatchAndAwait(ExternalControlAction.Plain))
            advanceUntilIdle()
            store.cleanup()
        }

    @Test
    fun `markExternallyDriven gates without notifying logic`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(ExternalControlModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()
            val logic = ExternalControlModule.lastLogic!!

            store.markExternallyDriven()
            assertTrue(store.isExternallyDriven)
            assertEquals(DispatchResult.Blocked, store.dispatchAndAwait(ExternalControlAction.Plain))
            advanceUntilIdle()

            assertTrue(logic.transitions.isEmpty())
            store.cleanup()
        }

    @Test
    fun `dropped dispatch is traced with an externalControl marker`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(ExternalControlModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.beginExternalControl()
            advanceUntilIdle()

            val observer = RecordingObserver()
            LogicTracer.addObserver(observer)

            store.dispatchAndAwait(ExternalControlAction.Plain)
            advanceUntilIdle()

            val drop = observer.started.single { it.params["externalControl"] == "dropped" }
            assertEquals("Plain", drop.methodName)
            assertEquals("Blocked", observer.completed.single { it.callId == drop.callId }.result)
            store.cleanup()
        }
}
