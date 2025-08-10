package eu.syrou.androidexample.reaktiv.TestNavigationModule

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleState
import kotlinx.serialization.Serializable

sealed class TestNavigationAction : ModuleAction(TestNavigationModule::class) {
    data object TriggerMultipleNavigation : TestNavigationAction()
    data object TriggerSequentialNavigation : TestNavigationAction()
    data object TriggerDelayedNavigation : TestNavigationAction()
    data class TriggerCustomNavigation(val screens: List<String>) : TestNavigationAction()
}

// Simple test state (not really used, but required for module)
@Serializable
data class TestNavigationState(val operationCount: Int = 0) : ModuleState

// Test module to hold the action
object TestNavigationModule : io.github.syrou.reaktiv.core.Module<TestNavigationState, TestNavigationAction> {
    override val initialState = TestNavigationState()
    
    override val reducer: (TestNavigationState, TestNavigationAction) -> TestNavigationState = { state, action ->
        when (action) {
            is TestNavigationAction.TriggerMultipleNavigation,
            is TestNavigationAction.TriggerSequentialNavigation,
            is TestNavigationAction.TriggerDelayedNavigation,
            is TestNavigationAction.TriggerCustomNavigation -> {
                state.copy(operationCount = state.operationCount + 1)
            }
        }
    }
    
    override val createLogic: (io.github.syrou.reaktiv.core.StoreAccessor) -> io.github.syrou.reaktiv.core.ModuleLogic<TestNavigationAction> = { 
        io.github.syrou.reaktiv.core.ModuleLogic { }
    }
}