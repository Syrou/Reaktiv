package io.github.syrou.reaktiv.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A composition local for the current navigation entry.
 * Using staticCompositionLocalOf ensures that changes to this value
 * only cause recomposition at the usage site, not for all children.
 */
val LocalNavigationEntry = staticCompositionLocalOf<NavigationEntry?> {
    null
}

/**
 * A composition local for the previous navigation entry.
 * Used for animations and transitions.
 */
val LocalPreviousNavigationEntry = staticCompositionLocalOf<NavigationEntry?> {
    null
}

/**
 * A composition local for handling nested navigation.
 */
val LocalNavigationDepth = staticCompositionLocalOf { 0 }

/**
 * Provides a navigation entry to its content and descendants in the composition.
 *
 * @param entry The current navigation entry.
 * @param previousEntry The previous navigation entry (for animations).
 * @param depth The current navigation depth.
 * @param content The composable content where the navigation entry will be available.
 */
@Composable
fun NavigationEntryProvider(
    entry: NavigationEntry,
    previousEntry: NavigationEntry? = null,
    depth: Int = 0,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalNavigationEntry provides entry,
        LocalPreviousNavigationEntry provides previousEntry,
        LocalNavigationDepth provides depth
    ) {
        content()
    }
}

/**
 * Remembers and provides the current navigation entry.
 *
 * @return The current navigation entry or null if not in a NavigationEntryProvider.
 */
@Composable
fun rememberNavigationEntry(): NavigationEntry? {
    return LocalNavigationEntry.current
}

/**
 * Remembers and provides the previous navigation entry.
 *
 * @return The previous navigation entry or null if not in a NavigationEntryProvider.
 */
@Composable
fun rememberPreviousNavigationEntry(): NavigationEntry? {
    return LocalPreviousNavigationEntry.current
}

/**
 * Remembers and provides the current navigation depth.
 *
 * @return The current navigation depth.
 */
@Composable
fun rememberNavigationDepth(): Int {
    return LocalNavigationDepth.current
}

/**
 * Tracks a navigation entry's state with previous value.
 */
@Stable
class NavigationEntryState(
    initialEntry: NavigationEntry,
    initialPreviousEntry: NavigationEntry? = null
) {
    var current by mutableStateOf(initialEntry)
    var previous by mutableStateOf(initialPreviousEntry)

    fun update(newEntry: NavigationEntry) {
        previous = current
        current = newEntry
    }
}

/**
 * Remembers a NavigationEntryState for a navigation entry.
 */
@Composable
fun rememberNavigationEntryState(
    entry: NavigationEntry,
    previousEntry: NavigationEntry? = null
): NavigationEntryState {
    return remember {
        NavigationEntryState(entry, previousEntry)
    }
}