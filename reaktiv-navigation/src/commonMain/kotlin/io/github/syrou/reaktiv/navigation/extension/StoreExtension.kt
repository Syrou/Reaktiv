package io.github.syrou.reaktiv.navigation.extension

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.ClearBackStackBuilder
import io.github.syrou.reaktiv.navigation.NavigationBuilder
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.PopUpToBuilder

fun StoreAccessor.navigate(
    route: String,
    params: Map<String, Any> = emptyMap(),
    config: (NavigationBuilder.() -> Unit)? = null
) {
    this.dispatch
    this.selectLogic<NavigationLogic>().navigate(route, params, config)
}

fun StoreAccessor.popUpTo(
    route: String,
    inclusive: Boolean = false,
    config: (PopUpToBuilder.() -> Unit)? = null
) {
    selectLogic<NavigationLogic>().popUpTo(route, inclusive, config)
}

fun StoreAccessor.navigateBack() {
    selectLogic<NavigationLogic>().navigateBack()
}

fun StoreAccessor.clearCurrentScreenParams() {
    selectLogic<NavigationLogic>().clearCurrentScreenParams()
}

fun StoreAccessor.clearCurrentScreenParam(key: String) {
    selectLogic<NavigationLogic>().clearCurrentScreenParam(key)
}

fun StoreAccessor.clearScreenParams(route: String) {
    selectLogic<NavigationLogic>().clearScreenParams(route)
}

fun StoreAccessor.clearScreenParam(route: String, key: String) {
    selectLogic<NavigationLogic>().clearScreenParam(route, key)
}

fun StoreAccessor.clearBackStack(config: (ClearBackStackBuilder.() -> Unit)? = null) {
    selectLogic<NavigationLogic>().clearBackStack(config)
}

fun StoreAccessor.replaceWith(route: String, params: Map<String, Any> = emptyMap()) {
    selectLogic<NavigationLogic>().replaceWith(route, params)
}

fun StoreAccessor.navigateWithValidation(
    route: String,
    params: Map<String, Any> = emptyMap(),
    validate: suspend (StoreAccessor, Map<String, Any>) -> Boolean
) {
    selectLogic<NavigationLogic>().navigateWithValidation(route, params, this, validate)
}