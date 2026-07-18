package io.github.syrou.reaktiv.test

import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@Serializable
data class CounterState(val count: Int = 0, val autoSaved: Boolean = false) : ModuleState

sealed class CounterAction : ModuleAction(CounterModule::class) {
    data object Increment : CounterAction()
    data object IncrementLater : CounterAction()
    data object AutoSave : CounterAction()
}

object CounterModule : Module<CounterState, CounterAction> {
    override val initialState = CounterState()
    override val reducer: (CounterState, CounterAction) -> CounterState = { state, action ->
        when (action) {
            CounterAction.Increment -> state.copy(count = state.count + 1)
            CounterAction.IncrementLater -> state
            CounterAction.AutoSave -> state.copy(autoSaved = true)
        }
    }
    override val createLogic: (StoreAccessor) -> ModuleLogic = { object : ModuleLogic() {} }
    override val createMiddleware: (() -> Middleware) = {
        { action, _, storeAccessor, updatedState ->
            updatedState(action)
            if (action is CounterAction.IncrementLater) {
                storeAccessor.launch {
                    delay(500)
                    storeAccessor.dispatch(CounterAction.Increment)
                }
            }
        }
    }
}

class ReaktivTestKitTest {

    @Test
    fun `dispatch settles before the next assertion`() = reaktivTest(CounterModule) {
        dispatch(CounterAction.Increment)
        dispatch(CounterAction.Increment)
        assertEquals(2, currentState<CounterState>().count)
    }

    @Test
    fun `settle drains delayed side effects through virtual time`() = reaktivTest(CounterModule) {
        dispatch(CounterAction.IncrementLater)
        assertEquals(1, currentState<CounterState>().count)
    }

    @Test
    fun `awaitState suspends until the predicate matches`() = reaktivTest(CounterModule) {
        store.dispatch(CounterAction.IncrementLater)
        val state = awaitState<CounterState> { it.count == 1 }
        assertEquals(1, state.count)
    }

    @Test
    fun `assertDispatched finds actions dispatched by middleware`() = reaktivTest(CounterModule) {
        dispatch(CounterAction.IncrementLater)
        assertDispatched<CounterAction.Increment>()
    }

    @Test
    fun `assertDispatched fails with recorded action names when missing`() = reaktivTest(CounterModule) {
        dispatch(CounterAction.Increment)
        val failure = assertFailsWith<AssertionError> {
            assertDispatched<CounterAction.AutoSave>()
        }
        assertTrue(failure.message!!.contains("AutoSave"))
        assertTrue(failure.message!!.contains("Increment"))
    }

    @Test
    fun `assertNotDispatched passes when the action never fired`() = reaktivTest(CounterModule) {
        dispatch(CounterAction.Increment)
        assertNotDispatched<CounterAction.AutoSave>()
    }

    @Test
    fun `advanceTimeBy exposes time-gated behavior without settling everything`() = reaktivTest(CounterModule) {
        val states = store.selectState<CounterState>()
        store.dispatch(CounterAction.IncrementLater)
        testScope.runCurrent()
        advanceTimeBy(400.milliseconds)
        assertEquals(0, states.value.count)
        advanceTimeBy(200.milliseconds)
        assertEquals(1, states.value.count)
    }

    @Test
    fun `configure block extends the store with additional setup`() {
        val seen = mutableListOf<String>()
        val observing: Middleware = { action, _, _, updatedState ->
            seen.add(action::class.simpleName ?: "?")
            updatedState(action)
        }
        reaktivTest(CounterModule, configure = { middlewares(observing) }) {
            dispatch(CounterAction.Increment)
            assertTrue(seen.contains("Increment"))
        }
    }
}
