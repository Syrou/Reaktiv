package io.github.syrou.reaktiv.navigation.extension

import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import io.github.syrou.reaktiv.navigation.link.LinkOutcome
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.param.Params

public suspend fun StoreAccessor.navigation(block: suspend NavigationBuilder.() -> Unit) {
    val navigationLogic = selectLogic<NavigationLogic>()
    navigationLogic.navigate(block)
}

public suspend fun StoreAccessor.openLink(link: String, params: Map<String, String> = emptyMap()): LinkOutcome =
    selectLogic<NavigationLogic>().openLink(link, params)

@Deprecated(
    SINGLE_OPERATION_DEPRECATION,
    ReplaceWith("navigation { navigateBack() }", NAVIGATION_IMPORT),
    DeprecationLevel.WARNING
)
public suspend fun StoreAccessor.navigateBack() {
    navigation { navigateBack() }
}

@Deprecated(
    SINGLE_OPERATION_DEPRECATION,
    ReplaceWith("navigation { navigateBack(expectedTopKey) }", NAVIGATION_IMPORT),
    DeprecationLevel.WARNING
)
public suspend fun StoreAccessor.navigateBack(expectedTopKey: String? = null) {
    navigation { navigateBack(expectedTopKey) }
}

@Deprecated(
    SINGLE_OPERATION_DEPRECATION,
    ReplaceWith("navigation { navigateTo(route) }", NAVIGATION_IMPORT),
    DeprecationLevel.WARNING
)
public suspend fun StoreAccessor.navigate(route: String) {
    navigation { navigateTo(route) }
}

@Deprecated(
    SINGLE_OPERATION_DEPRECATION,
    ReplaceWith("navigation { navigateTo(route, params) }", NAVIGATION_IMPORT),
    DeprecationLevel.WARNING
)
public suspend fun StoreAccessor.navigate(route: String, params: Params? = null) {
    navigation { navigateTo(route, params) }
}

@Deprecated(
    SINGLE_OPERATION_DEPRECATION,
    ReplaceWith("navigation { navigateTo<T>() }", NAVIGATION_IMPORT),
    DeprecationLevel.WARNING
)
public suspend inline fun <reified T : Navigatable> StoreAccessor.navigate() {
    navigation { navigateTo<T>() }
}

@Deprecated(
    SINGLE_OPERATION_DEPRECATION,
    ReplaceWith("navigation { navigateTo<T>(params) }", NAVIGATION_IMPORT),
    DeprecationLevel.WARNING
)
public suspend inline fun <reified T : Navigatable> StoreAccessor.navigate(params: Params? = null) {
    navigation { navigateTo<T>(params) }
}

@Deprecated(
    SINGLE_OPERATION_DEPRECATION,
    ReplaceWith("navigation { navigateTo<T>() }", NAVIGATION_IMPORT),
    DeprecationLevel.WARNING
)
public suspend inline fun <reified T : Modal> StoreAccessor.presentModal() {
    navigation { navigateTo<T>() }
}

@Deprecated(
    "Use the navigation { } DSL: call navigateTo<T>() and then the operations this config block held, " +
        "in the same navigation block.",
    level = DeprecationLevel.WARNING
)
public suspend inline fun <reified T : Modal> StoreAccessor.presentModal(
    noinline config: (suspend NavigationBuilder.() -> Unit)? = null
) {
    navigation {
        navigateTo<T>()
        config?.invoke(this)
    }
}

@Deprecated(
    SINGLE_OPERATION_DEPRECATION,
    ReplaceWith("navigation { dismissModal() }", NAVIGATION_IMPORT),
    DeprecationLevel.WARNING
)
public suspend fun StoreAccessor.dismissModal() {
    navigation { dismissModal() }
}

@Deprecated("Unused. Removed in the next release.", level = DeprecationLevel.WARNING)
public inline fun Modifier.applyIf(condition: Boolean, modifier: Modifier.() -> Modifier): Modifier {
    return if (condition) {
        then(modifier(Modifier))
    } else {
        this
    }
}

@Deprecated(
    SINGLE_OPERATION_DEPRECATION,
    ReplaceWith("navigation { dismissModal(modalEntry) }", NAVIGATION_IMPORT),
    DeprecationLevel.WARNING
)
public suspend fun StoreAccessor.dismissModal(modalEntry: NavigationEntry) {
    navigation { dismissModal(modalEntry) }
}

@Deprecated(
    SINGLE_OPERATION_DEPRECATION,
    ReplaceWith("navigation { navigateDeepLink(route) }", NAVIGATION_IMPORT),
    DeprecationLevel.WARNING
)
public suspend fun StoreAccessor.navigateDeepLink(route: String) {
    navigation { navigateDeepLink(route) }
}

/**
 * Navigate to a deep link route, resolving any registered aliases first.
 *
 * @param route The deep link path (may include query parameters)
 * @param params Additional parameters merged with any extracted from the route
 */
@Deprecated(
    SINGLE_OPERATION_DEPRECATION,
    ReplaceWith("navigation { navigateDeepLink(route, params) }", NAVIGATION_IMPORT),
    DeprecationLevel.WARNING
)
public suspend fun StoreAccessor.navigateDeepLink(route: String, params: Params = Params.empty()) {
    navigation { navigateDeepLink(route, params) }
}

@Deprecated(
    SINGLE_OPERATION_DEPRECATION,
    ReplaceWith("navigation { clearAllModals() }", NAVIGATION_IMPORT),
    DeprecationLevel.WARNING
)
public suspend fun StoreAccessor.clearAllModals() {
    navigation { clearAllModals() }
}

private const val SINGLE_OPERATION_DEPRECATION: String =
    "Single-operation shortcuts are replaced by the navigation { } DSL, which is the one way to navigate. " +
        "The IDE can apply the replacement."

private const val NAVIGATION_IMPORT: String = "io.github.syrou.reaktiv.navigation.extension.navigation"


