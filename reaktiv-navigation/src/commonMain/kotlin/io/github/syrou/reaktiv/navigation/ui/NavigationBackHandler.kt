package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.DismissSource
import io.github.syrou.reaktiv.navigation.util.performUserBack

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
        } else {
            performUserBack(store, navModule, DismissSource.Back)
        }
    }

    suspend fun cancel() {
        if (release()) completeInteractiveDismiss(false, 0f, controller, store, navModule)
    }

    private fun release(): Boolean = ownsScrub.also { ownsScrub = false }
}
