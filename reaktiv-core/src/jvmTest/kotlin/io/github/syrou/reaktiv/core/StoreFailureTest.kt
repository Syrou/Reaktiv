package io.github.syrou.reaktiv.core

import io.github.syrou.reaktiv.core.util.selectState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalReaktivApi::class)
class StoreFailureTest {

    @Serializable
    data class CountState(val count: Int = 0) : ModuleState

    sealed class CountAction : ModuleAction(CountModule::class) {
        data object Increment : CountAction()
    }

    object CountModule : Module<CountState, CountAction> {
        override val initialState = CountState()
        override val reducer: (CountState, CountAction) -> CountState = { state, _ -> state.copy(count = state.count + 1) }
        override val createLogic: (StoreAccessor) -> ModuleLogic = { object : ModuleLogic() {} }
    }

    class FlakyLogic : ModuleLogic()

    private class FlakyModule(private val failOnCall: Int) : Module<CountState, CountAction> {
        private val calls = AtomicInteger(0)
        override val initialState = CountState()
        override val reducer: (CountState, CountAction) -> CountState = { state, _ -> state }
        override val createLogic: (StoreAccessor) -> ModuleLogic = {
            if (calls.incrementAndGet() == failOnCall) throw IllegalStateException("logic could not be created")
            FlakyLogic()
        }
    }

    @Test
    fun `a module whose logic cannot be created fails dispatches instead of hanging them`() = runBlocking<Unit> {
        val executor = Executors.newSingleThreadExecutor()
        val held = CountDownLatch(1)
        executor.execute { held.await() }
        val store = createStore {
            module(FlakyModule(failOnCall = 1))
            coroutineContext(executor.asCoroutineDispatcher())
        }
        store.addCrashListener(object : CrashListener {
            override suspend fun onLogicCrash(exception: Throwable, action: ModuleAction?) =
                CrashRecovery.NAVIGATE_TO_CRASH_SCREEN
        })
        held.countDown()

        val outcome: Any = withTimeout(5_000) {
            try {
                store.dispatchAndAwait(CountAction.Increment)
            } catch (e: IllegalStateException) {
                if (e is CancellationException) throw e
                e
            }
        }

        assertTrue(
            outcome is IllegalStateException || outcome is DispatchResult.Error,
            "expected a failure, got $outcome"
        )
        executor.shutdownNow()
    }

    @Test
    fun `a reset whose logic cannot be created reports that instead of hanging later callers`() = runBlocking<Unit> {
        val store = createStore {
            module(FlakyModule(failOnCall = 2))
            coroutineContext(Dispatchers.Default)
        }
        withTimeout(5_000) { store.initialized.first { it } }

        withTimeout(5_000) {
            try {
                store.reset()
            } catch (e: IllegalStateException) {
                if (e is CancellationException) throw e
            }
        }

        val failure = withTimeout(5_000) {
            try {
                store.selectLogic<FlakyLogic>()
                null
            } catch (e: IllegalStateException) {
                if (e is CancellationException) throw e
                e
            }
        }
        assertTrue(failure != null, "selectLogic after a failed reset must report the failure")
        store.cleanup()
    }

    @Test
    fun `instrumentation that throws does not stop the store from dispatching`() = runBlocking<Unit> {
        val store = createStore {
            module(CountModule)
            coroutineContext(Dispatchers.Default)
        }
        withTimeout(5_000) { store.initialized.first { it } }
        store.setDispatchInstrumentation(object : DispatchInstrumentation {
            override suspend fun onDispatchStarted(action: ModuleAction, queueWaitMs: Long, queueDepth: Long): String =
                throw IllegalStateException("broken instrumentation")

            override fun onDispatchCompleted(token: String, applied: Boolean, durationMs: Long) = Unit
            override fun onDispatchFailed(token: String, error: Throwable, durationMs: Long) = Unit
            override suspend fun onExternalControlChanged(enabled: Boolean) = Unit
        })

        withTimeout(5_000) {
            store.dispatchAndAwait(CountAction.Increment)
            store.dispatchAndAwait(CountAction.Increment)
        }

        assertEquals(2, store.selectState<CountState>().first().count)
        store.cleanup()
    }

    @Test
    fun `a dispatch to a closed store is cancelled and an awaited one reports the closed store`() = runBlocking<Unit> {
        val store = createStore {
            module(CountModule)
            coroutineContext(Dispatchers.Default)
        }
        withTimeout(5_000) { store.initialized.first { it } }
        store.close()

        assertFailsWith<CancellationException> { store.dispatch(CountAction.Increment) }
        val result = withTimeout(5_000) { store.dispatchAndAwait(CountAction.Increment) }

        assertIs<DispatchResult.Error>(result)
        assertIs<IllegalStateException>(result.cause)
    }

    @Test
    fun `a coroutine that dispatches after the store closed under it ends as cancelled`() = runBlocking<Unit> {
        val store = createStore {
            module(CountModule)
            coroutineContext(Dispatchers.Default)
        }
        withTimeout(5_000) { store.initialized.first { it } }
        val writing = CompletableDeferred<Unit>()
        val written = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Throwable?>()
        val job = store.launch {
            withContext(NonCancellable) {
                writing.complete(Unit)
                written.await()
            }
            store.dispatch(CountAction.Increment)
        }
        job.invokeOnCompletion { cause -> ended.complete(cause) }
        withTimeout(5_000) { writing.await() }

        store.close()
        written.complete(Unit)

        assertIs<CancellationException>(withTimeout(5_000) { ended.await() })
    }
}
