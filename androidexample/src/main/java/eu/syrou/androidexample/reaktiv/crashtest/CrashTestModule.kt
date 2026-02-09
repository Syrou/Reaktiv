package eu.syrou.androidexample.reaktiv.crashtest

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
) : ModuleLogic<CrashTestAction>() {

    fun triggerCrashWithTracedOperations() {
        storeAccessor.launch {
            executeCrashSequence()
        }
    }

    fun triggerNestedExceptionCrash() {
        storeAccessor.launch {
            executeNestedCrashSequence()
        }
    }

    private suspend fun executeCrashSequence() {
        println("CrashTestLogic: Starting crash sequence...")
        performPreCrashOperation("preparing crash test")
        causeDeliberateCrash()
    }

    private suspend fun performPreCrashOperation(message: String) {
        println("CrashTestLogic: Pre-crash operation - $message")
        kotlinx.coroutines.delay(100)
    }

    private fun causeDeliberateCrash(): Nothing {
        println("CrashTestLogic: About to crash!")
        throw RuntimeException("Deliberate test crash from CrashTestLogic")
    }

    private suspend fun executeNestedCrashSequence() {
        println("CrashTestLogic: Starting nested exception crash sequence...")
        performPreCrashOperation("preparing nested crash")
        try {
            causeInnerException()
        } catch (e: Exception) {
            throw RuntimeException("Outer exception from CrashTestLogic", e)
        }
    }

    private fun causeInnerException(): Nothing {
        throw IllegalStateException("Inner exception - this is the root cause")
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
