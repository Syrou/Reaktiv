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
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.util.RouteResolver
import io.github.syrou.reaktiv.navigation.util.parseUrlWithQueryParams
import kotlinx.coroutines.flow.first




suspend fun StoreAccessor.navigation(block: suspend NavigationBuilder.() -> Unit) {
    val navigationLogic = selectLogic<NavigationLogic>()
    navigationLogic.navigate(block)
}







suspend fun StoreAccessor.navigateBack() {
    navigation {
        navigateBack()
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