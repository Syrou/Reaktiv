package io.github.syrou.reaktiv.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.syrou.reaktiv.core.Dispatch
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.Store
import kotlinx.coroutines.flow.StateFlow

private val LocalStore = staticCompositionLocalOf<Store> {
    error("You need to wrap your Preview Composable in StoreProvider and assign a  store")
}

@Composable
fun rememberStore(): Store {
    return LocalStore.current
}

@Composable
fun rememberDispatcher(): Dispatch {
    val store = rememberStore()
    return store.dispatcher
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
    return store.selectState<S>()
}

@Composable
inline fun <reified L : ModuleLogic<out ModuleAction>> selectLogic(): L {
    val store = rememberStore()
    return store.selectLogic<L>()
}