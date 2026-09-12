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

@Composable
internal fun ContentInsets.windowInsets(): WindowInsets? = when (this) {
    ContentInsets.Fullscreen -> null
    ContentInsets.SafeArea -> WindowInsets.systemBars.union(WindowInsets.displayCutout)
}

@Composable
private fun Modifier.contentInsetsPadding(insets: ContentInsets): Modifier =
    insets.windowInsets()?.let { windowInsetsPadding(it) } ?: this
