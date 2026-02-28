package io.github.syrou.reaktiv.navigation.definition

import io.github.syrou.reaktiv.navigation.alias.ActionResource
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.transition.NavTransition

/**
 * A full-screen destination rendered in the [RenderLayer.CONTENT] layer.
 *
 * Implement this interface on an `object` to define a screen in your navigation graph.
 * Default transition values are `null` (no animation) for pop transitions and the navigatable
 * defaults for enter/exit; override them as needed.
 *
 * ```kotlin
 * object HomeScreen : Screen {
 *     override val route = "home"
 *     override val enterTransition = NavTransition.SlideInRight
 *     override val exitTransition = NavTransition.SlideOutLeft
 *
 *     @Composable
 *     override fun Content(params: Params) {
 *         Text("Home")
 *     }
 * }
 * ```
 *
 * @see NavigationGraph
 * @see Modal
 */
interface Screen : Navigatable {
    override val actionResource: ActionResource? get() = null
    override val titleResource: TitleResource? get() = null
    override val popEnterTransition: NavTransition? get() = null
    override val popExitTransition: NavTransition? get() = null

    override val renderLayer: RenderLayer
        get() = RenderLayer.CONTENT

    override val elevation: Float
        get() = 0f
}