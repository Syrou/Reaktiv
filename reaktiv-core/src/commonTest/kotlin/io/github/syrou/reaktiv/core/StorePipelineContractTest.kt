package io.github.syrou.reaktiv.core

import io.github.syrou.reaktiv.core.util.selectState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Serializable
data class TallyState(val total: Int = 0) : ModuleState

sealed class TallyAction : ModuleAction(TallyModule::class) {
    data object Increment : TallyAction()
    data class Add(val amount: Int) : TallyAction()
}

class TallyLogic : ModuleLogic() {
    var released = false

    override suspend fun beforeReset() {
        released = true
    }
}

object TallyModule : ModuleWithLogic<TallyState, TallyAction, TallyLogic> {
    var lastLogic: TallyLogic? = null

    override val initialState = TallyState()
    override val reducer: (TallyState, TallyAction) -> TallyState = { state, action ->
        when (action) {
            TallyAction.Increment -> state.copy(total = state.total + 1)
            is TallyAction.Add -> state.copy(total = state.total + action.amount)
        }
    }
    override val createLogic: (StoreAccessor) -> TallyLogic = { TallyLogic().also { lastLogic = it } }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StorePipelineContractTest {

    private fun TestScope.store(block: StoreDSL.() -> Unit = {}): Store = createStore {
        module(TallyModule)
        coroutineContext(StandardTestDispatcher(testScheduler))
        block()
    }

    @Test
    fun `a middleware that rewrites an action passes the rewrite on in the same dispatch`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val seenBelow = mutableListOf<ModuleAction>()
            val rewrite = Middleware { action, _, _, next ->
                next(if (action == TallyAction.Increment) TallyAction.Add(5) else action)
            }
            val record = Middleware { action, _, _, next ->
                seenBelow += action
                next(action)
            }
            val store = store { middlewares(rewrite, record) }
            advanceUntilIdle()

            val result = store.dispatchAndAwait(TallyAction.Increment)

            assertEquals(DispatchResult.Processed, result)
            assertEquals(5, store.selectState<TallyState>().first().total)
            assertEquals(listOf<ModuleAction>(TallyAction.Add(5)), seenBelow)
            store.cleanup()
        }

    @Test
    fun `a dispatch dropped under external control says why`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store { externalState(ExternalStatePolicy.Allow) }
            advanceUntilIdle()
            store.externalState()!!.beginControl()

            val result = store.dispatchAndAwait(TallyAction.Increment)

            assertEquals(DispatchResult.Dropped(DispatchDropReason.EXTERNAL_CONTROL), result)
            store.cleanup()
        }

    @Test
    fun `a middleware that blocks still reports blocked`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store { middlewares(Middleware { _, _, _, _ -> }) }
            advanceUntilIdle()

            assertEquals(DispatchResult.Blocked, store.dispatchAndAwait(TallyAction.Increment))
            store.cleanup()
        }

    @Test
    fun `closing the store releases what its logic holds`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()
            val logic = TallyModule.lastLogic!!

            store.close()

            assertTrue(logic.released, "close must run the logic teardown")
        }
}
