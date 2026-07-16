package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.util.canArmSwipeDismiss

private val DISMISS_INDICATOR_SLOT_HEIGHT = 28.dp

@Composable
internal fun DismissIndicatorSlot(
    entry: NavigationEntry,
    content: @Composable () -> Unit
) {
    val controller = LocalInteractiveTransitionController.current
    val navModule = LocalNavigationModule.current
    val navigationState by composeState<NavigationState>()
    val navigatable = entry.navigatable
    val showPill = controller != null &&
        navigatable.showsDismissIndicator &&
        navigationState.currentEntry.stableKey == entry.stableKey &&
        canArmSwipeDismiss(navigationState, navModule)

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (showPill) {
                        Modifier
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .height(DISMISS_INDICATOR_SLOT_HEIGHT)
                            .onGloballyPositioned { coordinates ->
                                controller.indicatorCoordinates = coordinates
                            }
                    } else {
                        Modifier.height(0.dp)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (showPill) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(
                            color = Color(0x99888888),
                            shape = RoundedCornerShape(2.dp)
                        )
                        .testTag("reaktiv-dismiss-indicator")
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            content()
        }
    }
    DisposableEffect(controller, showPill) {
        onDispose {
            if (showPill) {
                controller.indicatorCoordinates = null
            }
        }
    }
}
