package io.github.syrou.reaktiv.navigation.definition

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition

internal object ColdStartPlaceholder : LoadingModal {
    override val route: String = "reaktiv-cold-start"
    override val enterTransition: NavTransition = NavTransition.None
    override val exitTransition: NavTransition = NavTransition.None

    @Composable
    override fun Content(params: Params) {
    }
}
