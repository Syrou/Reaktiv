package io.github.syrou.reaktiv.introspection.capture

import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.CapturedLogicComplete
import io.github.syrou.reaktiv.introspection.protocol.CapturedLogicFailed
import io.github.syrou.reaktiv.introspection.protocol.CapturedLogicStart
import io.github.syrou.reaktiv.introspection.protocol.CrashException
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
 * Events are stored in file-backed JSONL storage (when filesystem is available)
 * to avoid holding large state snapshots in memory. Falls back to in-memory
 * storage on platforms without filesystem access (e.g., wasmJs browser).
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
    private var actionsStorage: CaptureStorage = createCaptureStorage("actions")
    private var logicStartedStorage: CaptureStorage = createCaptureStorage("logic_started")
    private var logicCompletedStorage: CaptureStorage = createCaptureStorage("logic_completed")
    private var logicFailedStorage: CaptureStorage = createCaptureStorage("logic_failed")

    private var sessionStartTime: Long = 0
    private var clientId: String = ""
    private var clientName: String = ""
    private var platform: String = ""
    private var started = false
    private var initialStateJson: String = "{}"
    private var capturedCrash: CrashInfo? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val exportJson = Json {
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
        this.initialStateJson = "{}"
        this.capturedCrash = null

        actionsStorage.clear()
        logicStartedStorage.clear()
        logicCompletedStorage.clear()
        logicFailedStorage.clear()
    }

    /**
     * Captures the initial full state snapshot at session start.
     *
     * @param stateJson The full state JSON captured before the first action
     */
    fun captureInitialState(stateJson: String) {
        initialStateJson = stateJson
    }

    /**
     * Gets the captured initial state JSON.
     */
    fun getInitialStateJson(): String = initialStateJson

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

        val line = json.encodeToString(event)
        actionsStorage.appendLine(line)
        if (actionsStorage.lineCount() > maxActions) {
            actionsStorage.trimTo(maxActions)
        }
    }

    /**
     * Captures a logic method started event.
     */
    fun captureLogicStarted(event: CapturedLogicStart) {
        if (!started) return

        val line = json.encodeToString(event)
        logicStartedStorage.appendLine(line)
        trimLogicEvents()
    }

    /**
     * Captures a logic method completed event.
     */
    fun captureLogicCompleted(event: CapturedLogicComplete) {
        if (!started) return

        val line = json.encodeToString(event)
        logicCompletedStorage.appendLine(line)
        trimLogicEvents()
    }

    /**
     * Captures a logic method failed event.
     */
    fun captureLogicFailed(event: CapturedLogicFailed) {
        if (!started) return

        val line = json.encodeToString(event)
        logicFailedStorage.appendLine(line)
        trimLogicEvents()
    }

    /**
     * Captures crash information from a logic method failure.
     * This should be called when a logic failure represents a fatal crash.
     *
     * @param event The logic failure event to capture as a crash
     */
    fun captureCrashFromLogicFailure(event: CapturedLogicFailed) {
        if (!started) return

        capturedCrash = CrashInfo(
            timestamp = event.timestamp,
            exception = CrashException(
                exceptionType = event.exceptionType,
                message = event.exceptionMessage,
                stackTrace = "",
                causedBy = null
            )
        )
    }

    /**
     * Captures crash information from a throwable.
     * This is typically called by the crash handler for uncaught exceptions.
     *
     * @param throwable The exception that caused the crash
     */
    fun captureCrashFromThrowable(throwable: Throwable) {
        if (!started) return

        capturedCrash = CrashInfo(
            timestamp = Clock.System.now().toEpochMilliseconds(),
            exception = throwable.toCrashException()
        )
    }

    /**
     * Gets the captured crash info if any.
     */
    fun getCapturedCrash(): CrashInfo? = capturedCrash

    private fun trimLogicEvents() {
        val totalLogicEvents = logicStartedStorage.lineCount() +
                logicCompletedStorage.lineCount() +
                logicFailedStorage.lineCount()
        if (totalLogicEvents > maxLogicEvents) {
            val toRemove = totalLogicEvents - maxLogicEvents
            var removed = 0

            val startedCount = logicStartedStorage.lineCount()
            if (removed < toRemove && startedCount > 0) {
                val removeFromStarted = minOf(toRemove - removed, startedCount)
                logicStartedStorage.trimTo(startedCount - removeFromStarted)
                removed += removeFromStarted
            }

            val completedCount = logicCompletedStorage.lineCount()
            if (removed < toRemove && completedCount > 0) {
                val removeFromCompleted = minOf(toRemove - removed, completedCount)
                logicCompletedStorage.trimTo(completedCount - removeFromCompleted)
                removed += removeFromCompleted
            }

            val failedCount = logicFailedStorage.lineCount()
            if (removed < toRemove && failedCount > 0) {
                val removeFromFailed = minOf(toRemove - removed, failedCount)
                logicFailedStorage.trimTo(failedCount - removeFromFailed)
            }
        }
    }

    private fun readActions(): List<CapturedAction> {
        return actionsStorage.readLines().map { json.decodeFromString(it) }
    }

    private fun readLogicStarted(): List<CapturedLogicStart> {
        return logicStartedStorage.readLines().map { json.decodeFromString(it) }
    }

    private fun readLogicCompleted(): List<CapturedLogicComplete> {
        return logicCompletedStorage.readLines().map { json.decodeFromString(it) }
    }

    private fun readLogicFailed(): List<CapturedLogicFailed> {
        return logicFailedStorage.readLines().map { json.decodeFromString(it) }
    }

    /**
     * Gets the current session history.
     */
    fun getSessionHistory(): SessionHistory {
        return SessionHistory(
            startTime = sessionStartTime,
            initialStateJson = initialStateJson,
            actions = readActions(),
            logicStarted = readLogicStarted(),
            logicCompleted = readLogicCompleted(),
            logicFailed = readLogicFailed()
        )
    }

    /**
     * Exports the current session as a JSON string.
     * Automatically includes any captured crash information.
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
            crash = capturedCrash,
            session = SessionData(
                startTime = sessionStartTime,
                endTime = now,
                initialStateJson = initialStateJson,
                actions = readActions(),
                logicStartedEvents = readLogicStarted(),
                logicCompletedEvents = readLogicCompleted(),
                logicFailedEvents = readLogicFailed()
            )
        )

        return exportJson.encodeToString(export)
    }

    /**
     * Exports the current session as a JSON string, including crash info from parameters.
     * This is kept for DevTools compatibility where crash info is created externally.
     *
     * @param crashInfo Crash information to include in the export
     * @return JSON string that can be imported as a ghost device in DevTools
     */
    @OptIn(ExperimentalUuidApi::class)
    fun exportSessionWithCrash(crashInfo: CrashInfo): String {
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
            crash = crashInfo,
            session = SessionData(
                startTime = sessionStartTime,
                endTime = now,
                initialStateJson = initialStateJson,
                actions = readActions(),
                logicStartedEvents = readLogicStarted(),
                logicCompletedEvents = readLogicCompleted(),
                logicFailedEvents = readLogicFailed()
            )
        )

        return exportJson.encodeToString(export)
    }

    /**
     * Exports the current session with crash information as a JSON string.
     * Captures the crash from the throwable before exporting.
     *
     * @param throwable The exception that caused the crash
     * @return JSON string that can be imported as a ghost device with crash info
     */
    fun exportCrashSession(throwable: Throwable): String {
        captureCrashFromThrowable(throwable)
        return exportSession()
    }

    /**
     * Clears all captured data but keeps the session active.
     */
    fun clear() {
        actionsStorage.clear()
        logicStartedStorage.clear()
        logicCompletedStorage.clear()
        logicFailedStorage.clear()
        capturedCrash = null
    }

    /**
     * Stops the session capture.
     */
    fun stop() {
        started = false
        actionsStorage.delete()
        logicStartedStorage.delete()
        logicCompletedStorage.delete()
        logicFailedStorage.delete()
    }
}

/**
 * Represents the current session history.
 */
data class SessionHistory(
    val startTime: Long,
    val initialStateJson: String = "{}",
    val actions: List<CapturedAction>,
    val logicStarted: List<CapturedLogicStart>,
    val logicCompleted: List<CapturedLogicComplete>,
    val logicFailed: List<CapturedLogicFailed>
)
