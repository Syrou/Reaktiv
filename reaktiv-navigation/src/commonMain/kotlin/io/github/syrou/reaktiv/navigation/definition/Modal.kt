package io.github.syrou.reaktiv.navigation.definition

import io.github.syrou.reaktiv.core.StoreAccessor
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
     * Called when the user taps outside the modal content area. The intercepting layer
     * always captures all taps regardless of this value, preventing clicks from passing
     * through to screens behind the modal.
     *
     * Defaults to `null` — tapping outside does nothing. Override to provide custom
     * behaviour such as dismissing the modal or showing a confirmation prompt.
     * Programmatic dismissal via [navigateBack] or `navigation { }` always works
     * regardless of this value.
     *
     * Example:
     * ```kotlin
     * override val tapOutsideClick: (suspend StoreAccessor.() -> Unit) = { navigateBack() }
     * ```
     */
    val tapOutsideClick: (suspend StoreAccessor.() -> Unit)? get() = null

    val shouldDimBackground: Boolean get() = true
    val backgroundDimAlpha: Float get() = 0.5f

    override val renderLayer: RenderLayer
        get() = RenderLayer.GLOBAL_OVERLAY

    override val elevation: Float
        get() = 1000f
}