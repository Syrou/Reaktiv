package io.github.syrou.reaktiv.navigation.middleware

import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

class NavigationSpamMiddleware(
    private val debounceTimeMs: Long = 300L,
    private val enableDuplicateDetection: Boolean = true,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private var lastNavigationAction: NavigationAction? = null
    private var isNavigating = false
    private var debounceJob: Job? = null
    private val navigationMutex = Mutex()
    private val recentRoutes = mutableSetOf<String>()
    private var sequenceNumber = 0L

    companion object {
        fun create(
            debounceTimeMs: Long = 300L,
            enableDuplicateDetection: Boolean = true,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ): Middleware {
            val middleware = NavigationSpamMiddleware(debounceTimeMs, enableDuplicateDetection, scope)

            return { action, getAllStates, storeAccessor, updatedState ->
                if (action is NavigationAction) {
                    if (middleware.shouldBlockAction(action)) {
                        ReaktivDebug.nav("🚫 Blocked navigation spam: ${action::class.simpleName}")
                    } else {
                        middleware.trackAction(action)
                        updatedState(action)
                    }
                } else {
                    updatedState(action)
                }
            }
        }
    }

    private suspend fun shouldBlockAction(
        action: NavigationAction
    ): Boolean = navigationMutex.withLock {
        // Block navigation to the same route within debounce window
        if (debounceJob?.isActive == true && 
            action is NavigationAction.BatchUpdate && action.currentEntry != null &&
            lastNavigationAction is NavigationAction.BatchUpdate && (lastNavigationAction as NavigationAction.BatchUpdate).currentEntry != null) {
            
            val currentRoute = action.currentEntry.navigatable.route
            val lastRoute = (lastNavigationAction as NavigationAction.BatchUpdate).currentEntry?.navigatable?.route
            
            if (currentRoute == lastRoute) {
                ReaktivDebug.nav("🎯 Blocking action - same route within debounce window: $currentRoute")
                return@withLock true
            }
        }

        return@withLock false
    }

    private fun trackAction(action: NavigationAction) {
        scope.launch {
            navigationMutex.withLock {
                lastNavigationAction = action
                sequenceNumber++
                isNavigating = true
                if (action is NavigationAction.BatchUpdate && action.currentEntry != null) {
                    val routeKey = "${action.currentEntry.screen.route}-${action.currentEntry.graphId}"
                    recentRoutes.add(routeKey)
                }
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    try {
                        delay(debounceTimeMs)
                        navigationMutex.withLock {
                            isNavigating = false
                            recentRoutes.clear() // Clear recent routes after debounce
                        }

                        ReaktivDebug.nav("✅ Navigation debounce complete - ready for next action")
                    } catch (e: CancellationException) {
                    }
                }
            }
        }
    }

    fun cleanup() {
        debounceJob?.cancel()
        scope.cancel()
    }
}