package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.tracing.LogicTracer
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoreResetMemorySafetyTest {

    @Serializable
    data class HeavyState(val counter: Int = 0) : ModuleState

    sealed class HeavyAction : ModuleAction(HeavyModule::class) {
        data object Increment : HeavyAction()
    }

    class HeavyLogic(private val storeAccessor: StoreAccessor) : ModuleLogic<HeavyAction>() {
        suspend fun doWork(iteration: Int): String {
            return "result-$iteration"
        }

        suspend fun doMoreWork(a: Int, b: String): Int {
            return a + b.length
        }
    }

    object HeavyModule : Module<HeavyState, HeavyAction> {
        override val initialState = HeavyState()

        override val reducer: (HeavyState, HeavyAction) -> HeavyState = { state, action ->
            when (action) {
                is HeavyAction.Increment -> state.copy(counter = state.counter + 1)
            }
        }

        override val createLogic: (StoreAccessor) -> HeavyLogic = { storeAccessor ->
            HeavyLogic(storeAccessor)
        }
    }

    @Test
    fun `store reset clears session capture and leaves no leaked tracer entries`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val sessionCapture = SessionCapture(maxActions = 500, maxLogicEvents = 500)
        val config = IntrospectionConfig(
            clientId = "test-client",
            clientName = "MemorySafetyTest",
            platform = "JVM"
        )

        val store = createStore {
            coroutineContext(testDispatcher)
            module(IntrospectionModule(config, sessionCapture, PlatformContext()))
            module(CrashModule(PlatformContext(), sessionCapture))
            module(HeavyModule)
        }

        advanceUntilIdle()

        val heavyLogic = HeavyModule.selectLogic(store) as HeavyLogic

        // Dispatch many actions
        repeat(200) {
            store.dispatch(HeavyAction.Increment)
        }
        advanceUntilIdle()

        // Call traced logic methods
        repeat(50) { i ->
            heavyLogic.doWork(i)
            heavyLogic.doMoreWork(i, "test-$i")
        }
        advanceUntilIdle()

        // Pre-reset assertions
        val preResetHistory = sessionCapture.getSessionHistory()
        assertTrue(preResetHistory.actions.isNotEmpty(), "Should have captured actions before reset")
        assertTrue(preResetHistory.logicStarted.isNotEmpty(), "Should have captured logic events before reset")
        assertEquals(0, LogicTracer.pendingCallCount(), "All traced calls should have completed before reset")

        // Reset the store
        store.reset()

        // Check session state immediately after reset, before advanceUntilIdle().
        // sessionCapture.clear() is called synchronously during reset(), before any new
        // coroutines (e.g. CrashLogic.installCrashHandler) have had a chance to dispatch actions.
        val postResetHistory = sessionCapture.getSessionHistory()
        assertTrue(postResetHistory.actions.isEmpty(), "Session capture actions should be cleared after reset")
        assertTrue(postResetHistory.logicStarted.isEmpty(), "Session capture logic events should be cleared after reset")
        assertEquals(0, LogicTracer.pendingCallCount(), "No leaked tracer entries after reset")
        assertEquals(1, LogicTracer.observerCount(), "Observer should still be registered after reset")
        assertTrue(sessionCapture.isStarted(), "Session capture should still be active after reset")

        advanceUntilIdle()

        // Verify store still works after reset
        repeat(10) {
            store.dispatch(HeavyAction.Increment)
        }
        advanceUntilIdle()

        val postResetCaptures = sessionCapture.getSessionHistory()
        assertTrue(postResetCaptures.actions.isNotEmpty(), "Should capture actions after reset")
    }
}
