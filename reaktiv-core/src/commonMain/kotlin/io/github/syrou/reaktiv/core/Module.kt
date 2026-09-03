package io.github.syrou.reaktiv.core

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

public interface Logic


/**
 * Base class for module logic implementations.
 *
 * Logic classes handle side effects, async operations, and complex business logic.
 * Define public suspend methods for operations that can be called from Composables
 * or other Logic classes.
 *
 * Example:
 * ```kotlin
 * class UserLogic(private val storeAccessor: StoreAccessor) : ModuleLogic() {
 *     private val api = UserApi()
 *
 *     suspend fun loadUser(userId: String) {
 *         storeAccessor.dispatch(UserAction.SetLoading(true))
 *         try {
 *             val user = api.fetchUser(userId)
 *             storeAccessor.dispatch(UserAction.SetUser(user))
 *         } catch (e: Exception) {
 *             storeAccessor.dispatch(UserAction.SetError(e.message))
 *         } finally {
 *             storeAccessor.dispatch(UserAction.SetLoading(false))
 *         }
 *     }
 *
 *     suspend fun logout() {
 *         api.logout()
 *         storeAccessor.dispatch(UserAction.ClearUser)
 *     }
 * }
 * ```
 */
public open class ModuleLogic : Logic {

    /**
     * Called on the **current** logic instance just before the store is reset.
     *
     * Override to clean up resources held by this logic instance, for example running
     * lifecycle handlers, releasing observers, or clearing caches, before the instance is
     * discarded and a new one is created by [Module.createLogic].
     *
     * This is called on the dispatch pipeline after every coroutine this instance launched
     * through the store has been cancelled and joined, and just before the state and logic swap,
     * so nothing else touches module state or lifecycle bookkeeping while it runs. Suspend calls
     * are safe here, but [StoreAccessor.dispatchAndAwait] throws because the pipeline would be
     * waiting for itself. An action passed to [StoreAccessor.dispatch] from here is applied to the
     * new generation after the swap, and a coroutine launched on the [StoreAccessor] runs in the
     * new generation.
     *
     * Example:
     * ```kotlin
     * class MyLogic(private val storeAccessor: StoreAccessor) : ModuleLogic() {
     *     private var observer: SomeObserver? = null
     *
     *     init {
     *         observer = SomeObserver()
     *     }
     *
     *     override suspend fun beforeReset() {
     *         observer?.release()
     *         observer = null
     *     }
     * }
     * ```
     */
    public open suspend fun beforeReset() {}

    /**
     * Called when the store enters or leaves external control.
     *
     * While a store is externally driven its state is authored by a remote publisher through
     * [StoreAction.Hydrate], and every locally dispatched action that
     * is not [ExternalControlExempt] is dropped. Override this to quiesce work that would
     * otherwise compete with the incoming projection, such as start-up resolution or
     * long-running observation.
     *
     * Invoked before the dispatch gate engages and after it disengages, so suspend calls and
     * dispatches made from this hook are still processed normally.
     *
     * @param externallyDriven `true` when entering external control, `false` when leaving it
     */
    public open suspend fun onExternalControlChanged(externallyDriven: Boolean) {}
}

/**
 * The recommended interface for defining modules with type-safe logic access.
 *
 * ModuleWithLogic extends Module with typed logic, allowing direct access to
 * logic methods without type casting. This is the preferred pattern for new modules.
 *
 * Example:
 * ```kotlin
 * @Serializable
 * data class CounterState(val count: Int = 0) : ModuleState
 *
 * sealed class CounterAction : ModuleAction(CounterModule::class) {
 *     data object Increment : CounterAction()
 *     data class SetCount(val value: Int) : CounterAction()
 * }
 *
 * class CounterLogic(private val storeAccessor: StoreAccessor) : ModuleLogic() {
 *     suspend fun incrementAsync() {
 *         delay(1000)
 *         storeAccessor.dispatch(CounterAction.Increment)
 *     }
 * }
 *
 * object CounterModule : ModuleWithLogic<CounterState, CounterAction, CounterLogic> {
 *     override val initialState = CounterState()
 *
 *     override val reducer: (CounterState, CounterAction) -> CounterState = { state, action ->
 *         when (action) {
 *             is CounterAction.Increment -> state.copy(count = state.count + 1)
 *             is CounterAction.SetCount -> state.copy(count = action.value)
 *         }
 *     }
 *
 *     override val createLogic: (StoreAccessor) -> CounterLogic = { CounterLogic(it) }
 * }
 * ```
 *
 * @param S The state type for this module (must implement ModuleState)
 * @param A The action type for this module (must extend ModuleAction)
 * @param L The logic type for this module (must extend ModuleLogic)
 */
public interface ModuleWithLogic<S : ModuleState, A : ModuleAction, L : ModuleLogic> : Module<S, A> {

    override val createLogic: (StoreAccessor) -> L

    /**
     * Select the typed logic instance from the store.
     *
     * @param store The store to select logic from
     * @return The typed logic instance
     */
    public suspend fun selectLogicTyped(store: StoreAccessor): L {
        @Suppress("UNCHECKED_CAST")
        return selectLogic(store) as L
    }
}

/**
 * Interface for defining modules in the MVLI architecture.
 *
 * A module owns a slice of application state, a reducer for transforming that state,
 * and a logic factory for creating the logic instance that handles side effects.
 *
 * For type-safe logic access without casting, prefer [ModuleWithLogic].
 */
public interface Module<S : ModuleState, A : ModuleAction> {

    public val initialState: S


    public val reducer: (S, A) -> S


    public val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic

    /**
     * Optional factory for creating a middleware provided by this module.
     *
     * When a module provides a middleware factory, the middleware will be created
     * and automatically registered with the Store during initialization. Module
     * middlewares are applied after explicitly registered middlewares (closer to
     * the reducer in the chain).
     *
     * The middleware can use `storeAccessor.selectLogic<T>()` to access Logic,
     * which will suspend until Logic is initialized.
     *
     * Example usage:
     * ```kotlin
     * class DevToolsModule(config: DevToolsConfig, scope: CoroutineScope)
     *     : ModuleWithLogic<DevToolsState, DevToolsAction, DevToolsLogic> {
     *
     *     override val createMiddleware: (() -> Middleware) = {
     *         DevToolsMiddleware(config, scope).middleware
     *     }
     * }
     * ```
     */
    public val createMiddleware: (() -> Middleware)?
        get() = null

    public fun selectStateFlowNonSuspend(store: StoreAccessor): StateFlow<S> {
        @Suppress("UNCHECKED_CAST")
        return store.getStateFlowForModule(this) as? StateFlow<S>
            ?: error("No state found for module $this")
    }

    public suspend fun selectLogic(store: StoreAccessor): ModuleLogic {
        return store.getLogicForModule(this)
            ?: error("No logic found for module $this")
    }
}

@OptIn(ExperimentalAtomicApi::class)
internal class ModuleInfo(
    val module: Module<*, *>,
    val state: MutableStateFlow<ModuleState>
) {
    val logic: AtomicReference<ModuleLogic?> = AtomicReference(null)
}
