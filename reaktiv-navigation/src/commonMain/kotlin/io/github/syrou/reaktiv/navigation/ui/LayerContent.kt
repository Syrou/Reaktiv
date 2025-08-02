package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.model.NavigationLayer
import kotlinx.coroutines.launch

@Composable
fun LayerContent(
    layer: NavigationLayer,
    screenContent: @Composable (Navigatable, StringAnyMap) -> Unit
) {
    val scope = rememberCoroutineScope()
    val store = rememberStore()
    val entry = layer.entry

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(layer.zIndex)
    ) {
        screenContent(entry.navigatable, entry.params)

        if (layer.shouldDim) {
            val interactionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = layer.dimAlpha))
                    .let { modifier ->
                        val topModalEntry = entry.takeIf { it.isModal }
                        val isDismissible = topModalEntry?.navigatable?.let { nav ->
                            (nav as? Modal)?.onDismissTapOutside != null
                        } ?: false

                        if (isDismissible) {
                            modifier.clickable(interactionSource, indication = null) {
                                scope.launch {
                                    (topModalEntry.navigatable as? Modal)?.onDismissTapOutside?.invoke(store)
                                }
                            }
                        } else {
                            modifier
                        }
                    }
            )
        }
    }
}
