package io.github.syrou.reaktiv.navigation.model

import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen
import kotlinx.serialization.Serializable

@Serializable
data class NavigationEntry(
    val navigatable: Navigatable,
    val params: StringAnyMap,
    val graphId: String,
    val stackPosition: Int = 0
){
    companion object {
        private const val BASE_SCREEN_Z_INDEX = 0f
        private const val BASE_MODAL_Z_INDEX = 1000f
    }

    @Deprecated("Use navigatable instead", ReplaceWith("navigatable"))
    val screen: Navigatable get() = navigatable

    
    val zIndex: Float get() = when (navigatable) {
        is Modal -> BASE_MODAL_Z_INDEX + (stackPosition * 10f) // More spacing for modals
        is Screen -> BASE_SCREEN_Z_INDEX + (stackPosition * 1f) // Standard spacing for screens
        else -> BASE_SCREEN_Z_INDEX + (stackPosition * 1f)
    }

    val isModal: Boolean get() = navigatable is Modal
    val isScreen: Boolean get() = navigatable is Screen
}

data class RouteResolution(
    val targetNavigatable: Navigatable,
    val targetGraphId: String,
    val extractedParams: Map<String, Any>,
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

data class NavigationLayer(
    val entry: NavigationEntry,
    val zIndex: Float,
    val isVisible: Boolean,
    val shouldDim: Boolean = false,
    val dimAlpha: Float = 0f
)

fun Navigatable.toNavigationEntry(
    params: StringAnyMap = emptyMap(),
    graphId: String,
    stackPosition: Int = 0
): NavigationEntry = NavigationEntry(
    navigatable = this,
    params = params,
    graphId = graphId,
    stackPosition = stackPosition
)