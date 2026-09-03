package io.github.syrou.reaktiv.core

import io.github.syrou.reaktiv.core.util.selectState
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

class StoreResetTest {

    @Serializable
    data class ProbeState(val count: Int = 0) : ModuleState

    sealed class ProbeAction : ModuleAction(ProbeModule::class) {
        data object Increment : ProbeAction()
        data object Gated : ProbeAction()
        data object ResetInline : ProbeAction()
    }

    class ProbeLogic(
        val storeAccessor: StoreAccessor,
        private val onBeforeReset: suspend ProbeLogic.() -> Unit
    ) : ModuleLogic() {
        override suspend fun beforeReset() = onBeforeReset()
    }

    class ProbeModule(
        private val onCreate: ProbeLogic.(generation: Int) -> Unit = {},
        private val onBeforeReset: suspend ProbeLogic.() -> Unit = {}
    ) : ModuleWithLogic<ProbeState, ProbeAction, ProbeLogic> {
        private var generations = 0
        override val initialState = ProbeState()
        override val reducer: (ProbeState, ProbeAction) -> ProbeState = { state, action ->
            when (action) {
                ProbeAction.Increment -> state.copy(count = state.count + 1)
                ProbeAction.Gated, ProbeAction.ResetInline -> state
            }
        }
        override val createLogic: (StoreAccessor) -> ProbeLogic = { accessor ->
            ProbeLogic(accessor, onBeforeReset).also { it.onCreate(generations++) }
        }
    }

    private fun store(module: ProbeModule = ProbeModule(), vararg middleware: Middleware) = createStore {
        module(module)
        middlewares(*middleware)
        coroutineContext(Dispatchers.Default)
    }

