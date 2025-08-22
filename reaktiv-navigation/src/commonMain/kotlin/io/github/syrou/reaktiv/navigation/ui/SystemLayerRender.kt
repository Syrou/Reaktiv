package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.model.NavigationEntry

@Composable
fun SystemLayerRender(
    entries: List<NavigationEntry>,
    screenContent: @Composable (Navigatable, Params) -> Unit
) {
    entries
        .sortedBy { it.navigatable.elevation }
        .forEach { entry ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(3000f + entry.navigatable.elevation)
            ) {
                screenContent(entry.navigatable, entry.params)
            }
        }
}