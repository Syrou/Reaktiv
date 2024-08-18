package io.github.syrou.reaktiv.navigation.extension

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.NavigationBuilder
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.PopUpToBuilder

fun StoreAccessor.navigate(
    route: String,
    params: Map<String, Any> = emptyMap(),
    config: (NavigationBuilder.() -> Unit)? = null
) {
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

fun StoreAccessor.clearBackStack() {
    selectLogic<NavigationLogic>().clearBackStack()
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