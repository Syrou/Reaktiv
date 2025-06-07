package io.github.syrou.reaktiv.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import io.github.syrou.reaktiv.core.ModuleState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
inline fun <reified T, R> select(
    crossinline selector: (T) -> R,
    noinline areEqual: (R, R) -> Boolean = { old, new -> old == new }
): State<R> where T : ModuleState {
    val store = rememberStore()
    val stateFlow = store.selectStateNonSuspend<T>()

    return remember {
        stateFlow.map(selector).distinctUntilChanged(areEqual)
    }.collectAsState(selector(stateFlow.value))
}