package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.definition.DismissAction
import io.github.syrou.reaktiv.navigation.definition.DismissSource
import io.github.syrou.reaktiv.navigation.util.canHandleBack
import io.github.syrou.reaktiv.navigation.util.dismissableBoundary
import io.github.syrou.reaktiv.navigation.extension.navigation
import kotlinx.coroutines.flow.first

@Composable
internal expect fun PlatformBackHandler(
    enabled: Boolean,
    coordinator: PlatformBackCoordinator
)

@Composable
internal expect fun platformEdgeSwipeBackEnabled(): Boolean

internal class PlatformBackCoordinator(
    private val store: Store,
    private val navModule: NavigationModule,
    private val controller: InteractiveTransitionController,
    private val stateProvider: () -> NavigationState
) {
    private var scrubTop: NavigationEntry? = null
    private var scrubRevealed: NavigationEntry? = null

    fun startScrub(): Boolean {
        val arming = armContentBack(stateProvider(), navModule, controller) ?: return false
        if (!controller.beginScrub(arming.kind)) return false
        scrubTop = arming.top
        scrubRevealed = arming.revealed
        return true
    }

    fun progress(value: Float) {
        controller.scrubTo(value)
    }

    suspend fun commit() {
        val top = scrubTop
        val revealed = scrubRevealed
        scrubTop = null
        scrubRevealed = null
        if (top != null && revealed != null) {
            completeInteractiveDismiss(
                commit = true,
                progressVelocity = 0f,
                controller = controller,
                store = store,
                navModule = navModule,
                top = top,
                revealed = revealed
            )
        } else {
            dispatchBackDismissal(store, navModule)
        }
    }

    suspend fun cancel() {
        val top = scrubTop
        val revealed = scrubRevealed
        scrubTop = null
        scrubRevealed = null
        if (top != null && revealed != null) {
            completeInteractiveDismiss(
                commit = false,
                progressVelocity = 0f,
                controller = controller,
                store = store,
                navModule = navModule,
                top = top,
                revealed = revealed
            )
        }
    }
}

/**
 * Commits a gesture by unwinding to [revealed], the entry the gesture was already animating toward.
 *
 * The target decides what this means rather than the boundary being resolved again here. An edge
 * swipe inside a presented graph reveals the previous step, so it pops one entry; a drag on the
 * same screen reveals what sits beneath the whole graph, so it unwinds past every entry the graph
 * owns. Deriving it a second time is what made a back swipe behave like a dismissal, because both
 * gestures commit through this one function.
 *
 * The graph's own dismiss handler applies only when the gesture actually leaves the graph, since
 * stepping backwards inside it is not the graph being discarded.
 *
 * @param expectedTopKey Guards against committing a stale pop
 */
internal suspend fun DismissAction.perform(store: StoreAccessor, pop: suspend () -> Unit) {
    when (this) {
        DismissAction.Pop -> pop()
        DismissAction.Ignore -> Unit
        is DismissAction.Run -> handler(store)
    }
}

internal suspend fun dismissSurface(
    store: Store,
    navModule: NavigationModule,
    top: NavigationEntry,
    revealed: NavigationEntry?,
    source: DismissSource,
    expectedTopKey: String? = null
) {
    val boundary = dismissableBoundary(top, navModule)
    val leavesGraph = boundary != null &&
        revealed != null &&
        dismissableBoundary(revealed, navModule) != boundary

    val screenAction = top.navigatable.dismissal[source]
    val action = if (leavesGraph) {
        val graphAction = navModule.getGraphDefinitions()[boundary]?.declaration?.dismissal?.get(source)
        when {
            graphAction != null && graphAction !is DismissAction.Pop -> graphAction
            screenAction is DismissAction.Run -> screenAction
            else -> DismissAction.Pop
        }
    } else {
        screenAction
    }
    action.perform(store) {
        if (leavesGraph && revealed != null) {
            store.navigation {
                popUpTo(revealed.path, inclusive = false)
            }
        } else {
            store.navigateBack(expectedTopKey = expectedTopKey)
        }
    }
}

internal suspend fun dispatchBackDismissal(store: Store, navModule: NavigationModule) {
    val state = store.selectState<NavigationState>().first()
    if (!canHandleBack(state)) return
    state.currentEntry.navigatable.dismissal[DismissSource.Back].perform(store) {
        store.navigateBack()
    }
}
