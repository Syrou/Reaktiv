package io.github.syrou.reaktiv.navigation.model

import androidx.compose.runtime.Stable
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.param.Params
import kotlinx.serialization.Serializable

@Stable
@Serializable
data class NavigationEntry(
    val path: String,
    val params: Params,
    val stackPosition: Int = 0,
    val navigatableRoute: String = path.substringAfterLast("/")
) {
    val route: String get() = navigatableRoute

    val stableKey: String get() = "${path}_${params.hashCode()}"

    val graphId: String get() {
        val prefix = path.removeSuffix("/$navigatableRoute")
        return if (prefix == path || prefix.isEmpty()) "root"
        else prefix.substringAfterLast("/")
    }
}

data class RouteResolution(
    val targetNavigatable: Navigatable,
    val targetGraphId: String,
    val extractedParams: Params,
    val navigationGraphId: String? = null,
    val isGraphReference: Boolean = false
) {
    @Deprecated("Use targetNavigatable instead", ReplaceWith("targetNavigatable"))
    val targetScreen: Navigatable get() = targetNavigatable

    fun getEffectiveGraphId(): String {
        return when {
            isGraphReference -> targetGraphId
            navigationGraphId != null -> navigationGraphId
            else -> targetGraphId
        }
    }
}

data class ScreenResolution(
    val navigatable: Navigatable,
    val actualGraphId: String
) {
    @Deprecated("Use navigatable instead", ReplaceWith("navigatable"))
    val screen: Navigatable get() = navigatable
}

fun Navigatable.toNavigationEntry(
    path: String,
    params: Params = Params.empty(),
    stackPosition: Int = 0
): NavigationEntry = NavigationEntry(
    path = path,
    params = params,
    stackPosition = stackPosition,
    navigatableRoute = this.route
)
