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
                        ReaktivDebug.nav("🚫 Blocked spam navigation action: ${action::class.simpleName}")
                    }else {
                        middleware.trackAction(action)
                        updatedState(action)
                    }
                }else{
                    updatedState(action)
                }
            }
        }
    }

    private suspend fun shouldBlockAction(
        action: NavigationAction
    ): Boolean = navigationMutex.withLock {
        if (debounceJob?.isActive == true) {
            ReaktivDebug.nav("⏱️ Blocking action - within debounce window")
            return@withLock true
        }
        if (enableDuplicateDetection && isDuplicateAction(action)) {
            ReaktivDebug.nav("🔄 Blocking action - duplicate detected")
            return@withLock true
        }
        if (action is NavigationAction.BatchUpdate && action.currentEntry != null) {
            val routeKey = "${action.currentEntry.screen.route}-${action.currentEntry.graphId}"
            
            if (recentRoutes.contains(routeKey)) {
                ReaktivDebug.nav("🎯 Blocking action - same route recently accessed: $routeKey")
                return@withLock true
            }
        }

        return@withLock false
    }

    private fun isDuplicateAction(action: NavigationAction): Boolean {
        return when {
            action == lastNavigationAction -> true
            action is NavigationAction.BatchUpdate && lastNavigationAction is NavigationAction.BatchUpdate -> {
                val last = lastNavigationAction as NavigationAction.BatchUpdate
                action.currentEntry?.screen?.route == last.currentEntry?.screen?.route &&
                action.currentEntry?.graphId == last.currentEntry?.graphId &&
                action.currentEntry?.params == last.currentEntry?.params
            }
            else -> false
        }
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