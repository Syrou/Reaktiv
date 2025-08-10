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
    Modal,
    PopUpTo,
    ClearBackStack
}


class NavigationBuilder(
    private val storeAccessor: StoreAccessor,
    private val encoder: DualNavigationParameterEncoder = DualNavigationParameterEncoder()
) {
    @PublishedApi
    internal var target: NavigationTarget? = null

    @PublishedApi
    internal var operation: NavigationOperation = NavigationOperation.Navigate

    @PublishedApi
    internal val params = mutableMapOf<String, Any>()

    @PublishedApi
    internal var popUpToTarget: NavigationTarget? = null

    @PublishedApi
    internal var popUpToInclusive: Boolean = false

    @PublishedApi
    internal var shouldClearBackStack: Boolean = false

    @PublishedApi
    internal var shouldReplaceWith: Boolean = false

    @PublishedApi
    internal var shouldForwardParams: Boolean = false

    @PublishedApi
    internal var shouldBypassSpamProtection: Boolean = false

    fun navigateTo(path: String): NavigationBuilder {
        this.target = NavigationTarget.Path(path)
        this.operation = NavigationOperation.Navigate
        return this
    }

    fun navigateBack(): NavigationBuilder {
        this.operation = NavigationOperation.Back
        return this
    }

    suspend inline fun <reified T : Screen> navigateTo(): NavigationBuilder {
        return navigateTo<T>(null)
    }

    suspend inline fun <reified T : Navigatable> navigateTo(preferredGraphId: String? = null): NavigationBuilder {
        val navigatable = findNavigatableByType<T>()
        this.target = if (preferredGraphId != null) {
            NavigationTarget.NavigatableObjectWithGraph(navigatable, preferredGraphId)
        } else {
            NavigationTarget.NavigatableObject(navigatable)
        }
        this.operation = when (navigatable) {
            is Modal -> NavigationOperation.Modal
            else -> NavigationOperation.Navigate
        }
        return this
    }

    suspend inline fun <reified T : Modal> presentModal(): NavigationBuilder {
        val modal = findNavigatableByType<T>()
        this.target = NavigationTarget.NavigatableObject(modal)
        this.operation = NavigationOperation.Modal
        return this
    }

    fun replaceWith(path: String): NavigationBuilder {
        this.target = NavigationTarget.Path(path)
        this.operation = NavigationOperation.Replace
        this.shouldReplaceWith = true
        return this
    }

    suspend inline fun <reified T : Navigatable> replaceWith(preferredGraphId: String? = null): NavigationBuilder {
        val navigatable = findNavigatableByType<T>()
        this.target = if (preferredGraphId != null) {
            NavigationTarget.NavigatableObjectWithGraph(navigatable, preferredGraphId)
        } else {
            NavigationTarget.NavigatableObject(navigatable)
        }
        this.operation = NavigationOperation.Replace
        this.shouldReplaceWith = true
        return this
    }

    fun popUpTo(path: String, inclusive: Boolean = false): NavigationBuilder {
        this.popUpToTarget = NavigationTarget.Path(path)
        this.popUpToInclusive = inclusive
        this.operation = NavigationOperation.PopUpTo
        return this
    }

    suspend inline fun <reified T : Navigatable> popUpTo(
        inclusive: Boolean = false,
        preferredGraphId: String? = null
    ): NavigationBuilder {
        val navigatable = findNavigatableByType<T>()
        this.popUpToTarget = if (preferredGraphId != null) {
            NavigationTarget.NavigatableObjectWithGraph(navigatable, preferredGraphId)
        } else {
            NavigationTarget.NavigatableObject(navigatable)
        }
        this.popUpToInclusive = inclusive
        this.operation = NavigationOperation.PopUpTo
        return this
    }

    fun clearBackStack(): NavigationBuilder {
        this.shouldClearBackStack = true
        this.operation = NavigationOperation.ClearBackStack
        return this
    }

    @Deprecated("This introduces a bad pattern for knowing when to clear data, don't use it")
    fun forwardParams(): NavigationBuilder {
        this.shouldForwardParams = true
        return this
    }

    /**
     * Bypass spam protection for this navigation operation
     * Use this for programmatic navigation sequences where you want to allow rapid navigation
     */
    fun bypassSpamProtection(): NavigationBuilder {
        this.shouldBypassSpamProtection = true
        return this
    }

    inline fun <reified T> put(key: String, value: T): NavigationBuilder {
        params[key] = SerializableParam(value, serializer<T>())
        return this
    }

    fun <T> put(key: String, value: T, serializer: KSerializer<T>): NavigationBuilder {
        params[key] = SerializableParam(value, serializer)
        return this
    }

    fun putString(key: String, value: String): NavigationBuilder {
        params[key] = value
        return this
    }

    fun putInt(key: String, value: Int): NavigationBuilder {
        params[key] = value
        return this
    }

    fun putBoolean(key: String, value: Boolean): NavigationBuilder {
        params[key] = value
        return this
    }

    fun putDouble(key: String, value: Double): NavigationBuilder {
        params[key] = value
        return this
    }

    fun putLong(key: String, value: Long): NavigationBuilder {
        params[key] = value
        return this
    }

    fun putFloat(key: String, value: Float): NavigationBuilder {
        params[key] = value
        return this
    }

    fun putRaw(key: String, value: Any): NavigationBuilder {
        params[key] = value
        return this
    }

    fun param(key: String, value: String) = putString(key, value)
    fun param(key: String, value: Int) = putInt(key, value)
    fun param(key: String, value: Boolean) = putBoolean(key, value)
    fun param(key: String, value: Double) = putDouble(key, value)
    fun param(key: String, value: Long) = putLong(key, value)
    fun param(key: String, value: Any) = putRaw(key, value)

    internal fun validate() {
        when (operation) {
            NavigationOperation.Back -> {
                if (target != null || popUpToTarget != null || shouldClearBackStack) {
                    throw IllegalStateException("Back operation cannot be combined with other navigation operations")
                }
            }

            NavigationOperation.PopUpTo -> {
                if (popUpToTarget == null) {
                    throw IllegalStateException("PopUpTo operation requires a popUpTo target")
                }
            }

            NavigationOperation.ClearBackStack -> {
                if (popUpToTarget != null) {
                    throw IllegalStateException("Pure ClearBackStack operation cannot have popUpTo target")
                }
            }

            NavigationOperation.Navigate, NavigationOperation.Replace, NavigationOperation.Modal -> {
                if (target == null) {
                    throw IllegalStateException("${operation.name} operation requires a target")
                }
                if (shouldClearBackStack && popUpToTarget != null) {
                    throw IllegalStateException("Cannot combine clearBackStack with popUpTo in navigation operations")
                }
            }
        }
    }

    internal fun encodeParameters(): Map<String, Any> {
        return params.mapValues { (_, value) ->
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