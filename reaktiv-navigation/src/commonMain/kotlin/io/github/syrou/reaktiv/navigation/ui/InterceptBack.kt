package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.syrou.reaktiv.navigation.NavigationState

/**
 * Takes back for the screen it is composed in, before the screen's own dismissal applies.
 *
 * This is for UI inside a screen that should close before the screen does, such as an expanded
 * sheet, a focused field or a prompt about unsaved changes. How the screen itself leaves belongs in
 * [io.github.syrou.reaktiv.navigation.definition.Navigatable.dismissal].
 *
 * While it is enabled and its screen is on top, platform back and the Apple edge swipe call [onBack]
 * instead of going back. The swipes that move the screen with the finger do not start, so nothing
 * moves before [onBack] runs. The most recently composed enabled interceptor wins. One on a screen
 * covered by another entry, such as a modal, waits until its screen is on top again. Composed
 * outside any screen, it applies whatever is on top. Outside [NavigationRender] it does nothing.
 *
 * @param enabled whether back is taken right now.
 * @param onBack runs on the main thread when the user goes back.
 */
@Composable
public fun InterceptBack(enabled: Boolean = true, onBack: () -> Unit) {
    val controller = LocalInteractiveTransitionController.current ?: return
    val entryKey = LocalRenderedEntry.current?.stableKey
    val interceptor = remember(controller, entryKey) { BackInterceptors.Interceptor(entryKey) }
    SideEffect {
        interceptor.enabled = enabled
        interceptor.onBack = onBack
    }
    DisposableEffect(controller, interceptor) {
        controller.backInterceptors.add(interceptor)
        onDispose { controller.backInterceptors.remove(interceptor) }
    }
}

internal class BackInterceptors {

    class Interceptor(val entryKey: String?) {
        var enabled: Boolean by mutableStateOf(false)
        var onBack: () -> Unit = {}
    }

    private val interceptors = mutableStateListOf<Interceptor>()

    fun add(interceptor: Interceptor) {
        interceptors.add(interceptor)
    }

    fun remove(interceptor: Interceptor) {
        interceptors.remove(interceptor)
    }

    fun activeFor(state: NavigationState): Interceptor? = interceptors.lastOrNull { interceptor ->
        interceptor.enabled &&
            (interceptor.entryKey == null || interceptor.entryKey == state.currentEntry.stableKey)
    }
}
