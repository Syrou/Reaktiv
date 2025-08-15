package io.github.syrou.reaktiv.navigation.extension

import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.NavigationTarget
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.util.RouteResolver
import io.github.syrou.reaktiv.navigation.util.parseUrlWithQueryParams
import kotlinx.coroutines.flow.first


@Deprecated("Use store.navigation{...} instead")
class PopUpToBuilder(
    var route: String,
    var inclusive: Boolean = false
) {
    private var replaceWith: String? = null
    private var replaceParams: Map<String, Any> = emptyMap()

    @Deprecated("Use store.navigation{...} instead")
    fun replaceWith(route: String, params: Map<String, Any> = emptyMap()): PopUpToBuilder {
        this.replaceWith = route
        this.replaceParams = params
        return this
    }

    internal fun build(): PopUpToConfig {
        return PopUpToConfig(
            replaceWith = replaceWith,
            replaceParams = replaceParams
        )
    }
}


@Deprecated("Use store.navigation{...} instead")
class ClearBackStackBuilder(
    private var route: String? = null,
    private var params: Map<String, Any> = emptyMap()
) {
    @Deprecated("Use store.navigation{...} instead")
    fun navigate(route: String, params: Map<String, Any> = emptyMap()) {
        this.route = route
        this.params = params
    }

    internal fun build(): ClearBackStackConfig {
        return ClearBackStackConfig(root = route, params = params)
    }
}


@Deprecated("Use store.navigation{...} instead")
data class PopUpToConfig(
    val replaceWith: String?,
    val replaceParams: Map<String, Any>
)

@Deprecated("Use store.navigation{...} instead")
data class ClearBackStackConfig(
    val root: String?,
    val params: Map<String, Any>
)


suspend fun StoreAccessor.navigation(block: suspend NavigationBuilder.() -> Unit) {
    val navigationLogic = selectLogic<NavigationLogic>()
    navigationLogic.navigate(block)
}


@Deprecated("Use store.navigation{...} instead")
suspend fun StoreAccessor.navigate(
    route: String,
    params: Map<String, Any> = emptyMap(),
    config: (NavigationBuilder.() -> Unit)? = null
) {
    val navigationLogic = selectLogic<NavigationLogic>()

    // Parse query parameters from the route string
    val (cleanRoute, queryParams) = parseUrlWithQueryParams(route)

    // Merge query parameters with provided params (provided params take precedence)
    val mergedParams = queryParams + params

    navigationLogic.navigate(cleanRoute, mergedParams, replaceCurrent = false, config)
}


@Deprecated("Use store.navigation{...} instead")
suspend fun StoreAccessor.popUpTo(
    route: String,
    inclusive: Boolean = false,
    config: (PopUpToBuilder.() -> Unit)? = null
) {
    val navigationLogic = selectLogic<NavigationLogic>()

    // Parse query parameters from the route string for popUpTo target
    val (cleanRoute, _) = parseUrlWithQueryParams(route)
    // Note: We don't use query params for popUpTo targets as they're used for matching

    config?.let { configBlock ->
        val legacyBuilder = PopUpToBuilder(cleanRoute, inclusive)
        legacyBuilder.configBlock()
        val popUpToConfig = legacyBuilder.build()
        if (popUpToConfig.replaceWith != null) {
            navigationLogic.navigate {
                val (replaceCleanRoute, replaceQueryParams) = parseUrlWithQueryParams(popUpToConfig.replaceWith)
                popUpTo(cleanRoute, inclusive)
                // Set the target for popUpTo operation to navigate to the replacement route
                target = NavigationTarget.Path(replaceCleanRoute)

                // Add parsed query parameters from replaceWith route
                replaceQueryParams.forEach { (key, value) ->
                    putString(key, value)
                }

                // Add explicit replace params (these take precedence)
                popUpToConfig.replaceParams.forEach { (key, value) ->
                    putRaw(key, value)
                }
            }
        } else {
            navigationLogic.popUpTo(cleanRoute, inclusive)
        }
    } ?: run {
        navigationLogic.popUpTo(cleanRoute, inclusive)
    }
}

