package io.github.syrou.reaktiv.navigation.definition

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.alias.ActionResource
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Reason why an entry is being removed from the backstack.
 *
 * Passed to [BackstackLifecycle.invokeOnRemoval] handlers so they can
 * distinguish between normal navigation removal and a full store reset.
 */
enum class RemovalReason {
    /** Entry was removed via normal navigation (back, popUpTo, replace, etc.) */
    NAVIGATION,

    /** Entry was removed because [io.github.syrou.reaktiv.core.StoreAccessor.reset] was called */
    RESET
}

/**
 * Lifecycle manager for a screen in the backstack.
 *
 * Provides access to visibility status, state selection, action dispatching,
 * and a coroutine scope tied to the entry's lifecycle. Coroutines launched
 * via this scope are automatically cancelled when the entry is removed
 * from the backstack.
 *
 * ## Lifecycle Flow
 *
 * **Normal Navigation:**
 * 1. Screen added to backstack → [Navigatable.onLifecycleCreated] called once
 * 2. Screen remains in backstack → No lifecycle events (observe [visibility] for changes)
 * 3. Screen removed from backstack → [invokeOnRemoval] handlers called once, then scope cancelled
 *
 * **With Store.reset():**
 * 1. `Store.reset()` called → [invokeOnRemoval] handlers for all existing entries
 * 2. Observation restarts → [Navigatable.onLifecycleCreated] called once for each backstack entry
 * 3. Fresh lifecycle instances created with clean state
 *
 * Each reset is idempotent - multiple resets follow the same pattern.
 *
 * ## Example Usage
 *
 * ```kotlin
 * override suspend fun onLifecycleCreated(lifecycle: BackstackLifecycle) {
 *     // Check current visibility
 *     if (lifecycle.visibility.value) {
 *         lifecycle.dispatch(MyAction.LoadData)
 *     }
 *
 *     // Observe visibility changes - auto-cancelled when entry is removed
 *     lifecycle.launch {
 *         lifecycle.visibility.collect { isVisible ->
 *             println("Visibility changed: $isVisible")
 *         }
 *     }
 *
 *     // Register cleanup when removed from backstack
 *     lifecycle.invokeOnRemoval {
 *         // `this` is StoreAccessor — non-suspend context
 *         // Must use launch for suspend work (fire-and-forget)
 *         launch {
 *             val logic = selectLogic<SomeLogic>()
 *             logic.cleanup()
 *             dispatch(SomeAction.Removed)
 *         }
 *     }
 * }
 * ```
 *
 * @property entry The navigation entry for this navigatable
 * @property visibility StateFlow indicating whether this entry is currently visible. Use .value for current state or collect for changes.
 */
