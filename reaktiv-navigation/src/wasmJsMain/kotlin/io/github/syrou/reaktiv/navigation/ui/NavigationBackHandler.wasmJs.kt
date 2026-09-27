package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/**
 * Browsers deliver back through history navigation rather than through a system callback the
 * Compose tree can intercept, so there is nothing to register here. Back is driven by the app,
 * either from UI affordances or from the edge swipe gesture below.
 */
@Composable
internal actual fun PlatformBackHandler(
    enabled: Boolean,
    coordinator: PlatformBackCoordinator
) {
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
