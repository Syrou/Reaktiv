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
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.util.canArmSwipeDismiss
import io.github.syrou.reaktiv.navigation.util.dismissableBoundary
import io.github.syrou.reaktiv.navigation.util.presentsDismissIndicator

private val DISMISS_INDICATOR_SLOT_HEIGHT = 28.dp

private const val DISMISS_INDICATOR_ALPHA = 0.4f

private fun Modifier.paintIfSpecified(color: Color): Modifier =
    if (color.isSpecified && color != Color.Transparent) background(color) else this

@Composable
internal fun DismissIndicatorSlot(
    entry: NavigationEntry,
    contentBackground: Color = Color.Unspecified,
    content: @Composable () -> Unit
) {
    val controller = LocalInteractiveTransitionController.current
    val navModule = LocalNavigationModule.current
    val navigationState by composeState<NavigationState>()
    val reservesStrip = controller != null && presentsDismissIndicator(entry, navModule)
    val showPill = controller != null &&
        reservesStrip &&
        navigationState.currentEntry.stableKey == entry.stableKey &&
        canArmSwipeDismiss(navigationState, navModule)
    val boundary = dismissableBoundary(entry, navModule)
        ?.let { navModule.getGraphDefinitions()[it]?.declaration }
    val pillColor = listOfNotNull(
        boundary?.dismissIndicatorColor,
        entry.navigatable.dismissIndicatorColor,
        LocalDismissIndicatorColor.current
    ).firstOrNull { it.isSpecified }
        ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISMISS_INDICATOR_ALPHA)
    val stripBackground = listOfNotNull(
        boundary?.dismissIndicatorBackground,
        entry.navigatable.dismissIndicatorBackground,
        LocalDismissIndicatorBackground.current
    ).firstOrNull { it.isSpecified }
        ?: MaterialTheme.colorScheme.surfaceContainerLow

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .paintIfSpecified(stripBackground)
                .then(
                    if (reservesStrip) {
                        Modifier
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .height(DISMISS_INDICATOR_SLOT_HEIGHT)
                    } else {
                        Modifier.height(0.dp)
                    }
                )
                .then(
                    if (showPill) {
                        Modifier.onGloballyPositioned { coordinates ->
                            controller.indicatorCoordinates = coordinates
                        }
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (showPill) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(
                            color = pillColor,
                            shape = RoundedCornerShape(2.dp)
                        )
                        .testTag("reaktiv-dismiss-indicator")
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f).paintIfSpecified(contentBackground)) {
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
