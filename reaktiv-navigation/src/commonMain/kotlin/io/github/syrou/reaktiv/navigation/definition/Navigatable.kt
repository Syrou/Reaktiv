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
 * Lifecycle manager for a screen in the backstack.
 *
 * Provides access to visibility status, state selection, action dispatching,
 * and a coroutine scope tied to the entry's lifecycle. Coroutines launched
 * via this scope are automatically cancelled when the entry is removed
 * from the backstack.
 *
 * Example:
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
    val route: String get() = entry.navigatable.route

    fun dispatch(action: ModuleAction) = storeAccessor.dispatch(action)

    suspend inline fun <reified S : ModuleState> selectState(): StateFlow<S> = storeAccessor.selectState()

    suspend inline fun <reified L : ModuleLogic<out ModuleAction>> selectLogic(): L = storeAccessor.selectLogic<L>()

    private val removalHandlers = mutableListOf<StoreAccessor.() -> Unit>()

    /**
     * Register a callback to be invoked when this entry is removed from the backstack.
     * The handler receives [StoreAccessor] as its receiver, providing access to
     * [StoreAccessor.dispatch], [StoreAccessor.launch], and other store operations.
     *
     * Handlers run **before** the lifecycle scope is cancelled, so the store is still
     * fully operational. Since the handler is non-suspend, use [StoreAccessor.launch]
     * for any async/suspend work (fire-and-forget).
     *
     * Example:
     * ```kotlin
     * lifecycle.invokeOnRemoval {
     *     // `this` is StoreAccessor
     *     launch {
     *         val logic = selectLogic<SomeLogic>()
     *         logic.cleanup()
     *     }
     * }
     * ```
     *
     * @param handler Callback invoked with [StoreAccessor] as receiver when the entry is removed.
     */
    fun invokeOnRemoval(handler: StoreAccessor.() -> Unit) {
        removalHandlers.add(handler)
    }

    /**
     * Executes all registered removal handlers with the [storeAccessor] as receiver.
     * Called internally by [NavigationLogic] before cancelling the lifecycle scope.
     */
    internal fun runRemovalHandlers() {
        removalHandlers.forEach { handler ->
            try {
                handler(storeAccessor)
            } catch (e: Exception) {
                // Swallow exceptions from individual handlers to ensure all handlers run
            }
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
     * Use this to trigger side effects like loading data or dispatching actions.
     * The lifecycle provides visibility status, state selection, action dispatching,
     * and a coroutine scope that is automatically cancelled when the entry is removed.
     *
     * For cleanup when removed, use [BackstackLifecycle.invokeOnRemoval] or a try/finally
     * block in a launched coroutine.
     *
     * Example:
     * ```kotlin
     * object ProfileScreen : Screen {
     *     override suspend fun onLifecycleCreated(lifecycle: BackstackLifecycle) {
     *         if (lifecycle.visibility.value) {
     *             lifecycle.dispatch(ProfileAction.LoadProfile)
     *         }
     *
     *         lifecycle.launch {
     *             lifecycle.selectState<UserState>().collect { state ->
     *                 if (!lifecycle.visibility.value) return@collect
     *                 lifecycle.dispatch(ProfileAction.Update(state))
     *             }
     *         }
     *
     *         lifecycle.invokeOnRemoval {
     *             // `this` is StoreAccessor — runs before lifecycle scope is cancelled
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
