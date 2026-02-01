package io.github.syrou.reaktiv.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import io.github.syrou.reaktiv.core.ModuleState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Creates derived state from a module state using a selector function.
 *
 * This is useful when you only need a subset of the state. The composable will
 * only recompose when the selected value changes, not when other parts of the
 * state change.
 *
 * Example:
 * ```kotlin
 * @Composable
 * fun TodoCount() {
 *     // Only recomposes when the count changes, not when other TodoState fields change
 *     val count by select<TodoState, Int> { state -> state.items.size }
 *     Text("$count items")
 * }
 *
 * // With custom equality
 * @Composable
 * fun UserDisplay() {
 *     val userName by select<UserState, String>(
 *         selector = { it.user?.name ?: "Guest" },
 *         areEqual = { old, new -> old == new }
 *     )
 *     Text("Hello, $userName")
 * }
 * ```
 *
 * @param selector Function to extract the derived value from the state
 * @param areEqual Custom equality function for comparing values (defaults to ==)
 * @return Compose State of the derived value type
 */
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