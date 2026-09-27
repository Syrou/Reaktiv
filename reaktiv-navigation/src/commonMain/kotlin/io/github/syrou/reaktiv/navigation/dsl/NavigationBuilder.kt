package io.github.syrou.reaktiv.navigation.dsl

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationTarget
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.encoding.DualNavigationParameterEncoder
import io.github.syrou.reaktiv.navigation.exception.RouteNotFoundException
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.util.getNavigationModule
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.param.typedParamValue
import io.github.syrou.reaktiv.navigation.util.parseUrlWithQueryParams
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

@Serializable
public enum class NavigationOperation {
    Navigate,
    Replace,
    Back,
    PopUpTo,
    ClearBackStack,
    ResumePending,
    DismissModal,
    ClearModals,
    DeepLink
}

@Suppress("DEPRECATION")
@Serializable
public data class NavigationStep(
    val operation: NavigationOperation,
    val target: NavigationTarget? = null,
    val params: Params = Params.empty(),
    val popUpToTarget: NavigationTarget? = null,
    val popUpToInclusive: Boolean = false,
    val popUpToFallback: NavigationTarget? = null,
    @Deprecated("Restates operation. Check operation == NavigationOperation.ClearBackStack.", level = DeprecationLevel.WARNING)
    val shouldClearBackStack: Boolean = false,
    @Deprecated("Restates operation. Check operation == NavigationOperation.Replace.", level = DeprecationLevel.WARNING)
    val shouldReplaceWith: Boolean = false,
    val shouldDismissModals: Boolean = false,
    val synthesizeBackstack: Boolean = false,
    val expectedTopKey: String? = null
)

public class NavigationParameterBuilder {
    @PublishedApi
    internal val params: MutableMap<String, Any> = mutableMapOf()

    public inline fun <reified T> put(key: String, value: T): NavigationParameterBuilder {
        params[key] = typedParamValue(value, serializer<T>())
        return this
    }

    public fun <T> put(key: String, value: T, serializer: KSerializer<T>): NavigationParameterBuilder {
        params[key] = typedParamValue(value, serializer)
        return this
    }

