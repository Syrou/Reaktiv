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
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import kotlinx.coroutines.launch

@Composable
fun LocalOverlayLayerRender(
    entries: List<NavigationEntry>,
    screenContent: @Composable (Navigatable, StringAnyMap) -> Unit
) {
    val scope = rememberCoroutineScope()
    val store = rememberStore()
    
    entries
        .sortedBy { it.navigatable.elevation }
        .forEach { entry ->
            val modal = entry.navigatable as? Modal
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1000f + entry.navigatable.elevation)
            ) {
                if (modal?.shouldDimBackground == true) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = modal.backgroundDimAlpha))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = modal.onDismissTapOutside != null
                            ) {
                                scope.launch {
                                    modal.onDismissTapOutside?.invoke(store)
                                }
                            }
                    )
                }
                
                screenContent(entry.navigatable, entry.params)
            }
        }
}