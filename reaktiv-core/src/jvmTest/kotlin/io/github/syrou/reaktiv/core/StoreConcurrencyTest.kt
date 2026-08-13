package io.github.syrou.reaktiv.core

import io.github.syrou.reaktiv.core.util.selectState
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

class StoreConcurrencyTest {

    @Serializable
    data class CounterState(
        val normal: Int = 0,
        val urgent: Int = 0,
        val seen: List<Int> = emptyList()
    ) : ModuleState

    sealed class CounterAction : ModuleAction(CounterModule::class) {
        data object Normal : CounterAction()
        data object Urgent : CounterAction(), HighPriorityAction
        data class Record(val sequence: Int) : CounterAction()
    }

    object CounterModule : Module<CounterState, CounterAction> {
        override val initialState = CounterState()
        override val reducer: (CounterState, CounterAction) -> CounterState = { state, action ->
            when (action) {
                is CounterAction.Normal -> state.copy(normal = state.normal + 1)
                is CounterAction.Urgent -> state.copy(urgent = state.urgent + 1)
                is CounterAction.Record -> state.copy(seen = state.seen + action.sequence)
            }
        }
        override val createLogic: (StoreAccessor) -> ModuleLogic = { object : ModuleLogic() {} }
    }

    private fun store() = createStore {
        module(CounterModule)
        coroutineContext(Dispatchers.Default)
    }

    @Test
    fun `fire-and-forget dispatch preserves program order`() = runBlocking {
        val store = store()
        val count = 2000
        try {
            repeat(count) { store.dispatch(CounterAction.Record(it)) }
            store.dispatchAndAwait(CounterAction.Normal)

            val seen = withTimeout(30_000) {
                store.selectState<CounterState>().first { it.seen.size == count }.seen
            }
            assertEquals(
                (0 until count).toList(),
                seen,
                "Actions dispatched in order from one coroutine must reach the reducer in order"
            )
        } finally {
            store.cleanup()
        }
    }

    @Test
    fun `mixed priority dispatches never lose an update`() = runBlocking {
        val store = store()
        val perPriority = 1000
        try {
            val jobs = (0 until 16).map { worker ->
                async(Dispatchers.Default) {
                    repeat(perPriority / 8) {
                        if (worker % 2 == 0) {
                            store.dispatch(CounterAction.Normal)
                        } else {
                            store.dispatch(CounterAction.Urgent)
                        }
                    }
                }
            }
            jobs.awaitAll()

            val state = withTimeout(30_000) {
                store.selectState<CounterState>().first {
                    it.normal == perPriority && it.urgent == perPriority
                }
            }
            assertEquals(perPriority, state.normal)
            assertEquals(perPriority, state.urgent)
        } finally {
            store.cleanup()
        }
    }

    @Test
    fun `reset under load leaves exactly one consumer`() = runBlocking {
        val store = store()
        try {
            store.initialized.first { it }

            val writer = launch(Dispatchers.Default) {
                repeat(500) { store.dispatch(CounterAction.Normal) }
            }
            store.reset()
            writer.join()

            val after = 250
            repeat(after) { store.dispatch(CounterAction.Normal) }
            store.dispatchAndAwait(CounterAction.Record(0))

            val state = store.selectState<CounterState>().first()
            assertTrue(
                state.normal in after..(500 + after),
                "Every post-reset action must be applied exactly once; got ${state.normal}"
            )
        } finally {
            store.cleanup()
        }
    }

    @Test
    fun `crash listeners survive concurrent registration`() = runBlocking {
        val store = store()
        try {
            store.initialized.first { it }
            val listeners = (0 until 64).map {
                object : CrashListener {
                    override suspend fun onLogicCrash(
                        exception: Throwable,
                        action: ModuleAction?
                    ): CrashRecovery = CrashRecovery.RETHROW
                }
            }

            val churn = (0 until 8).map { worker ->
                async(Dispatchers.Default) {
                    repeat(200) {
                        val listener = listeners[(worker * 8 + it) % listeners.size]
                        store.addCrashListener(listener)
                        store.removeCrashListener(listener)
                    }
                }
            }
            churn.awaitAll()

            store.dispatchAndAwait(CounterAction.Normal)
            assertEquals(1, store.selectState<CounterState>().first().normal)
        } finally {
            store.cleanup()
        }
    }

    @Test
    fun `cleanup completes callers waiting on dispatchAndAwait`() = runBlocking {
        val store = store()
        store.initialized.first { it }

        val settled = ConcurrentLinkedQueue<String>()
        val waiters = (0 until 8).map {
            async(Dispatchers.Default) {
                runCatching { store.dispatchAndAwait(CounterAction.Normal) }
                settled.add("done")
            }
        }

        store.cleanup()
        withTimeout(30_000) { waiters.awaitAll() }

        assertEquals(
            8,
            settled.size,
            "cleanup() must resolve every pending dispatchAndAwait rather than leave it suspended"
        )
    }
}
