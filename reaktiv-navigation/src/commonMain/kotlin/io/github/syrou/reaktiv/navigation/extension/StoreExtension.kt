package io.github.syrou.reaktiv.navigation.extension

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.ClearBackStackBuilder
import io.github.syrou.reaktiv.navigation.NavigationBuilder
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.PopUpToBuilder
import kotlinx.coroutines.coroutineScope


suspend fun StoreAccessor.navigate(
    route: String,
    params: Map<String, Any> = emptyMap(),
    config: (NavigationBuilder.() -> Unit)? = null
) = coroutineScope {
    selectLogic<NavigationLogic>().navigate(route, params, config)
}

suspend fun StoreAccessor.popUpTo(
    route: String,
    inclusive: Boolean = false,
    config: (PopUpToBuilder.() -> Unit)? = null
) = coroutineScope {
    selectLogic<NavigationLogic>().popUpTo(route, inclusive, config)
}

suspend fun StoreAccessor.navigateBack() = coroutineScope {
    selectLogic<NavigationLogic>().navigateBack()
}

suspend fun StoreAccessor.clearCurrentScreenParams() = coroutineScope {
    selectLogic<NavigationLogic>().clearCurrentScreenParams()
}

suspend fun StoreAccessor.clearCurrentScreenParam(key: String) = coroutineScope {
    selectLogic<NavigationLogic>().clearCurrentScreenParam(key)
}

suspend fun StoreAccessor.clearScreenParams(route: String) = coroutineScope {
    selectLogic<NavigationLogic>().clearScreenParams(route)
}

suspend fun StoreAccessor.clearScreenParam(route: String, key: String) = coroutineScope {
    selectLogic<NavigationLogic>().clearScreenParam(route, key)
}

suspend fun StoreAccessor.clearBackStack(config: (ClearBackStackBuilder.() -> Unit)? = null) = coroutineScope {
    selectLogic<NavigationLogic>().clearBackStack(config)
}


suspend fun StoreAccessor.replaceWith(route: String, params: Map<String, Any> = emptyMap()) = coroutineScope {
    selectLogic<NavigationLogic>().replaceWith(route, params)
}

suspend fun StoreAccessor.navigateWithValidation(
    route: String,
    params: Map<String, Any> = emptyMap(),
    validate: suspend (StoreAccessor, Map<String, Any>) -> Boolean
) = coroutineScope {
    selectLogic<NavigationLogic>().navigateWithValidation(route, params, this@navigateWithValidation, validate)
}

