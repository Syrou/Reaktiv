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
import io.github.syrou.reaktiv.navigation.util.CommonUrlEncoder
import io.github.syrou.reaktiv.navigation.util.parseUrlWithQueryParams
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
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
    val params: StringAnyMap = emptyMap(),
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



class NavigationBuilder(
    private val storeAccessor: StoreAccessor,
    private val encoder: DualNavigationParameterEncoder = DualNavigationParameterEncoder()
) {
    @PublishedApi
    internal val operations = mutableListOf<NavigationStep>()
    
    @PublishedApi
    internal val currentParams = mutableMapOf<String, Any>()
    
    @PublishedApi
    internal var shouldDismissModalsGlobally = false


    fun navigateTo(path: String, replaceCurrent: Boolean = false, paramBuilder: (NavigationParameterBuilder.() -> Unit)? = null): NavigationBuilder {
        val (cleanPath, queryParams) = parseUrlWithQueryParams(path)
        val stepParams = mutableMapOf<String, Any>()
        
        // Add parsed query parameters
        queryParams.forEach { (key, value) ->
            stepParams[key] = value
        }

        // Apply parameter builder if provided
        paramBuilder?.let { builder ->
            val parameterBuilder = NavigationParameterBuilder()
            builder(parameterBuilder)
            stepParams.putAll(parameterBuilder.params)
        }
        
        // Add current accumulated params
        stepParams.putAll(currentParams)
        currentParams.clear()
        
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
        
        val stepParams = mutableMapOf<String, Any>()
        
        // Apply parameter builder if provided
        paramBuilder?.let { builder ->
            val parameterBuilder = NavigationParameterBuilder()
            builder(parameterBuilder)
            stepParams.putAll(parameterBuilder.params)
        }
        
        // Add current accumulated params
        stepParams.putAll(currentParams)
        currentParams.clear()
        
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

    inline fun <reified T> put(key: String, value: T): NavigationBuilder {
        currentParams[key] = SerializableParam(value, serializer<T>())
        return this
    }

    fun <T> put(key: String, value: T, serializer: KSerializer<T>): NavigationBuilder {
        currentParams[key] = SerializableParam(value, serializer)
        return this
    }

    fun putRaw(key: String, value: Any): NavigationBuilder {
        currentParams[key] = value
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

    internal fun validate() {
        if (operations.isEmpty()) {
            throw IllegalStateException("No navigation operations specified")
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

    internal fun encodeParametersForStep(stepParams: Map<String, Any>): Map<String, Any> {
        return stepParams.mapValues { (_, value) ->
            encoder.encodeMixed(value)
        }
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
}