    @Test
    fun `reset waits for cancelled logic coroutines to unwind before beforeReset runs`() = runBlocking {
        val events = ConcurrentLinkedQueue<String>()
        val started = CompletableDeferred<Unit>()
        val module = ProbeModule(
            onCreate = { generation ->
                if (generation == 0) {
                    storeAccessor.launch {
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                delay(100)
                                events.add("unwound")
                            }
                        }
                    }
                }
            },
            onBeforeReset = { events.add("beforeReset") }
        )
        val store = store(module)
        try {
            withTimeout(5_000) { started.await() }
            assertTrue(withTimeout(5_000) { store.reset() })
            assertEquals(listOf("unwound", "beforeReset"), events.toList())
        } finally {
            store.cleanup()
        }
    }

    @Test
    fun `a NonCancellable dispatchAndAwait in retired logic completes during the reset`() = runBlocking {
        val events = ConcurrentLinkedQueue<String>()
        val started = CompletableDeferred<Unit>()
        val module = ProbeModule(
            onCreate = { generation ->
                if (generation == 0) {
                    storeAccessor.launch {
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                val result = storeAccessor.dispatchAndAwait(ProbeAction.Increment)
                                events.add("finally:$result")
                            }
                        }
                    }
                }
            },
            onBeforeReset = { events.add("beforeReset") }
        )
        val store = store(module)
        try {
            withTimeout(5_000) { started.await() }
            assertTrue(withTimeout(5_000) { store.reset() })
            assertEquals(listOf("finally:Processed", "beforeReset"), events.toList())
            assertEquals(0, store.selectState<ProbeState>().first().count)
        } finally {
            store.cleanup()
        }
    }

    @Test
    fun `dispatches queued before a reset are dropped instead of landing on the fresh state`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val gating = Middleware { action, _, _, updatedState ->
            if (action is ProbeAction.Gated) gate.await()
            updatedState(action)
        }
        val store = store(ProbeModule(), gating)
        val dropped = ConcurrentLinkedQueue<DispatchDropReason>()
        store.setDispatchInstrumentation(object : DispatchInstrumentation {
            override suspend fun onDispatchStarted(action: ModuleAction, queueWaitMs: Long, queueDepth: Long) = ""
            override fun onDispatchCompleted(token: String, applied: Boolean, durationMs: Long) {}
            override fun onDispatchFailed(token: String, error: Throwable, durationMs: Long) {}
            override suspend fun onDispatchDropped(action: ModuleAction, reason: DispatchDropReason) {
                dropped.add(reason)
            }
            override suspend fun onExternalControlChanged(enabled: Boolean) {}
        })
        try {
            store.initialized.first { it }
            store.dispatch(ProbeAction.Gated)
            val queued = (1..5).map {
                async(start = CoroutineStart.UNDISPATCHED) { store.dispatchAndAwait(ProbeAction.Increment) }
            }
            val reset = async(start = CoroutineStart.UNDISPATCHED) { store.reset() }
            gate.complete(Unit)
            assertTrue(withTimeout(5_000) { reset.await() })
            queued.awaitAll().forEach { assertEquals(DispatchResult.Blocked, it) }
            assertTrue(dropped.size >= queued.size, "every queued action reports as dropped, got $dropped")
            assertTrue(dropped.all { it == DispatchDropReason.RESET }, "drops are attributed to the reset, got $dropped")
            assertEquals(DispatchResult.Processed, store.dispatchAndAwait(ProbeAction.Increment))
            assertEquals(1, store.selectState<ProbeState>().first().count)
        } finally {
            store.cleanup()
        }
    }

    @Test
    fun `reset awaited from inside middleware fails fast instead of deadlocking`() = runBlocking {
        val resetting = Middleware { action, _, storeAccessor, updatedState ->
            if (action is ProbeAction.ResetInline) storeAccessor.reset()
            updatedState(action)
        }
        val store = store(ProbeModule(), resetting)
        try {
            store.initialized.first { it }
            val result = withTimeout(5_000) { store.dispatchAndAwait(ProbeAction.ResetInline) }
            assertIs<DispatchResult.Error>(result)
            assertIs<IllegalStateException>(result.cause)
            assertEquals(DispatchResult.Processed, store.dispatchAndAwait(ProbeAction.Increment))
        } finally {
            store.cleanup()
        }
    }

    @Test
    fun `selectLogic from middleware while a reset is in progress does not deadlock`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val looking = Middleware { action, _, storeAccessor, updatedState ->
            if (action is ProbeAction.Gated) {
                entered.complete(Unit)
                gate.await()
                storeAccessor.selectLogic(ProbeLogic::class)
            }
            updatedState(action)
        }
        val store = store(ProbeModule(), looking)
        try {
            store.initialized.first { it }
            store.dispatch(ProbeAction.Gated)
            withTimeout(5_000) { entered.await() }
            val reset = async(start = CoroutineStart.UNDISPATCHED) { store.reset() }
            assertFalse(store.initialized.value)
            gate.complete(Unit)
            assertTrue(withTimeout(5_000) { reset.await() })
            assertEquals(DispatchResult.Processed, store.dispatchAndAwait(ProbeAction.Increment))
        } finally {
            store.cleanup()
        }
    }

    @Test
    fun `dispatchAndAwait from inside middleware fails fast instead of waiting for itself`() = runBlocking {
        val awaiting = Middleware { action, _, storeAccessor, updatedState ->
            if (action is ProbeAction.Gated) storeAccessor.dispatchAndAwait(ProbeAction.Increment)
            updatedState(action)
        }
        val store = store(ProbeModule(), awaiting)
        try {
            store.initialized.first { it }
            val result = withTimeout(5_000) { store.dispatchAndAwait(ProbeAction.Gated) }
            assertIs<DispatchResult.Error>(result)
            assertIs<IllegalStateException>(result.cause)
            assertEquals(DispatchResult.Processed, store.dispatchAndAwait(ProbeAction.Increment))
        } finally {
            store.cleanup()
        }
    }

    @Test
    fun `a logic coroutine that awaits reset survives it and its follow-up lands on the fresh store`() = runBlocking {
        val events = ConcurrentLinkedQueue<String>()
        val done = CompletableDeferred<Unit>()
        val module = ProbeModule(
            onCreate = { generation ->
                if (generation == 0) {
                    storeAccessor.launch {
                        storeAccessor.dispatchAndAwait(ProbeAction.Increment)
                        events.add("reset:${storeAccessor.reset()}")
                        events.add("after:${storeAccessor.dispatchAndAwait(ProbeAction.Increment)}")
                        done.complete(Unit)
                    }
                }
            }
        )
        val store = store(module)
        try {
            withTimeout(5_000) { done.await() }
            assertEquals(listOf("reset:true", "after:Processed"), events.toList())
            assertEquals(1, store.selectState<ProbeState>().first().count)
            assertTrue(store.initialized.value)
        } finally {
            store.cleanup()
        }
    }

    @Test
    fun `resetAsync returns a job that completes normally`() = runBlocking {
        val store = store()
        try {
            store.initialized.first { it }
            val job = store.resetAsync()
            withTimeout(5_000) { job.join() }
            assertFalse(job.isCancelled)
            assertTrue(store.initialized.value)
        } finally {
            store.cleanup()
        }
    }

    @Test
    fun `a failing beforeReset is rethrown after the store has still been reset`() = runBlocking {
        val store = store(ProbeModule(onBeforeReset = { throw IllegalStateException("cleanup failed") }))
        try {
            store.initialized.first { it }
            store.dispatchAndAwait(ProbeAction.Increment)
            val failure = runCatching { withTimeout(5_000) { store.reset() } }.exceptionOrNull()
            assertIs<IllegalStateException>(failure)
            assertEquals("cleanup failed", failure.message)
            assertTrue(store.initialized.value)
            assertEquals(0, store.selectState<ProbeState>().first().count)
            assertEquals(DispatchResult.Processed, store.dispatchAndAwait(ProbeAction.Increment))
        } finally {
            store.cleanup()
        }
    }
}
