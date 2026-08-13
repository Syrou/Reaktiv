package io.github.syrou.reaktiv.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.reflect.KClass

/**
 * Marker interface for module state classes.
 *
 * States represent the data of your application at a given point in time.
 * They must be immutable and should be data classes marked with @Serializable.
 *
 * Example:
 * ```kotlin
 * @Serializable
 * data class CounterState(
 *     val count: Int = 0,
 *     val isLoading: Boolean = false,
 *     val error: String? = null
 * ) : ModuleState
 * ```
 */
public interface ModuleState


/**
 * Marker interface for high-priority actions.
 *
 * Actions implementing this interface bypass the normal queue and are processed
 * immediately. Use this for time-sensitive operations like cancellations or
 * emergency stops.
 *
 * Example:
 * ```kotlin
 * sealed class UrgentAction : ModuleAction(UrgentModule::class), HighPriorityAction {
 *     data object CancelOperation : UrgentAction()
 *     data object EmergencyStop : UrgentAction()
 * }
 * ```
 */
public interface HighPriorityAction


/**
 * Base class for module actions.
 *
 * Actions are events that describe changes in your application. They are dispatched
 * to the store to trigger state updates via reducers.
 *
 * Example:
 * ```kotlin
 * sealed class CounterAction : ModuleAction(CounterModule::class) {
 *     data object Increment : CounterAction()
 *     data object Decrement : CounterAction()
 *     data class SetCount(val value: Int) : CounterAction()
 * }
 * ```
 *
 * @param moduleTag The KClass of the module that handles this action
 */
@Serializable
public abstract class ModuleAction(@Transient internal val moduleTag: KClass<*> = KClass::class)

public interface ExternalControlExempt


public typealias Dispatch = (ModuleAction) -> Unit

/**
 * Result of a dispatch operation, indicating whether the action was processed.
 */
public sealed class DispatchResult {
    /** Action was processed and applied to state */
    public data object Processed : DispatchResult()

    /** Action was blocked by middleware (e.g., spam protection) */
    public data object Blocked : DispatchResult()

    /** Action processing failed with an error */
    public data class Error(val cause: Throwable) : DispatchResult()
}

/**
 * Internal envelope wrapping an action with an optional completion signal.
 * Used to track when async dispatch processing completes.
 */
internal data class DispatchEnvelope(
    val action: ModuleAction,
    val completion: CompletableDeferred<DispatchResult>?,
    val enqueuedAtMs: Long = 0L
)
