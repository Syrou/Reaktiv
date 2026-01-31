package io.github.syrou.reaktiv.navigation.definition

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.alias.ActionResource
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition

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
     *
     * Example:
     * ```kotlin
     * object ProfileScreen : Screen {
     *     override suspend fun onAddedToBackstack(storeAccessor: StoreAccessor) {
     *         storeAccessor.dispatch(ProfileAction.LoadProfile)
     *     }
     * }
     * ```
     *
     * @param storeAccessor Access to the store for dispatching actions or reading state
     */
    suspend fun onAddedToBackstack(storeAccessor: StoreAccessor) {}

    /**
     * Called when this navigatable is removed from the backstack.
     *
     * Use this for cleanup, saving state, or analytics.
     * Only called when actually removed, not when covered by another screen.
     *
     * Example:
     * ```kotlin
     * object ProfileScreen : Screen {
     *     override suspend fun onRemovedFromBackstack(storeAccessor: StoreAccessor) {
     *         storeAccessor.dispatch(ProfileAction.ClearProfile)
     *     }
     * }
     * ```
     *
     * @param storeAccessor Access to the store for dispatching actions or reading state
     */
    suspend fun onRemovedFromBackstack(storeAccessor: StoreAccessor) {}

    @Composable
    fun Content(params: Params)
}
