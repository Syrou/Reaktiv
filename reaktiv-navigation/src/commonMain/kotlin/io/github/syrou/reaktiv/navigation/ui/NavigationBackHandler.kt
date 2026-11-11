package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.DismissSource
import io.github.syrou.reaktiv.navigation.util.canHandleBack
import io.github.syrou.reaktiv.navigation.util.performUserBack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal expect fun PlatformBackHandler(
    enabled: Boolean,
    coordinator: PlatformBackCoordinator
)


internal class PlatformBackCoordinator(
    private val store: Store,
    private val navModule: NavigationModule,
    private val controller: InteractiveTransitionController,
    private val stateProvider: () -> NavigationState
) {
    private var ownsScrub = false

    fun startScrub(): Boolean {
        val kind = armContentBack(stateProvider(), navModule, controller) ?: return false
        if (!controller.beginScrub(kind)) return false
        ownsScrub = true
        return true
    }

    fun progress(value: Float) {
        controller.scrubTo(value)
    }

    suspend fun commit() {
        if (release()) {
            completeInteractiveDismiss(true, 0f, controller, store, navModule)
            return
        }
        val state = store.selectState<NavigationState>().first()
        val interceptor = controller.backInterceptors.activeFor(state)
        if (interceptor != null) {
            interceptor.onBack()
        } else if (canHandleBack(state)) {
            performUserBack(store, navModule, state.currentEntry, state.revealedEntry, DismissSource.Back)
        }
    }

    suspend fun cancel() {
        if (release()) completeInteractiveDismiss(false, 0f, controller, store, navModule)
    }

    private fun release(): Boolean = ownsScrub.also { ownsScrub = false }
}

@Composable
internal fun NavigationEventBackHandler(
    enabled: Boolean,
    coordinator: PlatformBackCoordinator
) {
    val owner = LocalNavigationEventDispatcherOwner.current ?: return
    val scope = rememberCoroutineScope()
    val handler = remember(coordinator, scope) { CoordinatorBackHandler(coordinator, scope) }
    SideEffect { handler.isBackEnabled = enabled }
    DisposableEffect(owner, handler) {
        owner.navigationEventDispatcher.addHandler(handler)
        onDispose { handler.remove() }
    }
}

private class CoordinatorBackHandler(
    private val coordinator: PlatformBackCoordinator,
    private val scope: CoroutineScope
) : NavigationEventHandler<NavigationEventInfo.None>(NavigationEventInfo.None, isBackEnabled = false) {

    private var scrubbing = false

    override fun onBackStarted(event: NavigationEvent) {
        follow(event)
    }

    override fun onBackProgressed(event: NavigationEvent) {
        follow(event)
    }

    override fun onBackCompleted() {
        scrubbing = false
        scope.launch { withContext(NonCancellable) { coordinator.commit() } }
    }

    override fun onBackCancelled() {
        scrubbing = false
        scope.launch { withContext(NonCancellable) { coordinator.cancel() } }
    }

    private fun follow(event: NavigationEvent) {
        if (!scrubbing) {
            scrubbing = coordinator.startScrub()
        }
        if (scrubbing) {
            coordinator.progress(event.progress)
        }
    }
}
