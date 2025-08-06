@file:OptIn(ExperimentalTime::class)

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.ui.ContentLayerRender
import io.github.syrou.reaktiv.navigation.ui.GlobalOverlayLayerRender
import io.github.syrou.reaktiv.navigation.ui.LocalOverlayLayerRender
import io.github.syrou.reaktiv.navigation.ui.SystemLayerRender
import io.github.syrou.reaktiv.navigation.util.NavigationDebugger
import kotlin.time.ExperimentalTime

@Composable
fun NavigationRender(
    modifier: Modifier = Modifier,
    screenContent: @Composable (Navigatable, StringAnyMap) -> Unit
) {
    val navigationState by composeState<NavigationState>()

    if (ReaktivDebug.isEnabled) {
        NavigationDebugger(navigationState)
    }

    Box(modifier = modifier.fillMaxSize()) {
        ContentLayerRender(
            entries = navigationState.contentLayerEntries,
            navigationState = navigationState,
            screenContent = screenContent
        )

        GlobalOverlayLayerRender(
            entries = navigationState.globalOverlayEntries,
            screenContent = screenContent
        )

        SystemLayerRender(
            entries = navigationState.systemLayerEntries,
            screenContent = screenContent
        )
    }
}