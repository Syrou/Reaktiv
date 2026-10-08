package io.github.syrou.reaktiv.introspection.capture

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.tracing.StateRead
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import kotlin.math.abs
import io.github.syrou.reaktiv.core.util.ReaktivLogSink
import io.github.syrou.reaktiv.introspection.ClientMetadata
import io.github.syrou.reaktiv.core.util.DEFAULT_SENSITIVE_KEYS
import io.github.syrou.reaktiv.introspection.StateRedactor
import io.github.syrou.reaktiv.introspection.WireBudget
import io.github.syrou.reaktiv.introspection.approximateWireBytes
import io.github.syrou.reaktiv.introspection.network.NetworkBodyPart
import io.github.syrou.reaktiv.introspection.network.NetworkBodyProvider
import io.github.syrou.reaktiv.introspection.network.NetworkBodySlice
import io.github.syrou.reaktiv.introspection.network.NetworkBodySource
import io.github.syrou.reaktiv.introspection.network.NetworkEventListener
import io.github.syrou.reaktiv.introspection.network.sliceOnCharBoundary
import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import io.github.syrou.reaktiv.introspection.network.NetworkTap
import io.github.syrou.reaktiv.introspection.SessionRedaction
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.syrou.reaktiv.core.util.currentTimeMillis
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.CapturedLog
import io.github.syrou.reaktiv.introspection.protocol.DeltaKind
import io.github.syrou.reaktiv.introspection.protocol.CrashDiagnosis
import io.github.syrou.reaktiv.introspection.protocol.buildCrashDiagnosis
import io.github.syrou.reaktiv.introspection.protocol.CrashInfo
import io.github.syrou.reaktiv.introspection.protocol.CrashOrigin
import io.github.syrou.reaktiv.introspection.protocol.ExportedClientInfo
import io.github.syrou.reaktiv.introspection.protocol.SessionData
import io.github.syrou.reaktiv.introspection.protocol.SessionExport
import io.github.syrou.reaktiv.introspection.protocol.SessionExportFormat
import io.github.syrou.reaktiv.introspection.protocol.SessionMarker
import io.github.syrou.reaktiv.introspection.protocol.toCrashException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.random.Random
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val CRASH_DEDUP_WINDOW_MS: Long = 1_000L