@Deprecated("Use store.navigation{...} instead")
suspend fun StoreAccessor.clearBackStack(config: (ClearBackStackBuilder.() -> Unit)? = null) {
    val navigationLogic = selectLogic<NavigationLogic>()
    config?.let { configBlock ->
        val legacyBuilder = ClearBackStackBuilder()
        legacyBuilder.configBlock()
        val clearConfig = legacyBuilder.build()

        if (clearConfig.root != null) {
            // Parse query parameters from the root route string
            val (cleanRoot, queryParams) = parseUrlWithQueryParams(clearConfig.root)

            // Merge query parameters with provided params (provided params take precedence)
            val mergedParams = queryParams + clearConfig.params

            navigationLogic.clearBackStack(cleanRoot, mergedParams)
        } else {
            navigationLogic.clearBackStack()
        }
    } ?: run {
        navigationLogic.clearBackStack()
    }
}


@Deprecated("Use store.navigation{ navigateTo(route, replaceCurrent = true) } instead")
suspend fun StoreAccessor.replaceWith(route: String, params: Map<String, Any> = emptyMap()) {
    val navigationLogic = selectLogic<NavigationLogic>()

    // Parse query parameters from the route string
    val (cleanRoute, queryParams) = parseUrlWithQueryParams(route)

    // Merge query parameters with provided params (provided params take precedence)
    val mergedParams = queryParams + params

    navigationLogic.navigate(cleanRoute, mergedParams, replaceCurrent = true)
}

@Deprecated("These introduce a bad pattern for knowing when to clear data, don't use it")
suspend fun StoreAccessor.clearCurrentScreenParams() {
    val navigationLogic = selectLogic<NavigationLogic>()
    navigationLogic.clearCurrentScreenParams()
}

@Deprecated("These introduce a bad pattern for knowing when to clear data, don't use it")
suspend fun StoreAccessor.clearCurrentScreenParam(key: String) {
    val navigationLogic = selectLogic<NavigationLogic>()
    navigationLogic.clearCurrentScreenParam(key)
}

@Deprecated("These introduce a bad pattern for knowing when to clear data, don't use it")
suspend fun StoreAccessor.clearScreenParams(route: String) {
    val navigationLogic = selectLogic<NavigationLogic>()
    navigationLogic.clearScreenParams(route)
}

@Deprecated("These introduce a bad pattern for knowing when to clear data, don't use it")
suspend fun StoreAccessor.clearScreenParam(route: String, key: String) {
    val navigationLogic = selectLogic<NavigationLogic>()
    navigationLogic.clearScreenParam(route, key)
}


suspend fun StoreAccessor.navigateBack() {
    val navigationLogic = selectLogic<NavigationLogic>()
    navigationLogic.navigateBack()
}


suspend inline fun <reified T : Modal> StoreAccessor.presentModal(
    noinline config: (suspend NavigationBuilder.() -> Unit)? = null
) {
    navigation {
        presentModal<T>()
        config?.invoke(this)
    }
}

suspend fun StoreAccessor.dismissModal() {
    navigateBack()
}

inline fun Modifier.applyIf(condition : Boolean, modifier : Modifier.() -> Modifier) : Modifier {
    return if (condition) {
        then(modifier(Modifier))
    } else {
        this
    }
}

suspend fun StoreAccessor.dismissModal(modalEntry: NavigationEntry) {
    if (modalEntry.isModal) {
        selectLogic<NavigationLogic>().popUpTo(modalEntry.navigatable.route, inclusive = true)
    }
}

suspend fun StoreAccessor.clearAllModals() {
    val navigationState = selectState<NavigationState>().first()
    val lastScreen = navigationState.orderedBackStack.reversed().firstOrNull { it.isScreen }

    if (lastScreen != null) {
        val navigationLogic = selectLogic<NavigationLogic>()
        navigationLogic.popUpTo(lastScreen.navigatable.route, inclusive = false)
    }
}


fun NavigationState.getZIndex(entry: NavigationEntry): Float {
    return visibleLayers.find { it.entry == entry }?.zIndex ?: entry.zIndex
}


val NavigationState.currentFullPath: String
    get() = this.currentFullPath


val NavigationState.currentPathSegments: List<String>
    get() = currentFullPath.split("/").filter { it.isNotEmpty() }


val NavigationState.currentGraphHierarchy: List<String>
    get() = this.currentGraphHierarchy


fun NavigationState.isInGraph(graphId: String): Boolean {
    return currentGraphHierarchy.contains(graphId)
}


fun NavigationState.isAtPath(path: String): Boolean {
    return currentFullPath == path.trimStart('/').trimEnd('/')
}


val NavigationState.navigationDepth: Int
    get() = currentPathSegments.size