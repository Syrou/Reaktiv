package io.github.syrou.reaktiv.core

import io.github.syrou.reaktiv.core.util.selectState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Serializable
data class AnonymousCounterState(val count: Int = 0) : ModuleState

class Bump(module: KClass<*>) : ModuleAction(module)

@OptIn(ExperimentalCoroutinesApi::class)
class AnonymousModuleTest {

    @Test
    fun `a module declared as an anonymous object receives its actions`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val counter = object : Module<AnonymousCounterState, ModuleAction> {
                override val initialState = AnonymousCounterState()
                override val reducer: (AnonymousCounterState, ModuleAction) -> AnonymousCounterState = { state, _ ->
                    state.copy(count = state.count + 1)
                }
                override val createLogic: (StoreAccessor) -> ModuleLogic = { object : ModuleLogic() {} }
            }
            val store = createStore {
                module(counter)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            assertEquals(DispatchResult.Processed, store.dispatchAndAwait(Bump(counter::class)))
            assertEquals(1, store.selectState<AnonymousCounterState>().first().count)
            store.cleanup()
        }
}
