package io.github.syrou.reaktiv.navigation.dsl

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationTarget
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.encoding.DualNavigationParameterEncoder
import io.github.syrou.reaktiv.navigation.exception.RouteNotFoundException
import io.github.syrou.reaktiv.navigation.param.SerializableParam
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.util.CommonUrlEncoder
import io.github.syrou.reaktiv.navigation.util.parseUrlWithQueryParams
import io.github.syrou.reaktiv.navigation.model.GuidedFlowContext
import io.github.syrou.reaktiv.navigation.model.GuidedFlowState
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

@Serializable
enum class NavigationOperation {
    Navigate,
    Replace,
    Back,
    PopUpTo,
    ClearBackStack
}

@Serializable
data class NavigationStep(
    val operation: NavigationOperation,
    val target: NavigationTarget? = null,
    val params: Params = Params.empty(),
    val popUpToTarget: NavigationTarget? = null,
    val popUpToInclusive: Boolean = false,
    val shouldClearBackStack: Boolean = false,
    val shouldReplaceWith: Boolean = false,
    val shouldDismissModals: Boolean = false
)

class NavigationParameterBuilder {
    @PublishedApi
    internal val params = mutableMapOf<String, Any>()

    inline fun <reified T> put(key: String, value: T): NavigationParameterBuilder {
        params[key] = SerializableParam(value, serializer<T>())
        return this
    }

    fun <T> put(key: String, value: T, serializer: KSerializer<T>): NavigationParameterBuilder {
        params[key] = SerializableParam(value, serializer)
        return this
    }

    fun putRaw(key: String, value: Any): NavigationParameterBuilder {
        params[key] = value
        return this
    }

    // Convenience methods for common types  
    fun putString(key: String, value: String) = putRaw(key, value)
    fun putInt(key: String, value: Int) = putRaw(key, value)
    fun putBoolean(key: String, value: Boolean) = putRaw(key, value)
    fun putDouble(key: String, value: Double) = putRaw(key, value)
    fun putLong(key: String, value: Long) = putRaw(key, value)
    fun putFloat(key: String, value: Float) = putRaw(key, value)

    // Shorter aliases
    fun param(key: String, value: Any) = putRaw(key, value)
}



/**
 * Builder for creating atomic navigation operations.
 * 
 * Provides a DSL for chaining multiple navigation actions into a single atomic operation.
 * All operations are validated and executed together to ensure consistent navigation state.
 * 
 * Example usage:
 * ```kotlin
 * store.navigation {
 *     clearBackStack()
 *     navigateTo("home")
 *     navigateTo<ProfileScreen> { param("userId", "123") }
 * }
 * ```
 * 
 * @param storeAccessor Accessor for the store to execute operations
 * @param encoder Parameter encoder for serialization
 */
