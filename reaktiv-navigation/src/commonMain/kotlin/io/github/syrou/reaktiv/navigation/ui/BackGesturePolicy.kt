package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

internal data class BackGesturePolicy(
    val edgeSwipe: Boolean,
    val fullSurfaceSwipe: Boolean,
    val fullSurfaceSkipsEdge: Boolean,
    val mouseStartsBack: Boolean
) {
    companion object {
        val Everywhere: BackGesturePolicy = BackGesturePolicy(
            edgeSwipe = true,
            fullSurfaceSwipe = true,
            fullSurfaceSkipsEdge = false,
            mouseStartsBack = true
        )

        val Nowhere: BackGesturePolicy = BackGesturePolicy(
            edgeSwipe = false,
            fullSurfaceSwipe = false,
            fullSurfaceSkipsEdge = false,
            mouseStartsBack = false
        )
    }
}

internal val LocalBackGesturePolicy = compositionLocalOf<BackGesturePolicy?> { null }

@Composable
internal expect fun platformBackGesturePolicy(): BackGesturePolicy

@Composable
internal expect fun PlatformDocumentTitle(title: String?)

