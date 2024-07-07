package io.github.syrou.reaktiv.compose

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.Store
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalInspectionMode
import io.github.syrou.reaktiv.core.createStore
import kotlinx.coroutines.flow.StateFlow

private val LocalStore = staticCompositionLocalOf<Store?> {
    error("StoreProvider not found. Are you missing a StoreProvider composable?")
}

@Composable
fun rememberStore(): Store {
    val isInPreview = LocalInspectionMode.current
    val existingStore = LocalStore.current

    return remember {
        when {
            isInPreview -> createStore { }
            existingStore != null -> existingStore
            else -> error("StoreProvider not found. Are you missing a StoreProvider composable?")
        }
    }
}

@Composable
fun StoreProvider(
    store: Store,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalStore provides store) {
        content()
    }
}

@Composable
inline fun <reified S : ModuleState> selectState(): StateFlow<S> {
    val store = rememberStore()
    return remember(store) { store.selectState<S>() }
}

@Composable
inline fun <reified L : ModuleLogic<out ModuleAction>> selectLogic(): L {
    val store = rememberStore()
    return remember(store) { store.selectLogic<L>() }
}