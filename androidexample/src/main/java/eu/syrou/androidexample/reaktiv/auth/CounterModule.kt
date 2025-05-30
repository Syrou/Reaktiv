package eu.syrou.androidexample.reaktiv.auth

import io.github.syrou.reaktiv.core.Dispatch
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import kotlinx.serialization.Serializable

object CounterModule : Module<CounterModule.CounterState, CounterModule.CounterAction> {
    @Serializable
    data class CounterState(val count: Int = 0) : ModuleState

    sealed class CounterAction : ModuleAction(CounterModule::class) {
        data object Increment : CounterAction()
        data object Decrement : CounterAction()
    }

    override val initialState = CounterState()
    override val reducer: (CounterState, CounterAction) -> CounterState = { state, action ->
        when (action) {
            is CounterAction.Increment -> state.copy(count = state.count + 1)
            is CounterAction.Decrement -> state.copy(count = state.count - 1)
        }
    }
    override val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic<CounterAction> = { storeAccessor ->
        println("HERPA DERPA - Assigning and creating logic for: ${this::class.qualifiedName}")
        ModuleLogic.invoke { moduleAction ->

        }
    }

}