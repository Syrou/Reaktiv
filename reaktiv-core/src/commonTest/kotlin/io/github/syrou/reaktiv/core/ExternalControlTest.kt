package io.github.syrou.reaktiv.core

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

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalControlTest {

    @AfterTest
    fun tearDown() {
        ExternalControlModule.lastLogic = null
    }

    @Test
    fun `plain actions are dropped and exempt actions still apply under external control`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(ExternalControlModule)
                externalState(ExternalStatePolicy.Allow)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.externalState()!!.beginControl()
            advanceUntilIdle()

            assertEquals(DispatchResult.Dropped(DispatchDropReason.EXTERNAL_CONTROL), store.dispatchAndAwait(ExternalControlAction.Plain))
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
                externalState(ExternalStatePolicy.Allow)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.externalState()!!.beginControl()
            advanceUntilIdle()

            store.externalState()!!.hydrate(
                mapOf(
                    ExternalControlState::class.qualifiedName!! to ExternalControlState(plainCount = 42)
                ),
                HydrateSource.External("test")
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
                externalState(ExternalStatePolicy.Allow)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()
            val logic = ExternalControlModule.lastLogic!!

            store.externalState()!!.beginControl()
            store.externalState()!!.beginControl()
            store.externalState()!!.endControl()
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
                externalState(ExternalStatePolicy.Allow)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.externalState()!!.beginControl()
            store.dispatchAndAwait(ExternalControlAction.Plain)
            store.externalState()!!.endControl()
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
                externalState(ExternalStatePolicy.Allow)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.externalState()!!.beginControl()
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
    fun `dropped dispatch reports through the instrumentation seam`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(ExternalControlModule)
                externalState(ExternalStatePolicy.Allow)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.externalState()!!.beginControl()
            advanceUntilIdle()

            val dropped = mutableListOf<String?>()
            store.setDispatchInstrumentation(object : DispatchInstrumentation {
                override suspend fun onDispatchStarted(
                    action: ModuleAction,
                    queueWaitMs: Long,
                    queueDepth: Long
                ): String = ""

                override fun onDispatchCompleted(token: String, applied: Boolean, durationMs: Long) {}

                override fun onDispatchFailed(token: String, error: Throwable, durationMs: Long) {}

                override suspend fun onDispatchDropped(action: ModuleAction, reason: DispatchDropReason) {
                    dropped.add("${action::class.simpleName}:$reason")
                }

                override suspend fun onExternalControlChanged(enabled: Boolean) {}
            })

            assertEquals(DispatchResult.Dropped(DispatchDropReason.EXTERNAL_CONTROL), store.dispatchAndAwait(ExternalControlAction.Plain))
            advanceUntilIdle()

            assertEquals<List<String?>>(listOf("Plain:EXTERNAL_CONTROL"), dropped)
            store.cleanup()
        }
}
