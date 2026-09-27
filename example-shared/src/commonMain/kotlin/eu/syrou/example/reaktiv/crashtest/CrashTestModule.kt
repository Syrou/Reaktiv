package eu.syrou.example.reaktiv.crashtest

import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class CrashTestState(
    val placeholder: Boolean = false
) : ModuleState

@Serializable
sealed class CrashTestAction : ModuleAction(CrashTestModule::class)

/**
 * Logic class for triggering test crashes.
 *
 * The public methods launch coroutines via storeAccessor that deliberately
 * throw exceptions. The Store's CoroutineExceptionHandler catches these
 * and delegates to crash listeners (e.g., NavigationLogic's crash screen).
 */
class CrashTestLogic(
    private val storeAccessor: StoreAccessor
) : ModuleLogic() {

    fun triggerCrashWithTracedOperations() {
        storeAccessor.launch {
            executeCrashSequence()
        }
    }

    private suspend fun executeCrashSequence() {
        ReaktivDebug.general("CrashTestLogic: Starting crash sequence...")
        performPreCrashOperation("preparing crash test")
        causeDeliberateCrash()
    }

    private suspend fun performPreCrashOperation(message: String) {
        ReaktivDebug.general("CrashTestLogic: Pre-crash operation - $message")
        kotlinx.coroutines.delay(100)
    }

    private fun causeDeliberateCrash(): Nothing {
        ReaktivDebug.general("CrashTestLogic: About to crash!")
        throw RuntimeException("Deliberate test crash from CrashTestLogic")
    }

}

object CrashTestModule : Module<CrashTestState, CrashTestAction> {
    override val initialState = CrashTestState()

    override val reducer: (CrashTestState, CrashTestAction) -> CrashTestState = { state, _ ->
        state
    }

    override val createLogic: (StoreAccessor) -> CrashTestLogic = { storeAccessor ->
        CrashTestLogic(storeAccessor)
    }
}
