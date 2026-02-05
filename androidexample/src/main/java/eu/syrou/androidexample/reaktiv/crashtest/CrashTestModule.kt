package eu.syrou.androidexample.reaktiv.crashtest

import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * State for the CrashTest module.
 */
@Serializable
data class CrashTestState(
    val crashCount: Int = 0,
    val lastCrashAttempt: Long? = null
) : ModuleState

/**
 * Actions for the CrashTest module.
 */
@Serializable
sealed class CrashTestAction : ModuleAction(CrashTestModule::class) {
    @Serializable
    data class RecordCrashAttempt(val timestamp: Long) : CrashTestAction()
}

/**
 * Logic class for triggering test crashes.
 *
 * This logic class provides methods that can be traced by the tracer plugin,
 * allowing us to verify that logic method events are captured before a crash.
 *
 * The public methods are non-suspending and launch coroutines internally,
 * making them easy to call from UI code.
 */
class CrashTestLogic(
    private val storeAccessor: StoreAccessor
) : ModuleLogic<CrashTestAction>() {

    /**
     * Triggers a crash with traced operations.
     * This is a non-suspend method that launches the crash sequence via storeAccessor.
     *
     * Call this from UI to test crash capture with tracer events.
     */
    fun triggerCrashWithTracedOperations() {
        storeAccessor.launch {
            executeCrashSequence()
        }
    }

    /**
     * Triggers a crash with nested exceptions.
     * This is a non-suspend method that launches the crash sequence via storeAccessor.
     */
    fun triggerNestedExceptionCrash() {
        storeAccessor.launch {
            executeNestedCrashSequence()
        }
    }

    /**
     * Internal suspend function that performs traced operations before crashing.
     * This method will be traced if the tracer plugin is enabled.
     */
    private suspend fun executeCrashSequence() {
        println("CrashTestLogic: Starting crash sequence...")

        // Record the crash attempt
        storeAccessor.dispatch(CrashTestAction.RecordCrashAttempt(System.currentTimeMillis()))

        // Perform some operations that will be traced
        performPreCrashOperation("preparing crash test")

        // This will throw an exception
        causeDeliberateCrash()
    }

    /**
     * A traced operation performed before the crash.
     */
    private suspend fun performPreCrashOperation(message: String) {
        println("CrashTestLogic: Pre-crash operation - $message")
        // Simulate some work
        kotlinx.coroutines.delay(100)
    }

    /**
     * Method that deliberately causes a crash.
     */
    private fun causeDeliberateCrash(): Nothing {
        println("CrashTestLogic: About to crash!")
        throw RuntimeException("Deliberate test crash from CrashTestLogic.triggerCrashWithTracedOperations()")
    }

    /**
     * Internal suspend function for nested exception crash.
     */
    private suspend fun executeNestedCrashSequence() {
        println("CrashTestLogic: Starting nested exception crash sequence...")

        storeAccessor.dispatch(CrashTestAction.RecordCrashAttempt(System.currentTimeMillis()))

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

/**
 * Module for testing crash capture functionality.
 *
 * This module provides a Logic class with methods that can be traced,
 * allowing verification that the tracer plugin's events are properly
 * captured in crash reports.
 *
 * Usage:
 * ```kotlin
 * // In your store setup
 * module(CrashTestModule)
 *
 * // To trigger a crash (from a coroutine)
 * val crashLogic = store.selectLogic<CrashTestLogic>()
 * crashLogic.triggerCrashWithTracedOperations()
 * ```
 */
object CrashTestModule : Module<CrashTestState, CrashTestAction> {
    override val initialState = CrashTestState()

    override val reducer: (CrashTestState, CrashTestAction) -> CrashTestState = { state, action ->
        when (action) {
            is CrashTestAction.RecordCrashAttempt -> state.copy(
                crashCount = state.crashCount + 1,
                lastCrashAttempt = action.timestamp
            )
        }
    }

    override val createLogic: (StoreAccessor) -> CrashTestLogic = { storeAccessor ->
        CrashTestLogic(storeAccessor)
    }
}
