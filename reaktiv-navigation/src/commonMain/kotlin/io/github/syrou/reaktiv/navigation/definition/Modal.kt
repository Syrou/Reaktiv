package io.github.syrou.reaktiv.navigation.definition

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.alias.ActionResource
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.transition.NavTransition

interface Modal : Navigatable {
    override val actionResource: ActionResource? get() = null
    override val titleResource: TitleResource? get() = null
    override val popEnterTransition: NavTransition? get() = NavTransition.None
    override val popExitTransition: NavTransition? get() = NavTransition.None

    val onDismissTapOutside: (suspend StoreAccessor.() -> Unit)? get() = null
    
    val shouldDimBackground: Boolean get() = true

    
    val backgroundDimAlpha: Float get() = 0.5f
}