private const val CRASH_DEDUP_HISTORY: Int = 8

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
    private val maxActions: Int? = null,
    private val maxLogicEvents: Int? = null,
    private val maxLogs: Int? = null,
    private val redactor: StateRedactor? = null,
    private val redactSensitiveKeys: Boolean = true,
    private val sensitiveKeys: Set<String> = DEFAULT_SENSITIVE_KEYS
) {
    private val storageId: String = nextStorageId()

    private val json = reaktivJson(encodeDefaults = true)

    private fun <T> lane(name: String, serializer: KSerializer<T>): CaptureLane<T> =
        CaptureLane(createCaptureStorage("$storageId-$name"), serializer, json)

    private val actionsLane = lane("actions", CapturedAction.serializer())
    private val logicStartedLane = lane("logic_started", LogicMethodStart.serializer())
    private val logicCompletedLane = lane("logic_completed", LogicMethodCompleted.serializer())
    private val logicFailedLane = lane("logic_failed", LogicMethodFailed.serializer())
    private val crashLane = lane("crashes", CrashInfo.serializer())
    private val stateReadLane = lane("state_reads", StateRead.serializer())
    private val markerLane = lane("markers", SessionMarker.serializer())
    private val networkLane = lane("network", NetworkRequestCapture.serializer())
    private val logLane = lane("logs", CapturedLog.serializer())

    private val lanes: List<CaptureLane<*>> = listOf(
        actionsLane,
        logicStartedLane,
        logicCompletedLane,
        logicFailedLane,
        crashLane,
        stateReadLane,
        markerLane,
        networkLane,
        logLane,
    )

    private var sessionStartTime: Long = 0
    private var clientId: String = ""
    private var clientName: String = ""
    private var platform: String = ""
    private var clientMetadata: ClientMetadata? = null
    private var started = false
    private var initialStateJson: String = "{}"
    private var capturedCrash: CrashInfo? = null

    @Volatile
    private var actionsTrimmed = 0

    @Volatile
    private var baselineWanted = true
    private val recentCrashes = ArrayDeque<Pair<Triple<String, String?, String>, Long>>()
    private val droppedCount = AtomicLong(0L)

    public var stateJson: Json = reaktivJson(encodeDefaults = true)
        private set

    private var networkListener: NetworkEventListener? = null
    private var logSink: ReaktivLogSink? = null
    private var networkBodyProvider: NetworkBodyProvider? = null

    private val extensionState = MutableStateFlow<Map<String, JsonElement>>(emptyMap())

    public val extensions: StateFlow<Map<String, JsonElement>> = extensionState
    private val cachedBody = AtomicReference<CachedBody?>(null)

    private var workerScope: CoroutineScope? = null
    private var channel: Channel<Record>? = null
    private val enqueuedCount = AtomicLong(0L)
    private val processedCount = MutableStateFlow(0L)

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

    private val _stateReads = MutableSharedFlow<StateRead>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    public val stateReads: SharedFlow<StateRead> = _stateReads

    private val _markers = MutableSharedFlow<SessionMarker>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    public val markers: SharedFlow<SessionMarker> = _markers

    private val _logicEvents = MutableSharedFlow<CapturedLogicEvent>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    public val logicEvents: SharedFlow<CapturedLogicEvent> = _logicEvents

    private val _logs = MutableSharedFlow<CapturedLog>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    public val logs: SharedFlow<CapturedLog> = _logs

    private val _network = MutableSharedFlow<NetworkRequestCapture>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    public val network: SharedFlow<NetworkRequestCapture> = _network

    private sealed interface Record
    private class DispatchedAction(val action: ModuleAction, val state: ModuleState, val timestamp: Long) : Record
    private class PrebuiltAction(val event: CapturedAction) : Record
    private class InitialState(val states: Map<String, ModuleState>) : Record
    private class LogicStarted(val event: LogicMethodStart) : Record
    private class LogicCompleted(val event: LogicMethodCompleted) : Record
    private class LogicFailed(val event: LogicMethodFailed) : Record
    private class CrashRecord(val info: CrashInfo) : Record
    private class StateReadRecord(val read: StateRead) : Record
    private class MarkerRecord(val marker: SessionMarker, val historical: Boolean) : Record
    private class NetworkRecord(val capture: NetworkRequestCapture, val preview: NetworkRequestCapture) : Record
    private class LogRecord(val log: CapturedLog) : Record

    private class CachedBody(
        val requestId: String,
        val part: NetworkBodyPart,
        val bytes: ByteArray
    )

    private object ResetWorkerState : Record

    /**
     * Starts a new session capture and its background worker.
     *
     * @param clientId The client ID for this session
     * @param clientName The display name for this client
     * @param platform The platform description
     */
    public fun start(
        clientId: String,
        clientName: String,
        platform: String,
        metadata: ClientMetadata? = null
    ) {
        stopWorker()
        this.clientId = clientId
        this.clientName = clientName
        this.platform = platform
        this.clientMetadata = metadata
        this.sessionStartTime = currentTimeMillis()
        this.initialStateJson = "{}"
        this.capturedCrash = null
        this.actionsTrimmed = 0
        this.baselineWanted = true
        droppedCount.store(0L)

        lanes.forEach { it.clear() }

        attachNetworkListener()
        attachLogSink()

        val newChannel = Channel<Record>(capacity = Channel.UNLIMITED)
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        channel = newChannel
        workerScope = newScope
        newScope.launch { runWorker(newChannel) }
        started = true
        enqueue(ResetWorkerState)
    }

    /**
     * Records a completed network exchange, materialising its full bodies before they age out.
     *
     * The event carried by [NetworkTap] holds only a bounded preview. The full body lives in the
     * emitting plugin's retention window and is evicted as later requests arrive, so it has to be
     * pulled now rather than at export time, when it would already be gone.
     */
    public fun recordNetworkExchange(event: NetworkRequestCapture) {
        enqueue(NetworkRecord(materialise(event), event))
    }

    private fun materialise(event: NetworkRequestCapture): NetworkRequestCapture {
        val request = NetworkTap.originBody(event.id, NetworkBodyPart.REQUEST)
        val response = NetworkTap.originBody(event.id, NetworkBodyPart.RESPONSE)
        return event.copy(
            requestBody = request ?: event.requestBody,
            requestBodyTruncated = if (request != null) false else event.requestBodyTruncated,
            responseBody = response ?: event.responseBody,
            responseBodyTruncated = if (response != null) false else event.responseBodyTruncated
        )
    }

    /**
     * Serves a body from the capture lane when the emitting plugin has already evicted it.
     *
     * The plugin keeps a small rolling window so a live UI can inspect recent traffic, and older
     * bodies used to be gone for good. The lane keeps every body it recorded, so registering as a
     * fallback provider means scrolling back past the window still resolves, and a ghost session
     * and a live one behave the same way.
     */
    private fun sliceFromLane(
        requestId: String,
        part: NetworkBodyPart,
        offset: Int,
        maxBytes: Int
    ): NetworkBodySlice? {
        val cached = cachedBody.load()
        val bytes = if (cached != null && cached.requestId == requestId && cached.part == part) {
            cached.bytes
        } else {
            val body = findBody(requestId, part) ?: return null
            body.encodeToByteArray().also {
                cachedBody.store(CachedBody(requestId, part, it))
            }
        }
        return bytes.sliceOnCharBoundary(offset, maxBytes)
    }

    /**
     * Finds one exchange's body without decoding the whole lane.
     *
     * The lane holds every body the session captured, so decoding each record to look for one id
     * would mean parsing megabytes to answer a single slice. Records carry their id verbatim in the
     * JSON, so a substring check rejects almost every line before any parsing happens.
     */
    private fun findBody(requestId: String, part: NetworkBodyPart): String? {
        val needle = "\"id\":\"$requestId\""
        val line = networkLane.lines().lastOrNull { it.contains(needle) } ?: return null
        val exchange = runCatching { networkLane.decode(line) }.getOrNull() ?: return null
        if (exchange.id != requestId) return null
        return when (part) {
            NetworkBodyPart.REQUEST -> exchange.requestBody
            NetworkBodyPart.RESPONSE -> exchange.responseBody
        }
    }

    public fun captureLog(level: String, category: String, message: String) {
        if (category == SELF_LOG_CATEGORY) return
        enqueue(LogRecord(CapturedLog(level, category, message, currentTimeMillis())))
    }

    private fun attachLogSink() {
        detachLogSink()
        val sink = ReaktivLogSink { level, category, message ->
            captureLog(level, category, message)
        }
        logSink = sink
        ReaktivDebug.addSink(sink)
    }

    private fun detachLogSink() {
        logSink?.let { ReaktivDebug.removeSink(it) }
        logSink = null
    }

    private fun attachNetworkListener() {
        detachNetworkListener()
        detachLogSink()
        val listener = NetworkEventListener { event -> recordNetworkExchange(event) }
        networkListener = listener
        NetworkTap.addListener(listener)

        val provider = object : NetworkBodyProvider {
            override val source: NetworkBodySource = NetworkBodySource.Archive

            override fun slice(requestId: String, part: NetworkBodyPart, offset: Int, maxBytes: Int): NetworkBodySlice? =
                sliceFromLane(requestId, part, offset, maxBytes)
        }
        networkBodyProvider = provider
        NetworkTap.addBodyProvider(provider)
    }

    private fun detachNetworkListener() {
        networkListener?.let { NetworkTap.removeListener(it) }
        networkListener = null
        networkBodyProvider?.let { NetworkTap.removeBodyProvider(it) }
        networkBodyProvider = null
        cachedBody.store(null)
    }

    /**
     * Provides the store's serializers so the worker can encode module states.
     * Called by ToolingLogic during initialization.
     */
    public fun attachStateSerializers(serializersModule: SerializersModule) {
        stateJson = reaktivJson(serializersModule, encodeDefaults = true)
    }

    internal fun putExtension(key: String, value: JsonElement) {
        extensionState.update { it + (key to value) }
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
    @Deprecated("Unused. Read initialStateJson from getSessionHistory().", level = DeprecationLevel.WARNING)
    public fun getInitialStateJson(): String = initialStateJson

    /**
     * Checks if session capture has been started.
     */
    public fun isStarted(): Boolean = started

    /**
     * Gets the client ID for this session.
     */
    @Deprecated("Unused. The client id is the one passed to start().", level = DeprecationLevel.WARNING)
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
    @Deprecated(
        "Only tests call this. It will become internal. Dispatch through the store instead.",
        level = DeprecationLevel.WARNING
    )
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

    public fun captureStateRead(read: StateRead) {
        enqueue(StateReadRecord(read))
    }

    @OptIn(ExperimentalUuidApi::class)
    public fun addMarker(
        label: String,
        note: String = "",
        source: String = "device",
        timestampMs: Long? = null,
        afterActionIndex: Int = -1
    ) {
        enqueue(
            MarkerRecord(
                SessionMarker(
                    id = Uuid.random().toString(),
                    label = label,
                    note = note,
                    timestampMs = timestampMs ?: currentTimeMillis(),
                    afterActionIndex = afterActionIndex,
                    source = source
                ),
                historical = timestampMs != null
            )
        )
    }

    /**
     * Suggests an export file name carrying client identity and app version.
     * The prefix defaults to crash when a crash has been captured, session otherwise.
     */
    public fun suggestFileName(prefix: String? = null): String {
        val effectivePrefix = prefix ?: if (capturedCrash != null) "crash" else "session"
        val client = clientName.ifBlank { "client" }.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val version = clientMetadata?.appVersion ?: "na"
        return "reaktiv_${effectivePrefix}_${client}_${version}_${currentTimeMillis()}.json.gz"
    }

    /**
     * Reports a crash to the nexus: stores it for export and emits it on [crashes].
     */
    public fun reportCrash(crash: CrashInfo) {
        if (!started) return
        enqueue(CrashRecord(crash))
    }

    private fun isRepeatedCrash(info: CrashInfo): Boolean {
        val key = Triple(info.exception.exceptionType, info.exception.message, info.exception.stackTrace)
        val repeated = recentCrashes.any { (seen, at) ->
            seen == key && abs(info.timestamp - at) <= CRASH_DEDUP_WINDOW_MS
        }
        if (!repeated) {
            recentCrashes.addLast(key to info.timestamp)
            if (recentCrashes.size > CRASH_DEDUP_HISTORY) recentCrashes.removeFirst()
        }
        return repeated
    }

    /**
     * Reports a crash from a throwable.
     */
    public fun reportCrash(throwable: Throwable, origin: CrashOrigin = CrashOrigin.MANUAL) {
        reportCrash(
            CrashInfo(
                timestamp = currentTimeMillis(),
                exception = throwable.toCrashException(),
                origin = origin
            )
        )
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
        processedCount.first { it >= target }
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
            logicFailed = readLogicFailed(),
            stateReads = readStateReads(),
            markers = readMarkers(),
            network = readNetwork(),
            logs = readLogs(),
            extensions = extensionState.value
        )
    }

    /**
     * Exports the current session as a JSON string.
     *
     * @param crash Crash information to embed; defaults to the last reported crash
     * @return JSON string that can be imported as a ghost device in DevTools
     */
    @OptIn(ExperimentalUuidApi::class)
    public suspend fun exportSession(crash: CrashInfo? = null): String {
        flush()
        val allCrashes = readCrashes()
        val resolvedCrash = crash
            ?: capturedCrash?.let { it.copy(afterActionIndex = onLane(it.afterActionIndex)) }
            ?: allCrashes.lastOrNull()
        val now = currentTimeMillis()
        val redaction = SessionRedaction(stateJson, if (redactSensitiveKeys) sensitiveKeys else emptySet(), redactor)
        val actionsList = readActions().map(redaction::action)
        val logicStartedList = readLogicStarted().map(redaction::logicStart)
        val logicCompletedList = readLogicCompleted().map(redaction::logicCompleted)
        val logicFailedList = readLogicFailed()
        val stateReadsList = readStateReads()
        val diagnosis = resolvedCrash?.let {
            buildCrashDiagnosis(it, actionsList, logicStartedList, logicFailedList)
        }
        val export = SessionExport(
            version = SessionExportFormat.VERSION,
            sessionId = Uuid.random().toString(),
            exportedAt = now,
            clientInfo = ExportedClientInfo(
                clientId = clientId,
                clientName = clientName,
                platform = platform,
                metadata = clientMetadata
            ),
            crash = resolvedCrash,
            crashes = allCrashes,
            session = SessionData(
                startTime = sessionStartTime,
                endTime = now,
                initialStateJson = redaction.initialState(initialStateJson),
                actions = actionsList,
                logicStartedEvents = logicStartedList,
                logicCompletedEvents = logicCompletedList,
                logicFailedEvents = logicFailedList,
                stateReads = stateReadsList,
                markers = readMarkers(),
                network = readNetwork().map(redaction::exchange),
                logs = readLogs().map(redaction::log)
            ),
            droppedRecords = droppedCount.load(),
            diagnosis = diagnosis,
            extensions = extensionState.value
        )
        redaction.issues.forEach { issue ->
            ReaktivDebug.warn("SessionCapture: $issue, so an import cannot restore this field exactly")
        }
        return json.encodeToString(export)
    }

    public suspend fun diagnoseCrash(crash: CrashInfo): CrashDiagnosis {
        flush()
        return buildCrashDiagnosis(crash, readActions(), readLogicStarted(), readLogicFailed())
    }

    /**
     * Reports the throwable as a crash and exports the session including it.
     * Typically called by platform crash handlers for uncaught exceptions.
     */
    public suspend fun exportCrashSession(throwable: Throwable): String {
        reportCrash(throwable, CrashOrigin.UNCAUGHT)
        return exportSession()
    }

    /**
     * Clears all captured data but keeps the session active.
     */
    public suspend fun clear() {
        baselineWanted = true
        if (!started) {
            lanes.forEach { it.clear() }
            capturedCrash = null
            return
        }
        enqueue(ResetWorkerState)
        flush()
    }

    internal fun takeBaselineRequest(): Boolean {
        if (!baselineWanted) return false
        baselineWanted = false
        return true
    }

    /**
     * Stops the session capture and its worker. Drains pending records before
     * deleting storage so a mid-flight batch cannot resurrect deleted data.
     */
    public suspend fun stop() {
        started = false
        detachNetworkListener()
        detachLogSink()
        flush()
        stopWorker()
        lanes.forEach { it.clear() }
    }

    private fun stopWorker() {
        channel?.close()
        workerScope?.cancel()
        channel = null
        workerScope = null
        processedCount.value = enqueuedCount.load()
    }

    private fun enqueue(record: Record) {
        if (!started) return
        val target = channel ?: return
        if (enqueuedCount.load() - processedCount.value >= HIGH_WATER_MARK) {
            droppedCount.addAndFetch(1L)
            return
        }
        enqueuedCount.addAndFetch(1L)
        if (target.trySend(record).isFailure) {
            processedCount.update { it + 1L }
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
                ReaktivDebug.error(SELF_LOG_CATEGORY, "Worker failed to process batch: ${e.message}", e)
            } finally {
                processedCount.update { it + batch.size.toLong() }
            }
        }
    }

    private val previousModuleJson = mutableMapOf<String, JsonObject>()
    private var actionCount = 0
    private val callIdentities = LinkedHashMap<String, Pair<String, String>>()

    private fun rememberCall(event: LogicMethodStart) {
        callIdentities[event.callId] = event.logicClass to event.methodName
        if (callIdentities.size > CALL_IDENTITY_LIMIT) callIdentities.remove(callIdentities.keys.first())
    }

    private fun CrashInfo.withLogicIdentity(): CrashInfo {
        if (logicClass != null) return this
        val identity = callId?.let { callIdentities.remove(it) } ?: return this
        return copy(logicClass = identity.first, methodName = identity.second)
    }

    private fun currentRouteFromShadow(): String? {
        val navKey = previousModuleJson.keys.firstOrNull { it.endsWith(".NavigationState") } ?: return null
        val currentEntry = previousModuleJson[navKey]?.get("currentEntry") as? JsonObject ?: return null
        return (currentEntry["path"] as? JsonPrimitive)?.content
    }

    private fun encodeModuleObject(state: ModuleState): JsonObject =
        stateJson.encodeToJsonElement(PolymorphicSerializer(ModuleState::class), state) as? JsonObject
            ?: buildJsonObject {}

    /**
     * Encodes a state tree one module at a time with the encoder the capture uses for its deltas.
     */
    public fun encodeStateTree(states: Map<String, ModuleState>): StateTree {
        val failed = LinkedHashMap<String, String>()
        val modules = buildJsonObject {
            states.forEach { (moduleName, state) ->
                try {
                    put(moduleName, encodeModuleObject(state))
                } catch (e: Exception) {
                    failed[moduleName] = e.message ?: "encode failed"
                }
            }
        }
        return StateTree(modules, failed)
    }

    private fun diffAgainstShadow(moduleName: String, full: JsonObject): Pair<String, DeltaKind> {
        val previous = previousModuleJson[moduleName]
        previousModuleJson[moduleName] = full
        if (previous == null || previous["type"] != full["type"]) {
            return full.toString() to DeltaKind.FULL
        }
        val changed = buildJsonObject {
            full["type"]?.let { put("type", it) }
            full.forEach { (key, value) ->
                if (key != "type" && previous[key] != value) {
                    put(key, value)
                }
            }
        }
        return changed.toString() to DeltaKind.FIELDS
    }

    private suspend fun process(batch: List<Record>) {
        for (record in batch) {
            try {
                when (record) {
                    is DispatchedAction -> {
                        val moduleName = record.state::class.qualifiedName
                            ?: record.state::class.simpleName ?: "Unknown"
                        val full = encodeModuleObject(record.state)
                        val (deltaJson, deltaKind) = diffAgainstShadow(moduleName, full)
                        val event = CapturedAction(
                            clientId = clientId,
                            timestamp = record.timestamp,
                            actionType = record.action::class.simpleName ?: "Unknown",
                            actionData = record.action.toString(),
                            stateDeltaJson = deltaJson,
                            moduleName = moduleName,
                            deltaKind = deltaKind
                        )
                        actionsLane.add(event)
                        actionCount += 1
                        _actions.tryEmit(event)
                    }
                    is PrebuiltAction -> {
                        actionsLane.add(record.event)
                        actionCount += 1
                        _actions.tryEmit(record.event)
                    }
                    is InitialState -> {
                        val tree = encodeStateTree(record.states)
                        tree.failed.forEach { (moduleName, reason) ->
                            ReaktivDebug.warn("SessionCapture: cannot capture $moduleName - $reason")
                        }
                        tree.modules.forEach { (key, value) -> (value as? JsonObject)?.let { previousModuleJson[key] = it } }
                        initialStateJson = tree.modules.toString()
                    }
                    is NetworkRecord -> {
                        networkLane.add(record.capture)
                        _network.tryEmit(record.preview)
                    }
                    is LogicStarted -> {
                        rememberCall(record.event)
                        logicStartedLane.add(record.event)
                        _logicEvents.tryEmit(CapturedLogicEvent.Started(record.event))
                    }
                    is LogicCompleted -> {
                        callIdentities.remove(record.event.callId)
                        logicCompletedLane.add(record.event)
                        _logicEvents.tryEmit(CapturedLogicEvent.Completed(record.event))
                    }
                    is LogicFailed -> {
                        logicFailedLane.add(record.event)
                        _logicEvents.tryEmit(CapturedLogicEvent.Failed(record.event))
                    }
                    is StateReadRecord -> {
                        stateReadLane.add(record.read)
                        _stateReads.tryEmit(record.read)
                    }
                    is ResetWorkerState -> {
                        previousModuleJson.clear()
                        callIdentities.clear()
                        actionCount = 0
                        actionsTrimmed = 0
                        initialStateJson = "{}"
                        capturedCrash = null
                        lanes.forEach { it.clear() }
                    }
                    is LogRecord -> {
                        logLane.add(record.log)
                        _logs.tryEmit(record.log)
                    }
                    is MarkerRecord -> {
                        val enriched = record.marker.copy(
                            route = record.marker.route
                                ?: if (record.historical) null else currentRouteFromShadow(),
                            afterActionIndex = if (record.marker.afterActionIndex >= 0) {
                                record.marker.afterActionIndex
                            } else {
                                actionCount - 1
                            }
                        )
                        markerLane.add(enriched)
                        _markers.tryEmit(enriched)
                    }
                    is CrashRecord -> {
                        if (!isRepeatedCrash(record.info)) {
                            val enriched = record.info.withLogicIdentity().copy(
                                route = record.info.route ?: currentRouteFromShadow(),
                                afterActionIndex = if (record.info.afterActionIndex >= 0) {
                                    record.info.afterActionIndex
                                } else {
                                    actionCount - 1
                                }
                            )
                            crashLane.add(enriched)
                            capturedCrash = enriched
                            _crashes.tryEmit(enriched)
                        }
                    }
                }
            } catch (e: Exception) {
                ReaktivDebug.error(SELF_LOG_CATEGORY, "Failed to encode record: ${e.message}", e)
            }
        }

        lanes.forEach { it.flush() }
        actionsTrimmed += actionsLane.trimAbove(maxActions)
        logLane.trimAbove(maxLogs)
        trimLogicEvents()
    }

    private fun trimLogicEvents() {
        val maxLogicEvents = this.maxLogicEvents ?: return
        val total = logicStartedLane.lineCount() + logicCompletedLane.lineCount() + logicFailedLane.lineCount()
        if (total <= maxLogicEvents + maxLogicEvents / 4) return

        val started = logicStartedLane.lines()
        val completed = logicCompletedLane.lines()
        val failed = logicFailedLane.lines()
        val startedIds = started.map { logicStartedLane.decode(it).callId }
        val completedIds = completed.map { logicCompletedLane.decode(it).callId }
        val failedIds = failed.map { logicFailedLane.decode(it).callId }
        val endings = (completedIds + failedIds).groupingBy { it }.eachCount()

        var excess = total - maxLogicEvents
        val dropped = HashSet<String>()
        for (callId in startedIds) {
            if (excess <= 0) break
            dropped += callId
            excess -= 1 + (endings[callId] ?: 0)
        }

        dropCalls(logicStartedLane, started, startedIds, dropped)
        dropCalls(logicCompletedLane, completed, completedIds, dropped)
        dropCalls(logicFailedLane, failed, failedIds, dropped)
    }

    private fun dropCalls(lane: CaptureLane<*>, lines: List<String>, callIds: List<String>, dropped: Set<String>) {
        val kept = lines.filterIndexed { index, _ -> callIds[index] !in dropped }
        if (kept.size != lines.size) lane.replace(kept)
    }

    private fun onLane(index: Int): Int = if (index < 0) index else (index - actionsTrimmed).coerceAtLeast(-1)

    private fun readActions(): List<CapturedAction> = actionsLane.read()

    private fun readLogicStarted(): List<LogicMethodStart> = logicStartedLane.read()

    private fun readLogicCompleted(): List<LogicMethodCompleted> = logicCompletedLane.read()

    private fun readLogicFailed(): List<LogicMethodFailed> = logicFailedLane.read()

    private fun readCrashes(): List<CrashInfo> =
        crashLane.read().map { it.copy(afterActionIndex = onLane(it.afterActionIndex)) }

    private fun readNetwork(): List<NetworkRequestCapture> = networkLane.read()

    private fun readStateReads(): List<StateRead> = stateReadLane.read()

    private fun readLogs(): List<CapturedLog> = logLane.read()

    private fun readMarkers(): List<SessionMarker> =
        markerLane.read().map { it.copy(afterActionIndex = onLane(it.afterActionIndex)) }

    public companion object {
        /**
         * The `logicClass` this capture emits for redaction watchdog spans.
         *
         * Public because tooling downstream filters synthetic spans out of the narrative event
         * stream by name, so the name is part of this module's contract rather than an internal
         * detail.
         */
        @Deprecated(
            "Capture no longer redacts, so no watchdog spans are emitted. Removed in the next release.",
            level = DeprecationLevel.WARNING
        )
        public const val REDACTION_TRACE_CLASS: String = "RedactionWatchdog"

        /**
         * The log category this capture files its own diagnostics under.
         *
         * [captureLog] drops lines in this category, so a capture never records its own failure
         * reports. Without that, a failure to encode a record would log, be captured, and be
         * encoded again, which is the one path where the log lane could amplify itself.
         */
        public const val SELF_LOG_CATEGORY: String = "SessionCapture"
    }
}

