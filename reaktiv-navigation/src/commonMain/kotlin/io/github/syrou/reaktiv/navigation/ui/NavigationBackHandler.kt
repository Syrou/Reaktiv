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
            completeContentGesture(
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
            completeContentGesture(
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

internal suspend fun dispatchBackDismissal(store: Store, navModule: NavigationModule) {
    val state = store.selectState<NavigationState>().first()
    if (!canHandleBack(state, navModule)) return
    val handler = navModule.resolveNavigatable(state.currentEntry)?.onDismissRequest
    if (handler != null) {
        handler.invoke(store)
    } else {
        store.navigateBack()
    }
}
