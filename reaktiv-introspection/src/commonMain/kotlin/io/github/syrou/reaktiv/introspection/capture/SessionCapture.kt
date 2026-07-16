package io.github.syrou.reaktiv.introspection.capture

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.currentTimeMillis
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.CrashInfo
import io.github.syrou.reaktiv.introspection.protocol.ExportedClientInfo
import io.github.syrou.reaktiv.introspection.protocol.SessionData
import io.github.syrou.reaktiv.introspection.protocol.SessionExport
import io.github.syrou.reaktiv.introspection.protocol.SessionExportFormat
import io.github.syrou.reaktiv.introspection.protocol.toCrashException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Captures session data for crash reports, manual export, and DevTools streaming.
 *
 * SessionCapture is the shared nexus for all tooling signals: dispatched actions,
 * traced logic events, and crashes all flow through this single instance. Capture
 * calls only enqueue a record; a background worker performs JSON encoding and
 * storage writes off the dispatch path, batching consecutive records into single
 * storage writes.
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
 * capture.captureDispatchedAction(action, resultState)
 * capture.captureLogicStarted(logicEvent)
 *
 * // Crashes are reported once and fan out to every consumer
 * capture.reportCrash(exception)
 *
 * // Manual export (flushes pending records first)
 * val json = capture.exportSession()
 * ```
 *
 * The exported JSON format is compatible with DevTools ghost device import.
 *
 * @param maxActions Maximum number of actions to retain (older actions are dropped)
 * @param maxLogicEvents Maximum number of logic events to retain (older events are dropped)
 */
@OptIn(ExperimentalAtomicApi::class)
public class SessionCapture(
    private val maxActions: Int = 1000,
    private val maxLogicEvents: Int = 2000
) {
    private val actionsStorage: CaptureStorage = createCaptureStorage("actions")
    private val logicStartedStorage: CaptureStorage = createCaptureStorage("logic_started")
    private val logicCompletedStorage: CaptureStorage = createCaptureStorage("logic_completed")
    private val logicFailedStorage: CaptureStorage = createCaptureStorage("logic_failed")

    private var sessionStartTime: Long = 0
    private var clientId: String = ""
    private var clientName: String = ""
    private var platform: String = ""
    private var started = false
    private var initialStateJson: String = "{}"
    private var capturedCrash: CrashInfo? = null

    private val json = reaktivJson(encodeDefaults = true)
    private var stateJson: Json = reaktivJson()

    private var workerScope: CoroutineScope? = null
    private var channel: Channel<Record>? = null
    private val enqueuedCount = AtomicLong(0L)
    private val processedCount = AtomicLong(0L)

    private val _actions = MutableSharedFlow<CapturedAction>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Live stream of captured actions, emitted by the worker after encoding.
     * DevTools consumes this instead of re-serializing state per action.
     */
    public val actions: SharedFlow<CapturedAction> = _actions

    private val _crashes = MutableSharedFlow<CrashInfo>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Live stream of reported crashes. Every crash source (traced logic failures,
     * platform uncaught-exception handlers, manual reports) funnels through here.
     */
    public val crashes: SharedFlow<CrashInfo> = _crashes

    private sealed interface Record
    private class DispatchedAction(val action: ModuleAction, val state: ModuleState, val timestamp: Long) : Record
    private class PrebuiltAction(val event: CapturedAction) : Record
    private class InitialState(val states: Map<String, ModuleState>) : Record
    private class LogicStarted(val event: LogicMethodStart) : Record
    private class LogicCompleted(val event: LogicMethodCompleted) : Record
    private class LogicFailed(val event: LogicMethodFailed) : Record

    /**
     * Starts a new session capture and its background worker.
     *
     * @param clientId The client ID for this session
     * @param clientName The display name for this client
     * @param platform The platform description
     */
    public fun start(clientId: String, clientName: String, platform: String) {
        stopWorker()
        this.clientId = clientId
        this.clientName = clientName
        this.platform = platform
        this.sessionStartTime = currentTimeMillis()
        this.initialStateJson = "{}"
        this.capturedCrash = null

        actionsStorage.clear()
        logicStartedStorage.clear()
        logicCompletedStorage.clear()
        logicFailedStorage.clear()

        val newChannel = Channel<Record>(capacity = 1024)
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        channel = newChannel
        workerScope = newScope
        newScope.launch { runWorker(newChannel) }
        started = true
    }

    /**
     * Provides the store's serializers so the worker can encode module states.
     * Called by IntrospectionMiddleware during initialization.
     */
    public fun attachStateSerializers(serializersModule: SerializersModule) {
        stateJson = reaktivJson(serializersModule)
    }

    /**
     * Captures the initial full state snapshot at session start.
     * Encoding happens on the worker, off the dispatch path.
     */
    public fun captureInitialState(states: Map<String, ModuleState>) {
        enqueue(InitialState(states))
    }

    /**
     * Gets the captured initial state JSON.
     */
    public fun getInitialStateJson(): String = initialStateJson

    /**
     * Checks if session capture has been started.
     */
    public fun isStarted(): Boolean = started

    /**
     * Gets the client ID for this session.
     */
    public fun getClientId(): String = clientId

    /**
     * Captures a dispatched action and its resulting module state.
     * State encoding happens on the worker, off the dispatch path.
     */
    public fun captureDispatchedAction(action: ModuleAction, state: ModuleState) {
        enqueue(DispatchedAction(action, state, currentTimeMillis()))
    }

    /**
     * Captures a pre-built action event.
     */
    public fun captureAction(event: CapturedAction) {
        enqueue(PrebuiltAction(event))
    }

    /**
     * Captures a logic method started event.
     */
    public fun captureLogicStarted(event: LogicMethodStart) {
        enqueue(LogicStarted(event))
    }

    /**
     * Captures a logic method completed event.
     */
    public fun captureLogicCompleted(event: LogicMethodCompleted) {
        enqueue(LogicCompleted(event))
    }

    /**
     * Captures a logic method failed event.
     */
    public fun captureLogicFailed(event: LogicMethodFailed) {
        enqueue(LogicFailed(event))
    }

    /**
     * Reports a crash to the nexus: stores it for export and emits it on [crashes].
     */
    public fun reportCrash(crash: CrashInfo) {
        if (!started) return
        capturedCrash = crash
        _crashes.tryEmit(crash)
    }

    /**
     * Reports a crash from a throwable.
     */
    public fun reportCrash(throwable: Throwable) {
        reportCrash(CrashInfo(timestamp = currentTimeMillis(), exception = throwable.toCrashException()))
    }

    /**
     * Gets the captured crash info if any.
     */
    public fun getCapturedCrash(): CrashInfo? = capturedCrash

    /**
     * Suspends until every record enqueued before this call has been processed.
     */
    public suspend fun flush() {
        val target = enqueuedCount.load()
        while (processedCount.load() < target) {
            delay(1)
        }
    }

    /**
     * Gets the current session history.
     */
    public suspend fun getSessionHistory(): SessionHistory {
        flush()
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
     *
     * @param crash Crash information to embed; defaults to the last reported crash
     * @return JSON string that can be imported as a ghost device in DevTools
     */
    @OptIn(ExperimentalUuidApi::class)
    public suspend fun exportSession(crash: CrashInfo? = capturedCrash): String {
        flush()
        val now = currentTimeMillis()
        val export = SessionExport(
            version = SessionExportFormat.VERSION,
            sessionId = Uuid.random().toString(),
            exportedAt = now,
            clientInfo = ExportedClientInfo(
                clientId = clientId,
                clientName = clientName,
                platform = platform
            ),
            crash = crash,
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
        return json.encodeToString(export)
    }

    /**
     * Reports the throwable as a crash and exports the session including it.
     * Typically called by platform crash handlers for uncaught exceptions.
     */
    public suspend fun exportCrashSession(throwable: Throwable): String {
        reportCrash(throwable)
        return exportSession()
    }

    /**
     * Clears all captured data but keeps the session active.
     */
    public suspend fun clear() {
        flush()
        actionsStorage.clear()
        logicStartedStorage.clear()
        logicCompletedStorage.clear()
        logicFailedStorage.clear()
        capturedCrash = null
    }

    /**
     * Stops the session capture and its worker. Drains pending records before
     * deleting storage so a mid-flight batch cannot resurrect deleted data.
     */
    public suspend fun stop() {
        started = false
        flush()
        stopWorker()
        actionsStorage.delete()
        logicStartedStorage.delete()
        logicCompletedStorage.delete()
        logicFailedStorage.delete()
    }

    private fun stopWorker() {
        channel?.close()
        workerScope?.cancel()
        channel = null
        workerScope = null
        enqueuedCount.store(0L)
        processedCount.store(0L)
    }

    private fun enqueue(record: Record) {
        if (!started) return
        val target = channel ?: return
        if (target.trySend(record).isSuccess) {
            enqueuedCount.addAndFetch(1L)
        }
    }

    private suspend fun runWorker(source: Channel<Record>) {
        for (first in source) {
            val batch = ArrayList<Record>()
            batch.add(first)
            while (true) {
                val next = source.tryReceive().getOrNull() ?: break
                batch.add(next)
            }
            try {
                process(batch)
            } catch (e: Throwable) {
                ReaktivDebug.warn("SessionCapture worker failed to process batch: ${e.message}")
            } finally {
                processedCount.addAndFetch(batch.size.toLong())
            }
        }
    }

    private fun process(batch: List<Record>) {
        val actionLines = ArrayList<String>()
        val startedLines = ArrayList<String>()
        val completedLines = ArrayList<String>()
        val failedLines = ArrayList<String>()

        for (record in batch) {
            try {
                when (record) {
                    is DispatchedAction -> {
                        val event = CapturedAction(
                            clientId = clientId,
                            timestamp = record.timestamp,
                            actionType = record.action::class.simpleName ?: "Unknown",
                            actionData = record.action.toString(),
                            stateDeltaJson = stateJson.encodeToString(
                                PolymorphicSerializer(ModuleState::class), record.state
                            ),
                            moduleName = record.state::class.qualifiedName
                                ?: record.state::class.simpleName ?: "Unknown"
                        )
                        actionLines.add(json.encodeToString(event))
                        _actions.tryEmit(event)
                    }
                    is PrebuiltAction -> {
                        actionLines.add(json.encodeToString(record.event))
                        _actions.tryEmit(record.event)
                    }
                    is InitialState -> {
                        initialStateJson = stateJson.encodeToString(
                            MapSerializer(String.serializer(), PolymorphicSerializer(ModuleState::class)),
                            record.states
                        )
                    }
                    is LogicStarted -> startedLines.add(json.encodeToString(record.event))
                    is LogicCompleted -> completedLines.add(json.encodeToString(record.event))
                    is LogicFailed -> failedLines.add(json.encodeToString(record.event))
                }
            } catch (e: Exception) {
                ReaktivDebug.warn("SessionCapture failed to encode record: ${e.message}")
            }
        }

        if (actionLines.isNotEmpty()) {
            actionsStorage.appendLines(actionLines)
            if (actionsStorage.lineCount() > maxActions + maxActions / 4) {
                actionsStorage.trimTo(maxActions)
            }
        }
        if (startedLines.isNotEmpty()) logicStartedStorage.appendLines(startedLines)
        if (completedLines.isNotEmpty()) logicCompletedStorage.appendLines(completedLines)
        if (failedLines.isNotEmpty()) logicFailedStorage.appendLines(failedLines)
        trimLogicEvents()
    }

    private fun trimLogicEvents() {
        val total = logicStartedStorage.lineCount() +
                logicCompletedStorage.lineCount() +
                logicFailedStorage.lineCount()
        if (total <= maxLogicEvents + maxLogicEvents / 4) return

        var toRemove = total - maxLogicEvents
        for (storage in listOf(logicStartedStorage, logicCompletedStorage, logicFailedStorage)) {
            if (toRemove <= 0) break
            val count = storage.lineCount()
            if (count == 0) continue
            val removeHere = minOf(toRemove, count)
            storage.trimTo(count - removeHere)
            toRemove -= removeHere
        }
    }

    private fun readActions(): List<CapturedAction> =
        actionsStorage.readLines().map { json.decodeFromString(it) }

    private fun readLogicStarted(): List<LogicMethodStart> =
        logicStartedStorage.readLines().map { json.decodeFromString(it) }

    private fun readLogicCompleted(): List<LogicMethodCompleted> =
        logicCompletedStorage.readLines().map { json.decodeFromString(it) }

    private fun readLogicFailed(): List<LogicMethodFailed> =
        logicFailedStorage.readLines().map { json.decodeFromString(it) }
}

/**
 * Represents the current session history.
 */
@Serializable
public data class SessionHistory(
    val startTime: Long,
    val initialStateJson: String = "{}",
    val actions: List<CapturedAction>,
    val logicStarted: List<LogicMethodStart>,
    val logicCompleted: List<LogicMethodCompleted>,
    val logicFailed: List<LogicMethodFailed>
)
