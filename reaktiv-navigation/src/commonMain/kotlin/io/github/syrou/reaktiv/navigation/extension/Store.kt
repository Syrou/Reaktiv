package io.github.syrou.reaktiv.navigation.extension

import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.navigation.NavigationBuilder
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.PopUpToBuilder
import io.github.syrou.reaktiv.navigation.Screen

fun Store.navigate(
    route: String,
    params: Map<String, Any> = emptyMap(),
    config: (NavigationBuilder.() -> Unit)? = null
) {
    selectLogic<NavigationLogic>().navigate(route, params, config)
}

fun Store.navigate(
    screen: Screen,
    params: Map<String, Any> = emptyMap(),
    config: (NavigationBuilder.() -> Unit)? = null
) {
    navigate(screen.route, params, config)
}

fun Store.popUpTo(
    route: String,
    inclusive: Boolean = false,
    config: (PopUpToBuilder.() -> Unit)? = null
) {
    selectLogic<NavigationLogic>().popUpTo(route, inclusive, config)
}

fun Store.navigateBack() {
    selectLogic<NavigationLogic>().navigateBack()
}

fun Store.clearBackStack() {
    selectLogic<NavigationLogic>().clearBackStack()
}

fun Store.replaceWith(route: String, params: Map<String, Any> = emptyMap()) {
    selectLogic<NavigationLogic>().replaceWith(route, params)
}

fun Store.navigateWithValidation(
    route: String,
    params: Map<String, Any> = emptyMap(),
    validate: suspend (Store, Map<String, Any>) -> Boolean
) {
    selectLogic<NavigationLogic>().navigateWithValidation(route, params, this, validate)
}

fun Store.navigateWithValidation(
    screen: Screen,
    params: Map<String, Any> = emptyMap(),
    validate: suspend (Store, Map<String, Any>) -> Boolean
) {
    navigateWithValidation(screen.route, params, validate)
}