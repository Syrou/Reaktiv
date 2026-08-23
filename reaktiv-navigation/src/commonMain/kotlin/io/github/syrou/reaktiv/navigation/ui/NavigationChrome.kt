package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import kotlinx.coroutines.launch

/**
 * Everything a navigation header needs, derived from the current navigation state.
 *
 * Obtained from [rememberNavigationChrome]. Reaktiv supplies the values and the back action, the
 * caller supplies the bar, so the same facts can drive a Material app bar, an iOS styled one, or
 * anything else.
 *
 * ```kotlin
 * val chrome = rememberNavigationChrome()
 * TopAppBar(
 *     title = { Text(chrome.title.orEmpty()) },
 *     navigationIcon = {
 *         chrome.onBack?.let { back -> IconButton(back) { Icon(ArrowBack, null) } }
 *     }
 * )
 * ```
 *
 * @see rememberNavigationChrome
 */
public class NavigationChromeState internal constructor(
    /** Route of the destination the header describes. */
    public val route: String,
    /** Resolved title of the current destination, or null when it declares none. */
    public val title: String?,
    /** Resolved title of the destination a back navigation would reveal, or null at the root. */
    public val backTitle: String?,
    /**
     * Performs a back navigation, or null when there is nowhere to go.
     *
     * Routes through the same path as the system back button and the edge swipe, so a header
     * cannot disagree with them about what leaving means inside a presented graph.
     */
    public val onBack: (() -> Unit)?
) {
    /** Whether a back navigation is possible from here. */
    public val canGoBack: Boolean get() = onBack != null
}

/**
 * Title of the entry that a back navigation would reveal, or null at the root of the stack.
 *
 * Reads the same `titleResource` the current title does, so a title declared once serves both the
 * screen and any affordance pointing back at it. System and overlay entries are skipped, because a
 * back control names the content you would return to rather than whatever is layered above it.
 */
@Composable
public fun previousTitle(): String? {
    val navigationState by composeState<NavigationState>()
    val content = navigationState.backStack.filter {
        it.navigatable.renderLayer == RenderLayer.CONTENT
    }
    if (content.size < 2) return null
    return content[content.lastIndex - 1].titleResource?.invoke()
}

/**
 * Derives the values a navigation header needs from the current navigation state.
 *
 * Titles resolve in composition, so a `titleResource` built from `stringResource` is localised
 * exactly as it is anywhere else. The title follows an in flight gesture, because it reads the same
 * perceived entry the renderer does.
 *
 * ```kotlin
 * Scaffold(topBar = {
 *     if (navigationState.showsNavigationChrome) {
 *         val chrome = rememberNavigationChrome()
 *         TopAppBar(title = { Text(chrome.title.orEmpty()) })
 *     }
 * }) { ... }
 * ```
 *
 * @see NavigationChromeState
 */
@Composable
public fun rememberNavigationChrome(): NavigationChromeState {
    val store = rememberStore()
    val scope = rememberCoroutineScope()
    val navigationState by composeState<NavigationState>()
    val route = navigationState.currentEntry.route
    val title = currentTitle()
    val backTitle = previousTitle()
    val canGoBack = navigationState.canGoBack

    return remember(route, title, backTitle, canGoBack) {
        NavigationChromeState(
            route = route,
            title = title,
            backTitle = backTitle,
            onBack = if (canGoBack) ({ scope.launch { store.navigateBack() } }) else null
        )
    }
}

/**
 * Space the navigation header occupies, for screens that render beneath it.
 *
 * Reaktiv declares this so a screen and the layout that frames it can agree without depending on
 * each other, which matters when they live in different modules. Whatever draws the header provides
 * the value, and it is zero wherever no header is shown.
 *
 * ```kotlin
 * LazyColumn(contentPadding = LocalNavigationChromeInsets.current) { ... }
 * ```
 */
public val LocalNavigationChromeInsets: ProvidableCompositionLocal<PaddingValues> =
    staticCompositionLocalOf { PaddingValues(0.dp) }
