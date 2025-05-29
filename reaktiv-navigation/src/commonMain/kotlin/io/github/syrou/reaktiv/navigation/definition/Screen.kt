package io.github.syrou.reaktiv.navigation.definition

import io.github.syrou.reaktiv.navigation.alias.ActionResource
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.transition.NavTransition

interface Screen : Navigatable {
    override val actionResource: ActionResource? get() = null
    override val titleResource: TitleResource? get() = null
    override val popEnterTransition: NavTransition? get() = null
    override val popExitTransition: NavTransition? get() = null
}