package io.github.syrou.reaktiv.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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

/**
 * Remembers and provides the current [Store] instance.
 *
 * This composable function should be used within a [StoreProvider] to access the current [Store].
 *
 * @return The current [Store] instance.
 *
 * @throws IllegalStateException if used outside of a [StoreProvider].
 *
 * Example usage:
 * ```
 * @Composable
 * fun MyComposable() {
 *     StoreProvider(store = myStore) {
 *         val store = rememberStore()
 *         // Use the store...
 *     }
 * }
 * ```
 */
@Composable
fun rememberStore(): Store {
    return LocalStore.current
}

/**
 * Remembers and provides the dispatcher function from the current [Store].
 *
 * This composable function should be used within a [StoreProvider] to access the dispatcher.
 *
 * @return The [Dispatch] function from the current [Store].
 *
 * @throws IllegalStateException if used outside of a [StoreProvider].
 *
 * Example usage:
 * ```
 * @Composable
 * fun MyComposable() {
 *     StoreProvider(store = myStore) {
 *         val dispatch = rememberDispatcher()
 *         Button(onClick = { dispatch(MyAction) }) {
 *             Text("Dispatch Action")
 *         }
 *     }
 * }
 * ```
 */
@Composable
fun rememberDispatcher(): Dispatch {
    val store = rememberStore()
    return remember { store.dispatcher }
}

/**
 * Provides a [Store] instance to its content and descendants in the composition.
 *
 * @param store The [Store] instance to be provided.
 * @param content The composable content where the [Store] will be available.
 *
 * Example usage:
 * ```
 * @Composable
 * fun MyApp() {
 *     val store = createStore() // Assume this function creates and returns a Store
 *     StoreProvider(store = store) {
 *         // Content that needs access to the store
 *         MyScreen()
 *     }
 * }
 *
 * @Composable
 * fun MyScreen() {
 *     val store = rememberStore()
 *     // Use the store...
 * }
 * ```
 */
@Composable
fun StoreProvider(
    store: Store,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalStore provides store) {
        content()
    }
}

/**
 * Selects and provides a [StateFlow] of a specific [ModuleState] from the current [Store].
 *
 * @param S The type of [ModuleState] to be selected.
 * @return A [StateFlow] of the selected [ModuleState].
 *
 * @throws IllegalStateException if used outside of a [StoreProvider].
 *
 * Example usage:
 * ```
 * @Composable
 * fun CounterScreen() {
 *     val counterState by selectState<CounterState>().collectAsState()
 *
 *     Text("Count: ${counterState.count}")
 * }
 * ```
 */
@Composable
inline fun <reified S : ModuleState> selectState(): StateFlow<S> {
    val store = rememberStore()
    return remember { store.selectState<S>() }
}

/**
 * Selects and provides a specific [ModuleLogic] from the current [Store].
 *
 * @param L The type of [ModuleLogic] to be selected.
 * @return The selected [ModuleLogic] instance.
 *
 * @throws IllegalStateException if used outside of a [StoreProvider].
 *
 * Example usage:
 * ```
 * @Composable
 * fun CounterButtons() {
 *     val counterLogic = selectLogic<CounterLogic>()
 *     val dispatch = rememberDispatcher()
 *
 *     Row {
 *         Button(onClick = { dispatch(counterLogic.increment()) }) {
 *             Text("+")
 *         }
 *         Button(onClick = { dispatch(counterLogic.decrement()) }) {
 *             Text("-")
 *         }
 *     }
 * }
 * ```
 */
@Composable
inline fun <reified L : ModuleLogic<out ModuleAction>> selectLogic(): L {
    val store = rememberStore()
    return store.selectLogic<L>()
}