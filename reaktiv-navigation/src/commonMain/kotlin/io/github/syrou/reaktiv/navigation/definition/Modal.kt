package io.github.syrou.reaktiv.navigation.definition

import io.github.syrou.reaktiv.navigation.alias.ActionResource
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.transition.NavTransition

interface Modal : Navigatable {
    override val actionResource: ActionResource? get() = null
    override val titleResource: TitleResource? get() = null
    override val popEnterTransition: NavTransition? get() = NavTransition.None
    override val popExitTransition: NavTransition? get() = NavTransition.None

    /**
     * When true (the default), the system back gesture/button dismisses this modal via
     * [navigateBack]. Set to false for mandatory modals that must not be dismissed by the user.
     * Also gates [tapOutsideToDismiss] — if this is false, tapping outside does nothing even
     * when [tapOutsideToDismiss] is true.
     */
    val dismissable: Boolean get() = true

    /**
     * When true (the default), tapping on the dim background outside the modal content
     * calls [navigateBack]. The dim layer always captures all taps regardless of this flag,
     * preventing clicks from passing through to screens behind the modal.
     */
    val tapOutsideToDismiss: Boolean get() = true

    val shouldDimBackground: Boolean get() = true
    val backgroundDimAlpha: Float get() = 0.5f

    override val renderLayer: RenderLayer
        get() = RenderLayer.GLOBAL_OVERLAY

    override val elevation: Float
        get() = 1000f
}