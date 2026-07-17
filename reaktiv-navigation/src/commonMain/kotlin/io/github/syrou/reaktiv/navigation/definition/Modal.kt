package io.github.syrou.reaktiv.navigation.definition

import io.github.syrou.reaktiv.navigation.alias.ActionResource
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.transition.NavTransition

public interface Modal : Navigatable {
    override val actionResource: ActionResource? get() = null
    override val titleResource: TitleResource? get() = null
    override val popEnterTransition: NavTransition? get() = null
    override val popExitTransition: NavTransition? get() = null

    override val swipeToDismiss: Boolean get() = true

    public val shouldDimBackground: Boolean get() = true
    public val backgroundDimAlpha: Float get() = 0.5f

    override val renderLayer: RenderLayer
        get() = RenderLayer.GLOBAL_OVERLAY

    override val elevation: Float
        get() = 1000f
}
