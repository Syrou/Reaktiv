package io.github.syrou.reaktiv.navigation.middleware

import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.model.NavigationTransitionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/**
 * Action category extensions for cleaner classification
 */
private val NavigationAction.isSystemAction: Boolean
    get() = when (this) {
        is NavigationAction.UpdateTransitionState -> true // System-generated transition management
        else -> false
    }

private val NavigationAction.isNavigationOperation: Boolean
    get() = when (this) {
        is NavigationAction.Navigate,
        is NavigationAction.Replace,
        is NavigationAction.Back,
        is NavigationAction.PopUpTo,
        is NavigationAction.ClearBackstack,
        is NavigationAction.BatchUpdate -> true

        else -> false
    }

class NavigationSpamMiddleware(
    private val debounceTimeMs: Long = 300L,
    private val maxActionsPerWindow: Int = 3,
    private val windowSizeMs: Long = 1000L,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val debounceWithSameRoute: Boolean = false
) {
    private val startTime = timeSource.markNow()

    private data class ActionRecord(
        val action: NavigationAction,
        val timestamp: Long,
        val route: String?
    )

    private val actionHistory = mutableListOf<ActionRecord>()
    private val navigationMutex = Mutex()
    private var sequenceNumber = 0L

    /**
     * Time-based helper functions to eliminate duplicate calculations
     */
    private fun isWithinTimeWindow(timestamp: Long, currentTime: Long): Boolean =
        currentTime - timestamp <= windowSizeMs

    private fun isWithinDebounceWindow(timestamp: Long, currentTime: Long): Boolean =
        currentTime - timestamp < debounceTimeMs

    private fun timeSinceAction(timestamp: Long, currentTime: Long): Long =
        currentTime - timestamp


    companion object {

        fun create(
            debounceTimeMs: Long = 300L,
            maxActionsPerWindow: Int = 3,
            windowSizeMs: Long = 1000L,
            timeSource: TimeSource = TimeSource.Monotonic,
            debounceWithSameRoute: Boolean = false
        ): Middleware {
            // Create middleware instance once and reuse it
            val middleware = NavigationSpamMiddleware(
                debounceTimeMs = debounceTimeMs,
                maxActionsPerWindow = maxActionsPerWindow,
                windowSizeMs = windowSizeMs,
                timeSource = timeSource,
                debounceWithSameRoute = debounceWithSameRoute
            )

            return { action, getAllStates, storeAccessor, updatedState ->
                if (action is NavigationAction) {
                    if (middleware.shouldBlockAction(action, storeAccessor)) {
                        ReaktivDebug.nav("🚫 Blocked navigation spam: ${action::class.simpleName}")
                    } else {
                        middleware.trackAction(action, storeAccessor)
                        updatedState(action)
                    }
                } else {
                    updatedState(action)
                }
            }
        }
    }

    private suspend fun shouldBlockAction(
        action: NavigationAction,
        storeAccessor: StoreAccessor
    ): Boolean = navigationMutex.withLock {
        val currentTime = startTime.elapsedNow().inWholeMilliseconds

        // 1. System actions bypass all protection
        if (action.isSystemAction) {
            return@withLock false
        }

        // 2. Block navigation operations during animation
        if (shouldBlockDuringAnimation(action, storeAccessor)) {
            return@withLock true
        }

        // 3. Apply spam detection for user actions
        return@withLock shouldBlockSpam(action, currentTime)
    }

    private suspend fun shouldBlockDuringAnimation(
        action: NavigationAction,
        storeAccessor: StoreAccessor
    ): Boolean {
        val navigationState = storeAccessor.selectState<NavigationState>().first()
        if (navigationState.transitionState != NavigationTransitionState.ANIMATING) {
            return false
        }

        return if (action.isNavigationOperation) {
            ReaktivDebug.nav("🚫 Blocking navigation operation: navigation is animating (${action::class.simpleName})")
            true
        } else {
            false // Allow guided flow management and other actions during animation
        }
    }

    private fun shouldBlockSpam(action: NavigationAction, currentTime: Long): Boolean {
        // Clean old actions from history
        actionHistory.removeAll { !isWithinTimeWindow(it.timestamp, currentTime) }

        val currentRoute = extractRouteFromAction(action)
        val actionType = action::class.simpleName

        // Check window-based spam protection
        val recentSimilarActions = actionHistory.count { record ->
            isWithinTimeWindow(record.timestamp, currentTime) &&
                    record.action::class.simpleName == actionType &&
                    (!debounceWithSameRoute || record.route == currentRoute)
        }

        if (recentSimilarActions >= maxActionsPerWindow) {
            ReaktivDebug.nav("🚫 Blocking spam: $actionType (route: $currentRoute) - $recentSimilarActions actions in ${windowSizeMs}ms window")
            return true
        }

        // Check debounce-based rapid duplicate protection
        val lastIdenticalAction = actionHistory.findLast { record ->
            record.action::class.simpleName == actionType &&
                    (!debounceWithSameRoute || record.route == currentRoute)
        }

        return if (lastIdenticalAction != null && isWithinDebounceWindow(lastIdenticalAction.timestamp, currentTime)) {
            val timeSince = timeSinceAction(lastIdenticalAction.timestamp, currentTime)
            ReaktivDebug.nav("🎯 Blocking rapid duplicate: $actionType (route: $currentRoute) - ${timeSince}ms since last (debounce: ${debounceTimeMs}ms)")
            true
        } else {
            if (lastIdenticalAction != null) {
                val timeSince = timeSinceAction(lastIdenticalAction.timestamp, currentTime)
                ReaktivDebug.nav("⏱️ Allowing after debounce: $actionType (route: $currentRoute) - ${timeSince}ms since last (debounce: ${debounceTimeMs}ms)")
            }
            false
        }
    }


    /**
     * Extract route information from various NavigationAction types for spam detection
     */
    private fun extractRouteFromAction(action: NavigationAction?): String? {
        return when (action) {
            is NavigationAction.BatchUpdate -> extractBatchUpdateRoute(action)

            // Navigation actions with currentEntry
            is NavigationAction.Navigate -> action.currentEntry?.navigatable?.route
            is NavigationAction.Replace -> action.currentEntry?.navigatable?.route
            is NavigationAction.Back -> action.currentEntry?.navigatable?.route
            is NavigationAction.PopUpTo -> action.currentEntry?.navigatable?.route
            is NavigationAction.ClearBackstack -> action.currentEntry?.navigatable?.route

            else -> null
        }
    }

    private fun extractBatchUpdateRoute(action: NavigationAction.BatchUpdate): String? {
        return if (action.activeGuidedFlowState != null) {
            val flowRoute = action.activeGuidedFlowState.flowRoute
            val isStartFlow = action.activeGuidedFlowState.currentStepIndex == 0 && action.operations.isNotEmpty()
            if (isStartFlow) "${flowRoute}_start" else "${flowRoute}_next"
        } else {
            action.currentEntry?.navigatable?.route
        }
    }

    private fun trackAction(
        action: NavigationAction,
        storeAccessor: StoreAccessor
    ) {
        storeAccessor.launch {
            navigationMutex.withLock {
                val currentTime = startTime.elapsedNow().inWholeMilliseconds
                val route = extractRouteFromAction(action)

                // Add to action history
                actionHistory.add(ActionRecord(action, currentTime, route))
                sequenceNumber++

                // Clean old actions periodically to prevent memory leaks
                // Remove actions older than 2x window size, or if we exceed max history size
                val maxHistorySize = 50
                val cleanupThreshold = windowSizeMs * 2

                if (actionHistory.size > maxHistorySize || sequenceNumber % 10L == 0L) {
                    actionHistory.removeAll { currentTime - it.timestamp > cleanupThreshold }
                }
                ReaktivDebug.nav("📊 Tracked action: ${action::class.simpleName} (route: $route) - history size: ${actionHistory.size}")
            }
        }
    }

}