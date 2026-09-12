package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.navigation.definition.ContentInsets
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.param.Params

@Composable
internal fun NavigatableContent(navigatable: Navigatable, params: Params) {
    Box(modifier = Modifier.contentInsetsPadding(navigatable.contentInsets)) {
        navigatable.Content(params)
    }
}

/**
 * The insets the renderer holds this content clear of, or null when the declaration leaves that to
 * the content itself.
 *
 * Null is the whole of what [ContentInsets.Fullscreen] means, and it is what says the renderer is
 * to touch nothing: the grab strip above the surface reads the same answer to decide whether to
 * report the inset it stands in, so a screen that was never declared keeps the layout it had before
 * any of this existed.
 */
@Composable
internal fun ContentInsets.windowInsets(): WindowInsets? = when (this) {
    ContentInsets.Fullscreen -> null
    ContentInsets.SafeArea -> WindowInsets.systemBars.union(WindowInsets.displayCutout)
}

@Composable
private fun Modifier.contentInsetsPadding(insets: ContentInsets): Modifier =
    insets.windowInsets()?.let { windowInsetsPadding(it) } ?: this
