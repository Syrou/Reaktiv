package io.github.syrou.reaktiv.navigation.model

import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.definition.Screen
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

/**
 * Represents a single step in a guided flow.
 * Supports both route strings (with query parameters) and typed Screen classes with parameters.
 */
@Serializable
sealed class GuidedFlowStep {
    
    /**
     * Navigate to a route string, optionally with query parameters.
     * Examples:
     * - "user/profile" 
     * - "user/profile?userId=123&tab=settings"
     * - "onboarding/welcome"
     */
    @Serializable
    data class Route(
        val route: String,
        val params: StringAnyMap = emptyMap()
    ) : GuidedFlowStep()
    
    /**
     * Navigate to a typed Screen class with parameters.
     * Similar to navigateTo<T>(), this uses the Screen class to resolve the route.
     */
    @Serializable  
    data class TypedScreen(
        val screenClass: String, // Store class name as string for serialization
        val params: StringAnyMap = emptyMap()
    ) : GuidedFlowStep()
}

/**
 * Extension functions to create GuidedFlowStep instances
 */

/**
 * Create a step from a route string
 */
fun String.toGuidedFlowStep(params: StringAnyMap = emptyMap()): GuidedFlowStep.Route {
    return GuidedFlowStep.Route(this, params)
}

/**
 * Create a step from a Screen class with parameters, similar to navigateTo<T>()
 */
inline fun <reified T : Screen> guidedFlowStep(params: StringAnyMap = emptyMap()): GuidedFlowStep.TypedScreen {
    return GuidedFlowStep.TypedScreen(T::class.qualifiedName!!, params)
}

/**
 * Get the route string for navigation, regardless of step type
 * For TypedScreen steps, this resolves the route from the precomputed navigation data
 */
suspend fun GuidedFlowStep.getRoute(precomputedData: io.github.syrou.reaktiv.navigation.PrecomputedNavigationData): String {
    return when (this) {
        is GuidedFlowStep.Route -> route
        is GuidedFlowStep.TypedScreen -> {
            // Find the screen instance by class name and get its route
            val screenInstance = precomputedData.availableNavigatables.values
                .filterIsInstance<Screen>()
                .find { it::class.qualifiedName == screenClass }
            screenInstance?.route ?: throw IllegalStateException("Screen class $screenClass not found in navigation graph")
        }
    }
}

/**
 * Get the combined parameters for this step
 */
fun GuidedFlowStep.getParams(): StringAnyMap {
    return when (this) {
        is GuidedFlowStep.Route -> params
        is GuidedFlowStep.TypedScreen -> params
    }
}