class NavigationBuilder(
    private val storeAccessor: StoreAccessor,
    private val encoder: DualNavigationParameterEncoder = DualNavigationParameterEncoder()
) {
    @PublishedApi
    internal val operations = mutableListOf<NavigationStep>()
    
    @PublishedApi
    internal val guidedFlowOperations = mutableMapOf<String, GuidedFlowOperationBuilder>()
    
    @PublishedApi
    internal var currentParams = Params.empty()
    
    @PublishedApi
    internal var shouldDismissModalsGlobally = false
    
    @PublishedApi
    internal var guidedFlowContext: GuidedFlowContext? = null
    
    @PublishedApi
    internal var activeGuidedFlowState: GuidedFlowState? = null
    
    @PublishedApi
    internal var shouldClearActiveGuidedFlowState: Boolean = false


    fun navigateTo(path: String, replaceCurrent: Boolean = false, paramBuilder: (NavigationParameterBuilder.() -> Unit)? = null): NavigationBuilder {
        val (cleanPath, queryParams) = parseUrlWithQueryParams(path)
        var stepParams = Params.fromMap(queryParams)
        
        // Apply parameter builder if provided
        paramBuilder?.let { builder ->
            val parameterBuilder = NavigationParameterBuilder()
            builder(parameterBuilder)
            stepParams += Params.fromMap(parameterBuilder.params)
        }
        
        // Add current accumulated params
        stepParams += currentParams
        currentParams = Params.empty()
        
        val step = NavigationStep(
            operation = if (replaceCurrent) NavigationOperation.Replace else NavigationOperation.Navigate,
            target = NavigationTarget.Path(cleanPath),
            params = stepParams,
            shouldReplaceWith = replaceCurrent,
            shouldDismissModals = shouldDismissModalsGlobally
        )
        operations.add(step)

        return this
    }

    fun navigateBack(): NavigationBuilder {
        val step = NavigationStep(
            operation = NavigationOperation.Back,
            shouldDismissModals = shouldDismissModalsGlobally
        )
        operations.add(step)
        
        return this
    }

    suspend inline fun <reified T : Screen> navigateTo(replaceCurrent: Boolean = false): NavigationBuilder {
        return navigateTo<T>(replaceCurrent = replaceCurrent, preferredGraphId = null)
    }

    suspend inline fun <reified T : Navigatable> navigateTo(
        replaceCurrent: Boolean = false,
        preferredGraphId: String? = null,
        noinline paramBuilder: (NavigationParameterBuilder.() -> Unit)? = null
    ): NavigationBuilder {
        val navigatable = findNavigatableByType<T>()
        val target = if (preferredGraphId != null) {
            NavigationTarget.NavigatableObjectWithGraph(navigatable, preferredGraphId)
        } else {
            NavigationTarget.NavigatableObject(navigatable)
        }
        
        var stepParams = Params.empty()
        
        // Apply parameter builder if provided
        paramBuilder?.let { builder ->
            val parameterBuilder = NavigationParameterBuilder()
            builder(parameterBuilder)
            stepParams += Params.fromMap(parameterBuilder.params)
        }
        
        // Add current accumulated params
        stepParams += currentParams
        currentParams = Params.empty()
        
        val step = NavigationStep(
            operation = if (replaceCurrent) NavigationOperation.Replace else NavigationOperation.Navigate,
            target = target,
            params = stepParams,
            shouldReplaceWith = replaceCurrent,
            shouldDismissModals = shouldDismissModalsGlobally
        )
        operations.add(step)
        
        return this
    }



    fun popUpTo(path: String, inclusive: Boolean = false): NavigationBuilder {
        val (cleanPath, _) = parseUrlWithQueryParams(path)
        
        val step = NavigationStep(
            operation = NavigationOperation.PopUpTo,
            popUpToTarget = NavigationTarget.Path(cleanPath),
            popUpToInclusive = inclusive,
            shouldDismissModals = shouldDismissModalsGlobally
        )
        operations.add(step)
        
        return this
    }

    suspend inline fun <reified T : Navigatable> popUpTo(
        inclusive: Boolean = false,
        preferredGraphId: String? = null
    ): NavigationBuilder {
        val navigatable = findNavigatableByType<T>()
        val target = if (preferredGraphId != null) {
            NavigationTarget.NavigatableObjectWithGraph(navigatable, preferredGraphId)
        } else {
            NavigationTarget.NavigatableObject(navigatable)
        }
        
        val step = NavigationStep(
            operation = NavigationOperation.PopUpTo,
            popUpToTarget = target,
            popUpToInclusive = inclusive,
            shouldDismissModals = shouldDismissModalsGlobally
        )
        operations.add(step)
        
        return this
    }

    fun clearBackStack(): NavigationBuilder {
        val step = NavigationStep(
            operation = NavigationOperation.ClearBackStack,
            shouldClearBackStack = true,
            shouldDismissModals = shouldDismissModalsGlobally
        )
        operations.add(step)
        
        return this
    }


    /**
     * Dismiss any active modals as part of all navigation operations in this block
     */
    fun dismissModals(): NavigationBuilder {
        shouldDismissModalsGlobally = true
        // Apply to all existing operations as well
        for (i in operations.indices) {
            operations[i] = operations[i].copy(shouldDismissModals = true)
        }
        return this
    }

    fun params(params: Params): NavigationBuilder {
        currentParams += params
        return this
    }

    // Complex types using Params
    inline fun <reified T : Any> put(key: String, value: T): NavigationBuilder {
        currentParams = currentParams.withTyped(key, value)
        return this
    }

    // Keep existing parameter methods for backward compatibility
    fun putRaw(key: String, value: Any): NavigationBuilder {
        currentParams = when (value) {
            is String -> currentParams.with(key, value)
            is Int -> currentParams.with(key, value)
            is Boolean -> currentParams.with(key, value)
            is Double -> currentParams.with(key, value)
            is Long -> currentParams.with(key, value)
            is Float -> currentParams.with(key, value)
            else -> currentParams.withTyped(key, value)
        }
        return this
    }

    // Convenience methods for common types
    fun putString(key: String, value: String): NavigationBuilder {
        currentParams = currentParams.with(key, value)
        return this
    }
    fun putInt(key: String, value: Int): NavigationBuilder {
        currentParams = currentParams.with(key, value)
        return this
    }
    fun putBoolean(key: String, value: Boolean): NavigationBuilder {
        currentParams = currentParams.with(key, value)
        return this
    }
    fun putDouble(key: String, value: Double): NavigationBuilder {
        currentParams = currentParams.with(key, value)
        return this
    }
    fun putLong(key: String, value: Long): NavigationBuilder {
        currentParams = currentParams.with(key, value)
        return this
    }
    fun putFloat(key: String, value: Float): NavigationBuilder {
        currentParams = currentParams.with(key, value)
        return this
    }

    // Shorter aliases
    fun param(key: String, value: Any) = putRaw(key, value)
    
    /**
     * Execute guided flow operations atomically with navigation operations.
     * All guided flow modifications and steps will be executed as part of the same BatchUpdate.
     * 
     * Example:
     * ```kotlin
     * navigation {
     *     guidedFlow("signup") {
     *         removeSteps(listOf(2, 3))
     *         nextStep()
     *     }
     *     guidedFlow("onboarding") { 
     *         updateStepParams(0, mapOf("userId" to "123")) 
     *     }
     *     navigateTo("dashboard")
     * }
     * ```
     */
    suspend fun guidedFlow(flowRoute: String, block: suspend GuidedFlowOperationBuilder.() -> Unit): NavigationBuilder {
        val builder = guidedFlowOperations.getOrPut(flowRoute) { 
            GuidedFlowOperationBuilder(flowRoute, storeAccessor) 
        }
        builder.block()
        return this
    }
    
    /**
     * Execute guided flow operations on the currently active guided flow.
     * Automatically detects the active flow route and operates on it.
     * 
     * Example:
     * ```kotlin
     * store.navigation {
     *     activeGuidedFlow {
     *         nextStep()
     *         updateStepParams(0, mapOf("data" to "value"))
     *     }
     * }
     * ```
     * 
     * @throws IllegalStateException if no guided flow is currently active
     */
    suspend fun activeGuidedFlow(block: suspend GuidedFlowOperationBuilder.() -> Unit): NavigationBuilder {
        val currentState = storeAccessor.selectState<NavigationState>().first()
        val activeFlowRoute = currentState.activeGuidedFlowState?.flowRoute
            ?: throw IllegalStateException("No active guided flow to operate on")
        
        return guidedFlow(activeFlowRoute, block)
    }
    
    // GuidedFlow support
    fun setGuidedFlowContext(context: GuidedFlowContext): NavigationBuilder {
        guidedFlowContext = context
        return this
    }

    internal fun validate() {
        if (operations.isEmpty() && guidedFlowOperations.isEmpty()) {
            throw IllegalStateException("No navigation or guided flow operations specified")
        }
        
        // Validate guided flow operations
        guidedFlowOperations.values.forEach { builder ->
            builder.validate()
        }
        
        // Validate each operation has required targets
        operations.forEach { step ->
            when (step.operation) {
                NavigationOperation.PopUpTo -> {
                    require(step.popUpToTarget != null) { 
                        "PopUpTo operation requires a popUpTo target" 
                    }
                }
                NavigationOperation.ClearBackStack -> {
                    require(step.popUpToTarget == null) { 
                        "Pure ClearBackStack operation cannot have popUpTo target" 
                    }
                }
                NavigationOperation.Navigate, NavigationOperation.Replace -> {
                    require(step.target != null) { 
                        "${step.operation.name} operation requires a target" 
                    }
                }
                NavigationOperation.Back -> { /* Always valid */ }
            }
        }
        
        // Validate operation combinations that don't make sense
        val operationTypes = operations.map { it.operation }.toSet()
        val hasClearBackStack = NavigationOperation.ClearBackStack in operationTypes
        val hasPopUpTo = NavigationOperation.PopUpTo in operationTypes
        val hasReplaceCurrent = operations.any { it.shouldReplaceWith }
        
        require(!(hasClearBackStack && hasPopUpTo)) {
            "Cannot combine clearBackStack with popUpTo operations in the same batch"
        }
        
        require(!(hasClearBackStack && hasReplaceCurrent)) {
            "Cannot combine clearBackStack with replaceCurrent operations in the same batch"
        }
    }

    internal fun encodeParametersForStep(stepParams: Params): Params {
        // Params already handles encoding/decoding, so we can return as-is
        return stepParams
    }

    @PublishedApi
    internal suspend inline fun <reified T : Navigatable> findNavigatableByType(): Navigatable {
        return findNavigatableByType(T::class)
    }

    @PublishedApi
    internal suspend fun <T : Navigatable> findNavigatableByType(navigatableClass: KClass<T>): Navigatable {
        val navigationState = storeAccessor.selectState<NavigationState>().first()
        val matchingNavigatable = navigationState.allAvailableNavigatables.values
            .firstOrNull { navigatable -> navigatable::class == navigatableClass }

        return matchingNavigatable ?: throw RouteNotFoundException(
            "Navigatable ${navigatableClass.simpleName} not found in navigation graph. " +
                    "Available navigatables: ${navigationState.allAvailableNavigatables.values.map { it::class.simpleName }}"
        )
    }
    
    /**
     * Get all guided flow operations for execution
     */
    internal fun getGuidedFlowOperations(): Map<String, GuidedFlowOperationBuilder> {
        return guidedFlowOperations.toMap()
    }
    
    /**
     * Check if there are any guided flow operations
     */
    internal fun hasGuidedFlowOperations(): Boolean {
        return guidedFlowOperations.isNotEmpty()
    }
    
    /**
     * Set the active guided flow state to be included in the navigation update
     */
    fun setActiveGuidedFlowState(flowState: GuidedFlowState): NavigationBuilder {
        this.activeGuidedFlowState = flowState
        this.shouldClearActiveGuidedFlowState = false
        return this
    }
    
    /**
     * Clear the active guided flow state in the navigation update
     */
    fun clearActiveGuidedFlowState(): NavigationBuilder {
        this.activeGuidedFlowState = null
        this.shouldClearActiveGuidedFlowState = true
        return this
    }
}