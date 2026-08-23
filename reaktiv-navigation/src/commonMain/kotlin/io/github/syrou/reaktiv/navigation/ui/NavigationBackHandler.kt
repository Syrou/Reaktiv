package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.util.canArmInteractiveBackGesture
import io.github.syrou.reaktiv.navigation.util.canHandleBack
import io.github.syrou.reaktiv.navigation.util.dismissableBoundary
import io.github.syrou.reaktiv.navigation.util.revealedEntryForDismiss
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.util.revealedEntryForBack
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
        val state = stateProvider()
        if (!canArmInteractiveBackGesture(state, navModule)) return false
        if (controller.contentTransitionActive) return false
        val top = state.currentEntry
        val revealed = revealedEntryForBack(state) ?: return false
        val kind = InteractiveTransitionController.ScrubKind.ContentBack(top, revealed)
        if (!controller.beginScrub(kind)) return false
        scrubTop = top
        scrubRevealed = revealed
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
internal suspend fun dismissSurface(
    store: Store,
    navModule: NavigationModule,
    top: NavigationEntry,
    revealed: NavigationEntry?,
    expectedTopKey: String? = null
) {
    val boundary = dismissableBoundary(top, navModule)
    val leavesGraph = boundary != null &&
        revealed != null &&
        dismissableBoundary(revealed, navModule) != boundary

    val graphHandler = if (leavesGraph) {
        navModule.getGraphDefinitions()[boundary]?.declaration?.onDismissRequest
    } else {
        null
    }
    val handler = graphHandler ?: top.navigatable.onDismissRequest
    if (handler != null) {
        handler.invoke(store)
        return
    }

    if (leavesGraph && revealed != null) {
        store.navigation {
            popUpTo(revealed.path, inclusive = false)
        }
    } else {
        store.navigateBack(expectedTopKey = expectedTopKey)
    }
}

internal suspend fun dispatchBackDismissal(store: Store, navModule: NavigationModule) {
    val state = store.selectState<NavigationState>().first()
    if (!canHandleBack(state, navModule)) return
    val handler = state.currentEntry.navigatable.onDismissRequest
    if (handler != null) {
        handler.invoke(store)
    } else {
        store.navigateBack()
    }
}
