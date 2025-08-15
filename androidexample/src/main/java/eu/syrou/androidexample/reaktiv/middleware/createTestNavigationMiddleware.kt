package eu.syrou.androidexample.reaktiv.middleware

import eu.syrou.androidexample.reaktiv.TestNavigationModule.TestNavigationAction
import eu.syrou.androidexample.ui.screen.home.leaderboard.LeaderboardDetailScreen
import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.extension.navigation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

fun createTestNavigationMiddleware(): Middleware = { action, getAllStates, storeAccessor, updatedState ->
    when (action) {
        is TestNavigationAction.TriggerMultipleNavigation -> {
            ReaktivDebug.nav("🧪 Test: Starting multiple navigation sequence")

            // Execute the original action first
            updatedState(action)

            // Then perform multiple navigation operations
            storeAccessor.launch {
                storeAccessor.navigation {
                    dismissModals()
                    navigateTo("home/leaderboard/detail/weekly")
                    navigateTo("home/leaderboard/player/1")
                }
                ReaktivDebug.nav("🧪 Test: Multiple navigation sequence completed")
            }
        }

        else -> {
            // Pass through all other actions normally
            updatedState(action)
        }
    }
}