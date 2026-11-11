package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/**
 * The browser back button arrives through history navigation, not here. What Compose reports as
 * back, Escape pressed with nothing else handling it, goes through the same handling as Android back.
 */
@Composable
internal actual fun PlatformBackHandler(
    enabled: Boolean,
    coordinator: PlatformBackCoordinator
) {
    NavigationEventBackHandler(enabled, coordinator)
}

@Composable
internal actual fun platformBackGesturePolicy(): BackGesturePolicy {
    val coarse = remember { hasCoarsePointer() }
    return BackGesturePolicy(
        edgeSwipe = !coarse,
        fullSurfaceSwipe = true,
        fullSurfaceSkipsEdge = coarse,
        mouseStartsBack = false
    )
}

@Composable
internal actual fun PlatformDocumentTitle(title: String?) {
    SideEffect {
        if (title != null) setDocumentTitle(title)
    }
}

private fun hasCoarsePointer(): Boolean = js("""
    (function() {
        try {
            return typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia('(pointer: coarse)').matches;
        } catch (e) {
            return false;
        }
    })()
""")

private fun setDocumentTitle(title: String): Unit = js("""
    (function(t) {
        if (typeof document !== 'undefined') document.title = t;
    })(title)
""")