    public fun putRaw(key: String, value: Any): NavigationParameterBuilder {
        params[key] = value
        return this
    }
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
 * @param encoder Ignored. Retained for source compatibility and removed in a later release.
 */
public class NavigationBuilder(
    private val storeAccessor: StoreAccessor,
    @Suppress("UNUSED_PARAMETER") encoder: DualNavigationParameterEncoder = DualNavigationParameterEncoder()
) {
    @PublishedApi
    internal val operations: MutableList<NavigationStep> = mutableListOf()

    @PublishedApi
    internal var currentParams: Params = Params.empty()

    @PublishedApi
    internal var shouldDismissModalsGlobally: Boolean = false

    private var ignoredOperations: Int = 0

    internal val hasNothingToDo: Boolean get() = operations.isEmpty() && ignoredOperations > 0

    /**
     * Get the full path for a Navigatable within the navigation builder context.
     *
     * Example usage:
     * ```kotlin
     * store.navigation {
     *     navigateTo(ToolsScreen.fullPath)
     * }
     * ```
     *
     * @return The full path for the navigatable
     * @throws IllegalStateException if the navigatable is not registered
     */
    @Deprecated("Unused. Removed in the next release. Navigate to the navigatable itself.", level = DeprecationLevel.WARNING)
    public val Navigatable.fullPath: String
        get() = storeAccessor.getNavigationModule().getFullPath(this)
            ?: error("Navigatable '${this.route}' is not registered in any navigation graph")

    /**
     * Navigate to a path with optional parameters.
     *
     * @param path The route path to navigate to
     * @param replaceCurrent If true, replaces the current entry instead of pushing
     * @param synthesizeBackstack If true, automatically builds the backstack based on path hierarchy.
     * @param paramBuilder Optional builder for navigation parameters
     */
    public fun navigateTo(
        path: String,
        replaceCurrent: Boolean = false,
        synthesizeBackstack: Boolean = false,
        paramBuilder: (NavigationParameterBuilder.() -> Unit)? = null
    ): NavigationBuilder {
        val (cleanPath, queryParams) = parseUrlWithQueryParams(path)
        var stepParams = Params.fromMap(queryParams)

        paramBuilder?.let { builder ->
            val parameterBuilder = NavigationParameterBuilder()
            builder(parameterBuilder)
            stepParams += Params.fromMap(parameterBuilder.params)
        }

        stepParams += currentParams
        currentParams = Params.empty()

        val step = NavigationStep(
            operation = if (replaceCurrent) NavigationOperation.Replace else NavigationOperation.Navigate,
            target = NavigationTarget.Path(cleanPath),
            params = stepParams,
            shouldReplaceWith = replaceCurrent,
            shouldDismissModals = shouldDismissModalsGlobally,
            synthesizeBackstack = synthesizeBackstack
        )
        operations.add(step)

        return this
    }

    /**
     * Navigate to a [Navigatable] instance with full-path resolution.
     *
     * Unlike [navigateTo] with a string path, this overload resolves the navigatable's
     * full path from the navigation graph, so nested screens are found correctly.
     *
     * @param navigatable The navigatable to navigate to
     * @param replaceCurrent If true, replaces the current entry instead of pushing
     * @param synthesizeBackstack If true, automatically builds the backstack based on path hierarchy.
     */
    public fun navigateTo(
        path: String,
        params: Params?,
        replaceCurrent: Boolean = false,
        synthesizeBackstack: Boolean = false
    ): NavigationBuilder {
        params?.let { currentParams += it }
        return navigateTo(path, replaceCurrent = replaceCurrent, synthesizeBackstack = synthesizeBackstack)
    }

    public fun navigateTo(
        navigatable: Navigatable,
        replaceCurrent: Boolean = false,
        synthesizeBackstack: Boolean = false
    ): NavigationBuilder = addNavigatableStep(navigatable, Params.empty(), replaceCurrent, synthesizeBackstack)

    public fun navigateTo(
        navigatable: Navigatable,
        vararg params: Pair<String, Any>,
        replaceCurrent: Boolean = false,
        synthesizeBackstack: Boolean = false
    ): NavigationBuilder =
        addNavigatableStep(navigatable, Params.fromMap(params.toMap()), replaceCurrent, synthesizeBackstack)

    public fun navigateTo(
        navigatable: Navigatable,
        replaceCurrent: Boolean = false,
        synthesizeBackstack: Boolean = false,
        paramBuilder: NavigationParameterBuilder.() -> Unit
    ): NavigationBuilder {
        val parameterBuilder = NavigationParameterBuilder().apply(paramBuilder)
        return addNavigatableStep(
            navigatable,
            Params.fromMap(parameterBuilder.params),
            replaceCurrent,
            synthesizeBackstack
        )
    }

    private fun addNavigatableStep(
        navigatable: Navigatable,
        params: Params,
        replaceCurrent: Boolean,
        synthesizeBackstack: Boolean
    ): NavigationBuilder {
        val stepParams = params + currentParams
        currentParams = Params.empty()

        val step = NavigationStep(
            operation = if (replaceCurrent) NavigationOperation.Replace else NavigationOperation.Navigate,
            target = NavigationTarget.NavigatableObject(navigatable),
            params = stepParams,
            shouldReplaceWith = replaceCurrent,
            shouldDismissModals = shouldDismissModalsGlobally,
            synthesizeBackstack = synthesizeBackstack
        )
        operations.add(step)

        return this
    }

    public fun navigateBack(): NavigationBuilder = navigateBack(expectedTopKey = null)

    public fun navigateBack(expectedTopKey: String?): NavigationBuilder {
        val step = NavigationStep(
            operation = NavigationOperation.Back,
            shouldDismissModals = shouldDismissModalsGlobally,
            expectedTopKey = expectedTopKey
        )
        operations.add(step)

        return this
    }

    public fun dismissModal(): NavigationBuilder {
        operations.add(NavigationStep(operation = NavigationOperation.DismissModal))
        return this
    }

    public fun dismissModal(modalEntry: NavigationEntry): NavigationBuilder {
        if (modalEntry.navigatable is Modal) {
            popUpTo(modalEntry.location, inclusive = true)
        } else {
            ignoredOperations++
        }
        return this
    }

    public fun clearAllModals(): NavigationBuilder {
        operations.add(NavigationStep(operation = NavigationOperation.ClearModals))
        return this
    }

    public fun navigateDeepLink(route: String, params: Params = Params.empty()): NavigationBuilder {
        val stepParams = params + currentParams
        currentParams = Params.empty()
        operations.add(
            NavigationStep(
                operation = NavigationOperation.DeepLink,
                target = NavigationTarget.Path(route),
                params = stepParams
            )
        )
        return this
    }

    public suspend inline fun <reified T : Screen> navigateTo(
        replaceCurrent: Boolean = false,
        synthesizeBackstack: Boolean = false
    ): NavigationBuilder {
        return navigateTo<T>(replaceCurrent = replaceCurrent, preferredGraphId = null, synthesizeBackstack = synthesizeBackstack)
    }

    public suspend inline fun <reified T : Navigatable> navigateTo(
        params: Params?,
        replaceCurrent: Boolean = false,
        preferredGraphId: String? = null,
        synthesizeBackstack: Boolean = false
    ): NavigationBuilder {
        params?.let { currentParams += it }
        return navigateTo<T>(
            replaceCurrent = replaceCurrent,
            preferredGraphId = preferredGraphId,
            synthesizeBackstack = synthesizeBackstack
        )
    }

    /**
     * Navigate to a screen type with optional parameters.
     *
     * @param replaceCurrent If true, replaces the current entry instead of pushing
     * @param preferredGraphId Optional graph ID to prefer when resolving the target
     * @param synthesizeBackstack If true, automatically builds the backstack based on path hierarchy.
     * @param paramBuilder Optional builder for navigation parameters
     */
    public suspend inline fun <reified T : Navigatable> navigateTo(
        replaceCurrent: Boolean = false,
        preferredGraphId: String? = null,
        synthesizeBackstack: Boolean = false,
        noinline paramBuilder: (NavigationParameterBuilder.() -> Unit)? = null
    ): NavigationBuilder {
        val navigatable = findNavigatableByType<T>()
        val target = if (preferredGraphId != null) {
            NavigationTarget.NavigatableObjectWithGraph(navigatable, preferredGraphId)
        } else {
            NavigationTarget.NavigatableObject(navigatable)
        }

        var stepParams = Params.empty()

        paramBuilder?.let { builder ->
            val parameterBuilder = NavigationParameterBuilder()
            builder(parameterBuilder)
            stepParams += Params.fromMap(parameterBuilder.params)
        }

        stepParams += currentParams
        currentParams = Params.empty()

        val step = NavigationStep(
            operation = if (replaceCurrent) NavigationOperation.Replace else NavigationOperation.Navigate,
            target = target,
            params = stepParams,
            shouldReplaceWith = replaceCurrent,
            shouldDismissModals = shouldDismissModalsGlobally,
            synthesizeBackstack = synthesizeBackstack
        )
        operations.add(step)

        return this
    }

    /**
     * Pop back to a specific route in the navigation stack.
     *
     * @param path The route to pop back to
     * @param inclusive If true, also removes the target route from the backstack
     * @param fallback Optional fallback route if the target route is not found in the backstack.
     */
    public fun popUpTo(path: String, inclusive: Boolean = false, fallback: String? = null): NavigationBuilder {
        val (cleanPath, _) = parseUrlWithQueryParams(path)
        val fallbackTarget = fallback?.let {
            val (cleanFallback, _) = parseUrlWithQueryParams(it)
            NavigationTarget.Path(cleanFallback)
        }

        val step = NavigationStep(
            operation = NavigationOperation.PopUpTo,
            popUpToTarget = NavigationTarget.Path(cleanPath),
            popUpToInclusive = inclusive,
            popUpToFallback = fallbackTarget,
            shouldDismissModals = shouldDismissModalsGlobally
        )
        operations.add(step)

        return this
    }

    /**
     * Pop back to a specific screen type in the navigation stack.
     *
     * @param inclusive If true, also removes the target route from the backstack
     * @param preferredGraphId Optional graph ID to prefer when resolving the target
     * @param fallback Optional fallback route if the target route is not found in the backstack.
     */
    public suspend inline fun <reified T : Navigatable> popUpTo(
        inclusive: Boolean = false,
        preferredGraphId: String? = null,
        fallback: String? = null
    ): NavigationBuilder {
        val navigatable = findNavigatableByType<T>()
        val target = if (preferredGraphId != null) {
            NavigationTarget.NavigatableObjectWithGraph(navigatable, preferredGraphId)
        } else {
            NavigationTarget.NavigatableObject(navigatable)
        }

        val fallbackTarget = fallback?.let {
            val (cleanFallback, _) = parseUrlWithQueryParams(it)
            NavigationTarget.Path(cleanFallback)
        }

        val step = NavigationStep(
            operation = NavigationOperation.PopUpTo,
            popUpToTarget = target,
            popUpToInclusive = inclusive,
            popUpToFallback = fallbackTarget,
            shouldDismissModals = shouldDismissModalsGlobally
        )
        operations.add(step)

        return this
    }

    public fun clearBackStack(): NavigationBuilder {
        val step = NavigationStep(
            operation = NavigationOperation.ClearBackStack,
            shouldClearBackStack = true,
            shouldDismissModals = shouldDismissModalsGlobally
        )
        operations.add(step)

        return this
    }

    /**
     * Resume the pending navigation stored by [GuardResult.PendAndRedirectTo], expanding it
     * into a synthesized backstack at the point this operation appears in the chain.
     *
     * The synthesis runs on top of whatever the simulated backstack looks like at this point,
     * so the order of operations in the `navigation { }` block controls the final stack shape.
     * No-op if there is no pending navigation in state.
     *
     * Example usage:
     * ```kotlin
     * store.navigation {
     *     clearBackStack()
     *     resumePendingNavigation()
     * }
     * ```
     */
    public fun resumePendingNavigation(): NavigationBuilder {
        operations.add(NavigationStep(operation = NavigationOperation.ResumePending))
        return this
    }

    /**
     * Dismiss any active modals as part of all navigation operations in this block
     */
    public fun dismissModals(): NavigationBuilder {
        shouldDismissModalsGlobally = true
        for (i in operations.indices) {
            operations[i] = operations[i].copy(shouldDismissModals = true)
        }
        return this
    }

    /**
     * Whether this navigation was asked for from outside the app, as a deep link is.
     *
     * The app's own position inside a protected zone is what earns a guard skip, and a request
     * arriving from outside inherits no part of it: the link makes its own case at the door.
     */
    internal var isExternallyRequested: Boolean = false
        private set

    internal fun markExternallyRequested() {
        isExternallyRequested = true
    }

    /**
     * Whether executing this builder discards every entry already in the back stack.
     *
     * `ClearBackStack` wipes the stack wherever it sits among the operations, so the entries
     * standing there when the navigation begins do not survive it.
     */
    internal fun clearsBackStack(): Boolean =
        operations.any { it.operation == NavigationOperation.ClearBackStack }

    internal fun describeTarget(): String =
        operations.firstOrNull { it.target != null }?.let { step ->
            runCatching { step.target.toString() }.getOrNull()
        } ?: operations.joinToString(",") { it.operation.name }.ifEmpty { "empty" }

    public fun params(params: Params): NavigationBuilder {
        currentParams += params
        return this
    }

    public inline fun <reified T : Any> put(key: String, value: T): NavigationBuilder {
        currentParams = currentParams.withTyped(key, value)
        return this
    }

    public fun putRaw(key: String, value: Any): NavigationBuilder {
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

    public fun putString(key: String, value: String): NavigationBuilder {
        currentParams = currentParams.with(key, value)
        return this
    }
    public fun putInt(key: String, value: Int): NavigationBuilder {
        currentParams = currentParams.with(key, value)
        return this
    }
    public fun putBoolean(key: String, value: Boolean): NavigationBuilder {
        currentParams = currentParams.with(key, value)
        return this
    }
    public fun putDouble(key: String, value: Double): NavigationBuilder {
        currentParams = currentParams.with(key, value)
        return this
    }
    public fun putLong(key: String, value: Long): NavigationBuilder {
        currentParams = currentParams.with(key, value)
        return this
    }
    public fun putFloat(key: String, value: Float): NavigationBuilder {
        currentParams = currentParams.with(key, value)
        return this
    }

    public fun param(key: String, value: Any): NavigationBuilder = putRaw(key, value)

    internal fun validate() {
        if (hasNothingToDo) return
        if (operations.isEmpty()) {
            throw IllegalStateException("No navigation operations specified")
        }

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
                NavigationOperation.ResumePending -> { /* Always valid */ }
                NavigationOperation.DismissModal, NavigationOperation.ClearModals -> Unit
                NavigationOperation.DeepLink -> {
                    require(step.target != null) {
                        "navigateDeepLink requires a route"
                    }
                    require(operations.size == 1) {
                        "navigateDeepLink replaces the whole back stack, so it must be the only " +
                            "operation in its navigation block"
                    }
                }
            }
        }

        val operationTypes = operations.map { it.operation }.toSet()
        val hasClearBackStack = NavigationOperation.ClearBackStack in operationTypes
        val hasPopUpTo = NavigationOperation.PopUpTo in operationTypes
        val hasReplaceCurrent = NavigationOperation.Replace in operationTypes

        require(!(hasClearBackStack && hasPopUpTo)) {
            "Cannot combine clearBackStack with popUpTo operations in the same batch"
        }

        require(!(hasClearBackStack && hasReplaceCurrent)) {
            "Cannot combine clearBackStack with replaceCurrent operations in the same batch"
        }
    }

    @PublishedApi
    internal suspend inline fun <reified T : Navigatable> findNavigatableByType(): Navigatable {
        return findNavigatableByType(T::class)
    }

    @PublishedApi
    internal suspend fun <T : Navigatable> findNavigatableByType(navigatableClass: KClass<T>): Navigatable {
        val navModule = storeAccessor.getNavigationModule()
        val allNavigatables = navModule.getGraphDefinitions().values
            .flatMap { graph -> graph.navigatables }

        val matchingNavigatable = allNavigatables
            .firstOrNull { navigatable -> navigatable::class == navigatableClass }

        return matchingNavigatable ?: throw RouteNotFoundException(
            "Navigatable ${navigatableClass.simpleName} not found in navigation graph. " +
                    "Available navigatables: ${allNavigatables.map { it::class.simpleName }}"
        )
    }
}
