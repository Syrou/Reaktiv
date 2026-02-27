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

internal val LocalStore = staticCompositionLocalOf<Store> {
    error("You need to wrap your Composable in StoreProvider and provide a store")
}

/**
 * Retrieves the Store from the CompositionLocal provided by StoreProvider.
 *
 * Example:
 * ```kotlin
 * @Composable
 * fun MyComponent() {
 *     val store = rememberStore()
 *     val scope = rememberCoroutineScope()
 *
 *     Button(onClick = {
 *         scope.launch {
 *             store.navigation { navigateTo("profile") }
 *         }
 *     }) {
 *         Text("Go to Profile")
 *     }
 * }
 * ```
 *
 * @return The Store instance from the current composition
 * @throws IllegalStateException if called outside of a StoreProvider
 */
@Composable
fun rememberStore(): Store {
    return LocalStore.current
}

/**
 * Provides access to the store's dispatch function for firing actions.
 *
 * Example:
 * ```kotlin
 * @Composable
 * fun CounterButtons() {
 *     val dispatch = rememberDispatcher()
 *
 *     Row {
 *         Button(onClick = { dispatch(CounterAction.Increment) }) {
 *             Text("Increment")
 *         }
 *         Button(onClick = { dispatch(CounterAction.Decrement) }) {
 *             Text("Decrement")
 *         }
 *     }
 * }
 * ```
 *
 * @return The dispatch function from the store
 */
@Composable
fun rememberDispatcher(): Dispatch {
    val store = rememberStore()
    return remember { store.dispatch }
}


/**
 * Provides the Reaktiv store to the Compose hierarchy via CompositionLocal.
 *
 * Wrap your app's root composable with StoreProvider to make the store
 * accessible to all child composables.
 *
 * Example:
 * ```kotlin
 * @Composable
 * fun App(store: Store) {
 *     StoreProvider(store) {
 *         NavigationRender(modifier = Modifier.fillMaxSize())
 *     }
 * }
 * ```
 *
 * @param store The Reaktiv Store instance
 * @param content The composable content that will have access to the store
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
 * Selects a module's state as a StateFlow with an initial value for previews.
 *
 * @param initialValue The initial state value to use before the store is ready
 * @return StateFlow of the requested state type
 */
@Composable
inline fun <reified S : ModuleState> selectState(initialValue: S): StateFlow<S> {
    val store = rememberStore()
    val stateFlow by produceState<StateFlow<S>>(initialValue = MutableStateFlow(initialValue)) {
        value = store.selectStateNonSuspend<S>()
    }
    return stateFlow
}

/**
 * Selects a module's state as a StateFlow.
 *
 * For most use cases, prefer using composeState() which returns a Compose State
 * that automatically triggers recomposition.
 *
 * @return StateFlow of the requested state type
 */
@Composable
inline fun <reified S : ModuleState> selectState(): StateFlow<S> {
    val store = rememberStore()
    return remember { store.selectStateNonSuspend<S>() }
}

/**
 * Observes a module's state as a Compose State with an initial value for previews.
 *
 * The returned State automatically updates when the module state changes,
 * triggering recomposition of any composables that read from it.
 *
 * Example:
 * ```kotlin
 * @Composable
 * fun CounterDisplayPreview() {
 *     val state by composeState<CounterState>(initialValue = CounterState(count = 42))
 *     Text("Count: ${state.count}")
 * }
 * ```
 *
 * @param initialValue The initial state value to use before the store is ready
 * @return Compose State of the requested state type
 */
@Composable
inline fun <reified S : ModuleState> composeState(initialValue: S): State<S> {
    return selectState<S>(initialValue).collectAsState(Dispatchers.Main.immediate)
}

/**
 * Observes a module's state as a Compose State.
 *
 * This is the primary API for observing module state in Composables. The returned
 * State automatically updates when the module state changes, triggering recomposition.
 *
 * Example:
 * ```kotlin
 * @Composable
 * fun CounterDisplay() {
 *     val state by composeState<CounterState>()
 *     Text("Count: ${state.count}")
 * }
 * ```
 *
 * @return Compose State of the requested state type
 */
@Composable
inline fun <reified S : ModuleState> composeState(): State<S> {
    return selectState<S>().collectAsState(Dispatchers.Main.immediate)
}

/**
 * Watches a selected value from state and triggers a callback when it changes.
 *
 * Use this for side effects like analytics tracking or navigation that should
 * happen in response to state changes while the composable is active.
 *
 * Example:
 * ```kotlin
 * @Composable
 * fun AnalyticsTracker() {
 *     onActiveValueChange<NavigationState, String>(
 *         selector = { it.currentEntry.navigatable.route }
 *     ) { route ->
 *         analytics.trackScreenView(route)
 *     }
 * }
 * ```
 *
 * @param selector Function to extract the value to watch from the state
 * @param onChange Callback invoked when the selected value changes
 */
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