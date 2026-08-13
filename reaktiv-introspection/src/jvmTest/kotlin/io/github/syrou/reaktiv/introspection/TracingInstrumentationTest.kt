package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.tracing.LogicObserver
import io.github.syrou.reaktiv.core.tracing.LogicTracer
import io.github.syrou.reaktiv.tracing.annotations.Trace
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class TracedRepository {

    @Trace
    suspend fun fetchThings(count: Int): String {
        return "things-$count"
    }

    suspend fun untraced(): String = "plain"
}

@Serializable
data class ProvenanceState(val count: Int = 0) : ModuleState

sealed class ProvenanceAction : ModuleAction(ProvenanceModule::class) {
    data object Bump : ProvenanceAction()
}

object ProvenanceModule : Module<ProvenanceState, ProvenanceAction> {
    override val initialState = ProvenanceState()
    override val reducer: (ProvenanceState, ProvenanceAction) -> ProvenanceState = { state, action ->
        when (action) {
            ProvenanceAction.Bump -> state.copy(count = state.count + 1)
        }
    }
    override val createLogic: (StoreAccessor) -> ModuleLogic = { object : ModuleLogic() {} }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TracingInstrumentationTest {

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
    }

    @Test
    fun `a Trace annotated function outside ModuleLogic is instrumented`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val observer = RecordingObserver()
            LogicTracer.addObserver(observer)

            val repository = TracedRepository()
            assertEquals("things-3", repository.fetchThings(3))
            assertEquals("plain", repository.untraced())

            val start = observer.started.single { it.methodName == "fetchThings" }
            assertTrue(start.logicClass.contains("TracedRepository"))
            assertEquals("3", start.params["count"])
            observer.completed.single { it.callId == start.callId }
            assertTrue(observer.started.none { it.methodName == "untraced" })
        }

    @Test
    fun `a dispatch call site records its origin on the dispatch span`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(ProvenanceModule)
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            val observer = RecordingObserver()
            LogicTracer.addObserver(observer)
            store.setDispatchInstrumentation(DispatchTracingInstrumentation())

            store.dispatch(ProvenanceAction.Bump)
            advanceUntilIdle()

            val dispatchStart = observer.started.single { it.logicClass == "StoreDispatch" }
            val origin = dispatchStart.params["dispatchedFrom"]
            assertNotNull(origin, "Dispatch span must carry its call site, params were ${dispatchStart.params}")
            assertTrue(
                origin.contains("TracingInstrumentationTest"),
                "Origin must name the dispatching function and file, was: $origin"
            )
            store.cleanup()
        }
}