class BackstackLifecycle(
    val entry: NavigationEntry,
    navigationStateFlow: StateFlow<NavigationState>,
    @PublishedApi internal val storeAccessor: StoreAccessor,
    lifecycleScope: CoroutineScope
) : CoroutineScope by lifecycleScope {

    val visibility: StateFlow<Boolean> = navigationStateFlow
        .map { state -> state.currentEntry.stableKey == entry.stableKey }
        .stateIn(
            scope = lifecycleScope,
            started = SharingStarted.Eagerly,
            initialValue = navigationStateFlow.value.currentEntry.stableKey == entry.stableKey
        )

    val params: Params get() = entry.params
    val route: String get() = entry.route

    fun dispatch(action: ModuleAction) = storeAccessor.dispatch(action)

    suspend inline fun <reified S : ModuleState> selectState(): StateFlow<S> = storeAccessor.selectState()

    suspend inline fun <reified L : ModuleLogic> selectLogic(): L = storeAccessor.selectLogic<L>()

    private val removalHandlers = mutableListOf<StoreAccessor.(RemovalReason) -> Unit>()

    /**
     * Register a callback to be invoked when this entry is removed from the backstack.
     *
     * ## When Handlers Are Called
     *
     * Removal handlers execute in two scenarios:
     * 1. **Normal removal** - When navigating back/away and the entry leaves the backstack
     * 2. **Store reset** - When `Store.reset()` is called (for all entries in backstack)
     *
     * Handlers run **before** the lifecycle scope is cancelled, so the store is still
     * fully operational. Multiple handlers can be registered and all will execute.
     *
     * ## Handler Execution
     *
     * - Handlers are non-suspend functions (synchronous)
     * - Use [StoreAccessor.launch] for suspend/async work (fire-and-forget)
     * - The [RemovalReason] parameter indicates why the entry is being removed
     * - Handlers receive [StoreAccessor] as receiver for dispatch, state access, etc.
     *
     * ## Example
     *
     * ```kotlin
     * lifecycle.invokeOnRemoval { reason ->
     *     // `this` is StoreAccessor, reason indicates NAVIGATION or RESET
     *     if (reason == RemovalReason.NAVIGATION) {
     *         launch {
     *             val logic = selectLogic<SomeLogic>()
     *             logic.cleanup()
     *         }
     *     }
     * }
     * ```
     *
     * @param handler Callback invoked with [StoreAccessor] as receiver and [RemovalReason] as parameter.
     */
    fun invokeOnRemoval(handler: StoreAccessor.(RemovalReason) -> Unit) {
        removalHandlers.add(handler)
    }

    /**
     * Executes all registered removal handlers with the [storeAccessor] as receiver.
     * Called internally by [NavigationLogic] before cancelling the lifecycle scope.
     *
     * @param reason Why the entry is being removed
     */
    internal fun runRemovalHandlers(reason: RemovalReason) {
        removalHandlers.forEach { handler ->
            handler(storeAccessor, reason)
        }
    }
}

interface Navigatable : NavigationNode {
    val titleResource: TitleResource?
    val actionResource: ActionResource?
    val enterTransition: NavTransition
    val exitTransition: NavTransition
    val popEnterTransition: NavTransition?
    val popExitTransition: NavTransition?
    val requiresAuth: Boolean

    /**
     * Which layer this navigatable should render in
     */
    val renderLayer: RenderLayer
        get() = RenderLayer.CONTENT

    /**
     * Elevation within the layer (higher = on top)
     */
    val elevation: Float
        get() = 0f

    /**
     * Called when this navigatable is added to the backstack.
     *
     * ## When This Is Called
     *
     * This method is invoked exactly **once** per lifecycle:
     * - When navigating to this screen and it's added to the backstack
     * - After `Store.reset()` for entries that remain in the backstack (new lifecycle instance)
     *
     * ## What You Can Do Here
     *
     * - Dispatch initial actions (e.g., load data)
     * - Launch coroutines (automatically cancelled when entry is removed)
     * - Register cleanup handlers via [BackstackLifecycle.invokeOnRemoval]
     * - Observe state changes using [BackstackLifecycle.selectState]
     * - Check/observe visibility via [BackstackLifecycle.visibility]
     *
     * ## Cleanup Options
     *
     * 1. **Recommended**: Use [BackstackLifecycle.invokeOnRemoval] for cleanup logic
     * 2. Alternative: Use try/finally in launched coroutines (runs when scope is cancelled)
     *
     * ## Example
     *
     * ```kotlin
     * object ProfileScreen : Screen {
     *     override suspend fun onLifecycleCreated(lifecycle: BackstackLifecycle) {
     *         // Dispatch initial action if visible
     *         if (lifecycle.visibility.value) {
     *             lifecycle.dispatch(ProfileAction.LoadProfile)
     *         }
     *
     *         // Launch coroutine (auto-cancelled on removal)
     *         lifecycle.launch {
     *             lifecycle.selectState<UserState>().collect { state ->
     *                 if (!lifecycle.visibility.value) return@collect
     *                 lifecycle.dispatch(ProfileAction.Update(state))
     *             }
     *         }
     *
     *         // Register cleanup handler
     *         lifecycle.invokeOnRemoval {
     *             launch {
     *                 val logic = selectLogic<ProfileLogic>()
     *                 logic.cleanup()
     *             }
     *         }
     *     }
     * }
     * ```
     *
     * @param lifecycle Provides entry info, visibility, dispatch, selectState, and coroutine scope
     */
    suspend fun onLifecycleCreated(lifecycle: BackstackLifecycle) {}

    @Composable
    fun Content(params: Params)
}
