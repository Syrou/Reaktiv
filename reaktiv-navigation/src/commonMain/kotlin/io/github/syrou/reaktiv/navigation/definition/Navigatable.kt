package io.github.syrou.reaktiv.navigation.definition

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.navigation.alias.ActionResource
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
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

    @Composable
    fun Content(params: Map<String, Any>)
}
