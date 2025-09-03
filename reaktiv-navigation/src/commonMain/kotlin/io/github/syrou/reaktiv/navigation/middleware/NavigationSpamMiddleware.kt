package io.github.syrou.reaktiv.navigation.middleware

import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

class NavigationSpamMiddleware(
    private val debounceTimeMs: Long = 300L,
    private val maxActionsPerWindow: Int = 3,
    private val windowSizeMs: Long = 1000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private data class ActionRecord(
        val action: NavigationAction,
        val timestamp: Long,
        val route: String?
    )

    private val actionHistory = mutableListOf<ActionRecord>()
    private val navigationMutex = Mutex()
    private var sequenceNumber = 0L

    companion object {
        fun create(
            debounceTimeMs: Long = 300L,
            maxActionsPerWindow: Int = 3,
            windowSizeMs: Long = 1000L,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ): Middleware {
            val middleware = NavigationSpamMiddleware(debounceTimeMs, maxActionsPerWindow, windowSizeMs, scope)

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
        val currentTime = Clock.System.now().toEpochMilliseconds()

        // Skip spam prevention for certain action types
        when (action) {
            // Allow Back actions - users should be able to navigate back quickly
            is NavigationAction.Back -> return@withLock false

            // Allow guided flow user actions - but they still get tracked
            is NavigationAction.StartGuidedFlow,
            is NavigationAction.NextStep -> {
                /* Continue with spam detection */
            }

            // Skip internal/high-priority actions completely
            is NavigationAction.UpdateActiveGuidedFlow,
            is NavigationAction.ClearActiveGuidedFlow,
            is NavigationAction.UpdateGuidedFlowModifications,
            is NavigationAction.ClearAllGuidedFlowModifications -> return@withLock false

            else -> {
                /* Continue with spam detection */
            }
        }

        // Clean old actions from history (older than window)
        actionHistory.removeAll { currentTime - it.timestamp > windowSizeMs }

        val currentRoute = extractRouteFromAction(action)
        val actionType = action::class.simpleName

        // Count recent similar actions in the time window
        val recentSimilarActions = actionHistory.count { record ->
            val timeDiff = currentTime - record.timestamp
            val isSameType = record.action::class.simpleName == actionType
            val isSameRoute = when {
                currentRoute != null && record.route != null -> currentRoute == record.route
                currentRoute == null && record.route == null -> true
                else -> false
            }

            timeDiff <= windowSizeMs && isSameType && isSameRoute
        }

        if (recentSimilarActions >= maxActionsPerWindow) {
            ReaktivDebug.nav("🚫 Blocking spam: $actionType (route: $currentRoute) - $recentSimilarActions actions in ${windowSizeMs}ms window")
            return@withLock true
        }

        // Also check for rapid identical actions within debounce period
        val lastIdenticalAction = actionHistory.findLast { record ->
            record.action::class.simpleName == actionType && record.route == currentRoute
        }

        if (lastIdenticalAction != null && (currentTime - lastIdenticalAction.timestamp) < debounceTimeMs) {
            ReaktivDebug.nav("🎯 Blocking rapid duplicate: $actionType (route: $currentRoute) - ${currentTime - lastIdenticalAction.timestamp}ms since last")
            return@withLock true
        }

        return@withLock false
    }

    /**
     * Extract route information from various NavigationAction types for spam detection
     */
    private fun extractRouteFromAction(action: NavigationAction?): String? {
        return when (action) {
            is NavigationAction.BatchUpdate -> action.currentEntry?.navigatable?.route
            is NavigationAction.Navigate -> action.currentEntry?.navigatable?.route
            is NavigationAction.Replace -> action.currentEntry?.navigatable?.route
            is NavigationAction.Back -> action.currentEntry?.navigatable?.route
            is NavigationAction.PopUpTo -> action.currentEntry?.navigatable?.route
            is NavigationAction.ClearBackstack -> action.currentEntry?.navigatable?.route
            // Guided flow actions are handled by business logic, not spam middleware
            else -> null
        }
    }

    private fun trackAction(action: NavigationAction) {
        scope.launch {
            navigationMutex.withLock {
                val currentTime = Clock.System.now().toEpochMilliseconds()
                val route = extractRouteFromAction(action)

                // Add to action history
                actionHistory.add(ActionRecord(action, currentTime, route))
                sequenceNumber++

                // Clean old actions periodically to prevent memory leaks
                // Keep reasonable history size
                if (actionHistory.size > 50) {
                    actionHistory.removeAll { currentTime - it.timestamp > windowSizeMs * 2 }
                }

                ReaktivDebug.nav("📊 Tracked action: ${action::class.simpleName} (route: $route) - history size: ${actionHistory.size}")
            }
        }
    }

}