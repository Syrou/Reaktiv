package io.github.syrou.reaktiv.introspection.capture

import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.CapturedLogicComplete
import io.github.syrou.reaktiv.introspection.protocol.CapturedLogicFailed
import io.github.syrou.reaktiv.introspection.protocol.CapturedLogicStart
import io.github.syrou.reaktiv.introspection.protocol.CrashInfo
import io.github.syrou.reaktiv.introspection.protocol.ExportedClientInfo
import io.github.syrou.reaktiv.introspection.protocol.SessionData
import io.github.syrou.reaktiv.introspection.protocol.SessionExport
import io.github.syrou.reaktiv.introspection.protocol.SessionExportFormat
import io.github.syrou.reaktiv.introspection.protocol.toCrashException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Captures session data for crash reports and manual export.
 *
 * Usage:
 * ```kotlin
 * val capture = SessionCapture(maxActions = 500, maxLogicEvents = 1000)
 * capture.start("client-id", "MyApp", "Android")
 *
 * // Capture events as they happen (typically called by middleware/observers)
 * capture.captureAction(actionEvent)
 * capture.captureLogicStarted(logicEvent)
 *
 * // Manual export (no crash)
 * val json = capture.exportSession()
 *
 * // Export with crash info
 * val crashJson = capture.exportCrashSession(exception)
 * ```
 *
 * The exported JSON format is compatible with DevTools ghost device import.
 *
 * @param maxActions Maximum number of actions to retain (older actions are dropped)
 * @param maxLogicEvents Maximum number of logic events to retain (older events are dropped)
 */
class SessionCapture(
    private val maxActions: Int = 1000,
    private val maxLogicEvents: Int = 2000
) {
    private val actions = ArrayDeque<CapturedAction>()
    private val logicStarted = ArrayDeque<CapturedLogicStart>()
    private val logicCompleted = ArrayDeque<CapturedLogicComplete>()
    private val logicFailed = ArrayDeque<CapturedLogicFailed>()

    private var sessionStartTime: Long = 0
    private var clientId: String = ""
    private var clientName: String = ""
    private var platform: String = ""
    private var started = false

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Starts a new session capture.
     *
     * @param clientId The client ID for this session
     * @param clientName The display name for this client
     * @param platform The platform description
     */
    fun start(clientId: String, clientName: String, platform: String) {
        this.clientId = clientId
        this.clientName = clientName
        this.platform = platform
        this.sessionStartTime = Clock.System.now().toEpochMilliseconds()
        this.started = true

        actions.clear()
        logicStarted.clear()
        logicCompleted.clear()
        logicFailed.clear()
    }

    /**
     * Checks if session capture has been started.
     */
    fun isStarted(): Boolean = started

    /**
     * Gets the client ID for this session.
     */
    fun getClientId(): String = clientId

    /**
     * Captures an action dispatched event.
     */
    fun captureAction(event: CapturedAction) {
        if (!started) return

        actions.addLast(event)
        while (actions.size > maxActions) {
            actions.removeFirst()
        }
    }

    /**
     * Captures a logic method started event.
     */
    fun captureLogicStarted(event: CapturedLogicStart) {
        if (!started) return

        logicStarted.addLast(event)
        trimLogicEvents()
    }

    /**
     * Captures a logic method completed event.
     */
    fun captureLogicCompleted(event: CapturedLogicComplete) {
        if (!started) return

        logicCompleted.addLast(event)
        trimLogicEvents()
    }

    /**
     * Captures a logic method failed event.
     */
    fun captureLogicFailed(event: CapturedLogicFailed) {
        if (!started) return

        logicFailed.addLast(event)
        trimLogicEvents()
    }

    private fun trimLogicEvents() {
        val totalLogicEvents = logicStarted.size + logicCompleted.size + logicFailed.size
        if (totalLogicEvents > maxLogicEvents) {
            val toRemove = totalLogicEvents - maxLogicEvents
            var removed = 0

            while (removed < toRemove && logicStarted.isNotEmpty()) {
                logicStarted.removeFirst()
                removed++
            }
            while (removed < toRemove && logicCompleted.isNotEmpty()) {
                logicCompleted.removeFirst()
                removed++
            }
            while (removed < toRemove && logicFailed.isNotEmpty()) {
                logicFailed.removeFirst()
                removed++
            }
        }
    }

    /**
     * Gets the current session history.
     */
    fun getSessionHistory(): SessionHistory {
        return SessionHistory(
            startTime = sessionStartTime,
            actions = actions.toList(),
            logicStarted = logicStarted.toList(),
            logicCompleted = logicCompleted.toList(),
            logicFailed = logicFailed.toList()
        )
    }

    /**
     * Exports the current session as a JSON string (no crash).
     *
     * @return JSON string that can be imported as a ghost device in DevTools
     */
    @OptIn(ExperimentalUuidApi::class)
    fun exportSession(): String {
        val now = Clock.System.now().toEpochMilliseconds()

        val export = SessionExport(
            version = SessionExportFormat.VERSION,
            sessionId = Uuid.random().toString(),
            exportedAt = now,
            clientInfo = ExportedClientInfo(
                clientId = clientId,
                clientName = clientName,
                platform = platform
            ),
            crash = null,
            session = SessionData(
                startTime = sessionStartTime,
                endTime = now,
                actions = actions.toList(),
                logicStartedEvents = logicStarted.toList(),
                logicCompletedEvents = logicCompleted.toList(),
                logicFailedEvents = logicFailed.toList()
            )
        )

        return json.encodeToString(export)
    }

    /**
     * Exports the current session with crash information as a JSON string.
     *
     * @param throwable The exception that caused the crash
     * @return JSON string that can be imported as a ghost device with crash info
     */
    @OptIn(ExperimentalUuidApi::class)
    fun exportCrashSession(throwable: Throwable): String {
        val now = Clock.System.now().toEpochMilliseconds()

        val crashException = throwable.toCrashException()

        val export = SessionExport(
            version = SessionExportFormat.VERSION,
            sessionId = Uuid.random().toString(),
            exportedAt = now,
            clientInfo = ExportedClientInfo(
                clientId = clientId,
                clientName = clientName,
                platform = platform
            ),
            crash = CrashInfo(
                timestamp = now,
                exception = crashException
            ),
            session = SessionData(
                startTime = sessionStartTime,
                endTime = now,
                actions = actions.toList(),
                logicStartedEvents = logicStarted.toList(),
                logicCompletedEvents = logicCompleted.toList(),
                logicFailedEvents = logicFailed.toList()
            )
        )

        return json.encodeToString(export)
    }

    /**
     * Clears all captured data but keeps the session active.
     */
    fun clear() {
        actions.clear()
        logicStarted.clear()
        logicCompleted.clear()
        logicFailed.clear()
    }

    /**
     * Stops the session capture.
     */
    fun stop() {
        started = false
        clear()
    }
}

/**
 * Represents the current session history.
 */
data class SessionHistory(
    val startTime: Long,
    val actions: List<CapturedAction>,
    val logicStarted: List<CapturedLogicStart>,
    val logicCompleted: List<CapturedLogicComplete>,
    val logicFailed: List<CapturedLogicFailed>
)