private const val HIGH_WATER_MARK: Long = 50_000L
private const val CALL_IDENTITY_LIMIT: Int = 512

/**
 * A state tree encoded one module at a time, with the modules that could not be encoded and why.
 */
public class StateTree(public val modules: JsonObject, public val failed: Map<String, String>)

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
    val logicFailed: List<LogicMethodFailed>,
    val stateReads: List<StateRead> = emptyList(),
    val markers: List<SessionMarker> = emptyList(),
    val network: List<NetworkRequestCapture> = emptyList(),
    val logs: List<CapturedLog> = emptyList(),
    val extensions: Map<String, JsonElement> = emptyMap()
)

/**
 * Splits a history into pieces small enough to send as individual messages.
 *
 * Actions and logic events are cut by count, which tracks their size closely enough. Network
 * exchanges are cut by whichever comes first, count or [WireBudget.MAX_PAYLOAD_BYTES] of estimated
 * payload, because a single exchange carries full request and response bodies and can be larger on
 * its own than a thousand logic events.
 *
 * @param actionsPerChunk Maximum actions in one chunk
 * @param eventsPerChunk Maximum logic events of each kind in one chunk
 * @param networkPerChunk Upper bound on exchanges per chunk, before the byte budget applies
 * @param networkBytesPerChunk Estimated payload budget for the exchanges in one chunk
 */
