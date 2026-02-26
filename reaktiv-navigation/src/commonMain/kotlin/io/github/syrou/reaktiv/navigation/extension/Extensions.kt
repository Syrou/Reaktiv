package io.github.syrou.reaktiv.navigation.extension

import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.util.getNavigationModule
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.param.Params
import kotlinx.coroutines.flow.first

suspend fun StoreAccessor.navigation(block: suspend NavigationBuilder.() -> Unit) {
    val navigationLogic = selectLogic<NavigationLogic>()
    navigationLogic.navigate(block)
}

suspend fun StoreAccessor.navigateBack() {
    selectLogic<NavigationLogic>().navigateBack()
}

suspend fun StoreAccessor.navigate(route: String, params: Params? = null) {
    navigation {
        navigateTo(route) {
            params?.let { params(it) }
        }
    }
}

suspend inline fun <reified T : Navigatable> StoreAccessor.navigate(params: Params? = null) {
    navigation {
        navigateTo<T> {
            params?.let { params(it) }
        }
    }
}

suspend inline fun <reified T : Modal> StoreAccessor.presentModal(
    noinline config: (suspend NavigationBuilder.() -> Unit)? = null
) {
    navigation {
        navigateTo<T>()
        config?.invoke(this)
    }
}

suspend fun StoreAccessor.dismissModal() {
    navigateBack()
}

inline fun Modifier.applyIf(condition: Boolean, modifier: Modifier.() -> Modifier): Modifier {
    return if (condition) {
        then(modifier(Modifier))
    } else {
        this
    }
}

suspend fun StoreAccessor.dismissModal(modalEntry: NavigationEntry) {
    val navModule = getNavigationModule()
    if (navModule.resolveNavigatable(modalEntry) is Modal) {
        selectLogic<NavigationLogic>().popUpTo(modalEntry.path, inclusive = true)
    }
}

/**
 * Navigate to a deep link route, resolving any registered aliases first.
 *
 * @param route The deep link path (may include query parameters)
 * @param params Additional parameters merged with any extracted from the route
 */
suspend fun StoreAccessor.navigateDeepLink(route: String, params: Params = Params.empty()) {
    selectLogic<NavigationLogic>().navigateDeepLink(route, params)
}

suspend fun StoreAccessor.clearAllModals() {
    val navigationState = selectState<NavigationState>().first()
    val navModule = getNavigationModule()
    val lastScreen = navigationState.orderedBackStack.reversed().firstOrNull {
        navModule.resolveNavigatable(it) is Screen
    }

    if (lastScreen != null) {
        val navigationLogic = selectLogic<NavigationLogic>()
        navigationLogic.popUpTo(lastScreen.path, inclusive = false)
    }
}

/**
 * Resume a pending navigation stored by [GuardResult.PendAndRedirectTo].
 *
 * Clears [NavigationState.pendingNavigation] and navigates to the stored route.
 * No-op if there is no pending navigation.
 */
suspend fun StoreAccessor.resumePendingNavigation() {
    selectLogic<NavigationLogic>().resumePendingNavigation()
}


