package io.github.syrou.reaktiv.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.syrou.reaktiv.core.Dispatch
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
    return remember { store.dispatch }
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
inline fun <reified S : ModuleState> selectState(initialValue: S): StateFlow<S> {
    val store = rememberStore()
    val stateFlow by produceState<StateFlow<S>>(initialValue = MutableStateFlow(initialValue)) {
        value = store.selectStateNonSuspend<S>()
    }
    return stateFlow
}

@Composable
inline fun <reified S : ModuleState> selectState(): StateFlow<S> {
    val store = rememberStore()
    return remember { store.selectStateNonSuspend<S>() }
}

@Composable
inline fun <reified S : ModuleState> composeState(initialValue: S): State<S> {
    return selectState<S>(initialValue).collectAsState(Dispatchers.Main.immediate)
}

@Composable
inline fun <reified S : ModuleState> composeState(): State<S> {
    return selectState<S>().collectAsState(Dispatchers.Main.immediate)
}

@Composable
inline fun <reified S : ModuleState, T> onActiveValueChange(
    crossinline selector: (S) -> T,
    crossinline onChange: suspend (T) -> Unit
) {
    val state by composeState<S>()
    val selectedValue = selector(state)

    val isActive = remember { mutableStateOf(true) }
    val previousValue = remember { mutableStateOf<T?>(null) }

    DisposableEffect(Unit) {
        isActive.value = true
        onDispose {
            isActive.value = false
        }
    }

    LaunchedEffect(selectedValue) {
        if (isActive.value && previousValue.value != null && previousValue.value != selectedValue) {
            onChange(selectedValue)
        }
        previousValue.value = selectedValue
    }
}