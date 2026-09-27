package eu.syrou.example.reaktiv.TestNavigationModule

import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import kotlinx.serialization.Serializable

sealed class TestNavigationAction : ModuleAction(TestNavigationModule::class) {
    data object TriggerMultipleNavigation : TestNavigationAction()
}

// Simple test state (not really used, but required for module)
@Serializable
data class TestNavigationState(val operationCount: Int = 0) : ModuleState

// Test module to hold the action
object TestNavigationModule : Module<TestNavigationState, TestNavigationAction> {
    override val initialState = TestNavigationState()

    override val reducer: (TestNavigationState, TestNavigationAction) -> TestNavigationState = { state, action ->
        when (action) {
            is TestNavigationAction.TriggerMultipleNavigation -> {
                state.copy(operationCount = state.operationCount + 1)
            }
        }
    }

    override val createLogic: (StoreAccessor) -> ModuleLogic =
        { object : ModuleLogic() {} }
}