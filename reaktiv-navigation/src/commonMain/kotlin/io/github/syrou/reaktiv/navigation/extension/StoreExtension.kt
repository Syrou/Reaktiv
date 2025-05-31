package io.github.syrou.reaktiv.navigation.extension

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.dsl.ClearBackStackBuilder
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.dsl.PopUpToBuilder
import io.github.syrou.reaktiv.navigation.dsl.TypeSafeParameterBuilder
import kotlinx.coroutines.coroutineScope


suspend fun StoreAccessor.navigate(
    route: String,
    params: Map<String, Any> = emptyMap(),
    config: (NavigationBuilder.() -> Unit)? = null
) {
    selectLogic<NavigationLogic>().navigate(route, params, config)
}

suspend fun StoreAccessor.popUpTo(
    route: String,
    inclusive: Boolean = false,
    config: (PopUpToBuilder.() -> Unit)? = null
) {
    selectLogic<NavigationLogic>().popUpTo(route, inclusive, config)
}

suspend fun StoreAccessor.navigateBack() {
    selectLogic<NavigationLogic>().navigateBack()
}

suspend fun StoreAccessor.clearCurrentScreenParams() {
    selectLogic<NavigationLogic>().clearCurrentScreenParams()
}

suspend fun StoreAccessor.clearCurrentScreenParam(key: String) {
    selectLogic<NavigationLogic>().clearCurrentScreenParam(key)
}

suspend fun StoreAccessor.clearScreenParams(route: String) {
    selectLogic<NavigationLogic>().clearScreenParams(route)
}

suspend fun StoreAccessor.clearScreenParam(route: String, key: String) {
    selectLogic<NavigationLogic>().clearScreenParam(route, key)
}

suspend fun StoreAccessor.clearBackStack(config: (ClearBackStackBuilder.() -> Unit)? = null) {
    selectLogic<NavigationLogic>().clearBackStack(config)
}

suspend fun StoreAccessor.replaceWith(route: String, params: Map<String, Any> = emptyMap()) {
    selectLogic<NavigationLogic>().replaceWith(route, params)
}

suspend fun StoreAccessor.navigateWithValidation(
    route: String,
    params: Map<String, Any> = emptyMap(),
    config: (NavigationBuilder.() -> Unit)? = null,
    validate: suspend (StoreAccessor, Map<String, Any>) -> Boolean
) {
    selectLogic<NavigationLogic>().navigateWithValidation(route, params, config, validate)
}

suspend fun StoreAccessor.navigateTypeSafe(
    route: String,
    config: (NavigationBuilder.() -> Unit)? = null,
    params: TypeSafeParameterBuilder.() -> Unit = {}
) {
    selectLogic<NavigationLogic>().navigateTypeSafe(route, params, config)
}

suspend fun StoreAccessor.navigateTypeSafeWithValidation(
    route: String,
    config: (NavigationBuilder.() -> Unit)? = null,
    params: TypeSafeParameterBuilder.() -> Unit = {},
    validate: suspend (StoreAccessor, Map<String, Any>) -> Boolean
) {
    selectLogic<NavigationLogic>().navigateTypeSafeWithValidation(route, params, config, validate)
}

// Convenience extensions
suspend inline fun <reified T> StoreAccessor.navigateWith(
    route: String,
    paramName: String,
    paramValue: T,
    noinline config: (NavigationBuilder.() -> Unit)? = null
) {
    navigateTypeSafe(route, config) {
        put(paramName, paramValue)
    }
}

suspend inline fun <reified T> StoreAccessor.navigateWithData(
    route: String,
    data: T,
    noinline config: (NavigationBuilder.() -> Unit)? = null
) {
    navigateTypeSafe(route, config) {
        put("data", data)
    }
}