public fun SessionHistory.chunked(
    actionsPerChunk: Int = 250,
    eventsPerChunk: Int = 1000,
    networkPerChunk: Int = 50,
    networkBytesPerChunk: Int = WireBudget.MAX_PAYLOAD_BYTES
): List<SessionHistory> {
    fun chunksNeeded(size: Int, per: Int): Int = if (size == 0) 0 else (size + per - 1) / per

    val networkGroups = ArrayList<List<NetworkRequestCapture>>()
    var current = ArrayList<NetworkRequestCapture>()
    var currentBytes = 0
    for (exchange in network) {
        val weight = exchange.approximateWireBytes()
        val wouldExceedBytes = current.isNotEmpty() && currentBytes + weight > networkBytesPerChunk
        val wouldExceedCount = current.size >= networkPerChunk
        if (wouldExceedBytes || wouldExceedCount) {
            networkGroups.add(current)
            current = ArrayList()
            currentBytes = 0
        }
        current.add(exchange)
        currentBytes += weight
    }
    if (current.isNotEmpty()) networkGroups.add(current)

    val totalChunks = maxOf(
        chunksNeeded(actions.size, actionsPerChunk),
        chunksNeeded(logicStarted.size, eventsPerChunk),
        chunksNeeded(logicCompleted.size, eventsPerChunk),
        chunksNeeded(logicFailed.size, eventsPerChunk),
        chunksNeeded(logs.size, eventsPerChunk),
        networkGroups.size,
        1
    )
    fun <T> slice(list: List<T>, index: Int, per: Int): List<T> {
        val from = index * per
        if (from >= list.size) return emptyList()
        return list.subList(from, minOf(from + per, list.size)).toList()
    }
    return List(totalChunks) { index ->
        SessionHistory(
            startTime = startTime,
            initialStateJson = if (index == 0) initialStateJson else "{}",
            actions = slice(actions, index, actionsPerChunk),
            logicStarted = slice(logicStarted, index, eventsPerChunk),
            logicCompleted = slice(logicCompleted, index, eventsPerChunk),
            logicFailed = slice(logicFailed, index, eventsPerChunk),
            stateReads = if (index == 0) stateReads else emptyList(),
            markers = if (index == 0) markers else emptyList(),
            network = networkGroups.getOrElse(index) { emptyList() },
            logs = slice(logs, index, eventsPerChunk),
            extensions = if (index == 0) extensions else emptyMap()
        )
    }
}


@OptIn(ExperimentalAtomicApi::class)
private val storageIdCounter = AtomicLong(0L)

private val storageIdPrefix: String by lazy {
    "${currentTimeMillis()}-${Random.nextInt(Int.MAX_VALUE)}"
}

@OptIn(ExperimentalAtomicApi::class)
private fun nextStorageId(): String = "$storageIdPrefix-${storageIdCounter.addAndFetch(1L)}"
