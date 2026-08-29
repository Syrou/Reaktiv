package io.github.syrou.reaktiv.introspection.capture

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.tracing.LogicTracer
import io.github.syrou.reaktiv.core.tracing.StateRead
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.introspection.ClientMetadata
import io.github.syrou.reaktiv.introspection.DEFAULT_SENSITIVE_KEYS
import io.github.syrou.reaktiv.introspection.StateRedactor
import io.github.syrou.reaktiv.introspection.WireBudget
import io.github.syrou.reaktiv.introspection.approximateWireBytes
import io.github.syrou.reaktiv.introspection.network.NetworkBodyPart
import io.github.syrou.reaktiv.introspection.network.NetworkBodyProvider
import io.github.syrou.reaktiv.introspection.network.NetworkBodySlice
import io.github.syrou.reaktiv.introspection.network.NetworkEventListener
import io.github.syrou.reaktiv.introspection.network.sliceOnCharBoundary
import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import io.github.syrou.reaktiv.introspection.network.NetworkTap
import io.github.syrou.reaktiv.introspection.normalizeRedactionKey
import io.github.syrou.reaktiv.introspection.redactModuleElement
import io.github.syrou.reaktiv.introspection.restoreRedactedModuleElement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.syrou.reaktiv.core.util.currentTimeMillis
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.DeltaKind
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
import kotlin.concurrent.atomics.AtomicReference
import kotlin.random.Random
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
    private val maxActions: Int? = null,
    private val maxLogicEvents: Int? = null,
    private val redactor: StateRedactor? = null,
    private val redactSensitiveKeys: Boolean = true
) {
    private val storageId: String = nextStorageId()

    private val actionsStorage: CaptureStorage = createCaptureStorage("$storageId-actions")
    private val logicStartedStorage: CaptureStorage = createCaptureStorage("$storageId-logic_started")
    private val logicCompletedStorage: CaptureStorage = createCaptureStorage("$storageId-logic_completed")
    private val logicFailedStorage: CaptureStorage = createCaptureStorage("$storageId-logic_failed")
    private val crashStorage: CaptureStorage = createCaptureStorage("$storageId-crashes")
    private val stateReadStorage: CaptureStorage = createCaptureStorage("$storageId-state_reads")
    private val markerStorage: CaptureStorage = createCaptureStorage("$storageId-markers")
    private val networkStorage: CaptureStorage = createCaptureStorage("$storageId-network")

    private val allStorages: List<CaptureStorage> = listOf(
        actionsStorage,
        logicStartedStorage,
        logicCompletedStorage,
        logicFailedStorage,
        crashStorage,
        stateReadStorage,
        markerStorage,
        networkStorage,
    )

    private var sessionStartTime: Long = 0
    private var clientId: String = ""
    private var clientName: String = ""
    private var platform: String = ""
    private var clientMetadata: ClientMetadata? = null
    private var started = false
    private var initialStateJson: String = "{}"
    private var capturedCrash: CrashInfo? = null
    private val droppedCount = AtomicLong(0L)

    private val json = reaktivJson(encodeDefaults = true)
    private var stateJson: Json = reaktivJson()

    private var networkListener: NetworkEventListener? = null
    private var networkBodyProvider: NetworkBodyProvider? = null
    private val materialisingId = AtomicReference<String?>(null)
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
    private class NetworkRecord(val capture: NetworkRequestCapture) : Record

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
        droppedCount.store(0L)

        allStorages.forEach { it.clear() }

        attachNetworkListener()

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
        materialisingId.store(event.id)
        val enriched = try {
            materialise(event)
        } finally {
            materialisingId.store(null)
        }
        enqueue(NetworkRecord(enriched))
    }

    private fun materialise(event: NetworkRequestCapture): NetworkRequestCapture {
        val request = fullBody(event.id, NetworkBodyPart.REQUEST)
        val response = fullBody(event.id, NetworkBodyPart.RESPONSE)
        return event.copy(
            requestBody = request ?: event.requestBody,
            requestBodyTruncated = if (request != null) false else event.requestBodyTruncated,
            responseBody = response ?: event.responseBody,
            responseBodyTruncated = if (response != null) false else event.responseBodyTruncated
        )
    }

    private fun fullBody(requestId: String, part: NetworkBodyPart): String? {
        val builder = StringBuilder()
        var offset = 0
        while (true) {
            val slice = NetworkTap.bodySlice(requestId, part, offset, BODY_SLICE_BYTES) ?: return null
            builder.append(slice.content)
            if (slice.isLast || slice.nextOffset <= offset) break
            offset = slice.nextOffset
        }
        return builder.toString().takeIf { it.isNotEmpty() }
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
        if (materialisingId.load() == requestId) return null

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
        val line = networkStorage.readLines().lastOrNull { it.contains(needle) } ?: return null
        val exchange = runCatching { json.decodeFromString<NetworkRequestCapture>(line) }.getOrNull()
            ?: return null
        if (exchange.id != requestId) return null
        return when (part) {
            NetworkBodyPart.REQUEST -> exchange.requestBody
            NetworkBodyPart.RESPONSE -> exchange.responseBody
        }
    }

    private fun attachNetworkListener() {
        detachNetworkListener()
        val listener = NetworkEventListener { event -> recordNetworkExchange(event) }
        networkListener = listener
        NetworkTap.addListener(listener)

        val provider = NetworkBodyProvider { requestId, part, offset, maxBytes ->
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
     * Called by IntrospectionMiddleware during initialization.
     */
    public fun attachStateSerializers(serializersModule: SerializersModule) {
        stateJson = reaktivJson(serializersModule, encodeDefaults = true)
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
            network = readNetwork()
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
        val resolvedCrash = crash ?: capturedCrash ?: allCrashes.lastOrNull()
        val now = currentTimeMillis()
        val actionsList = readActions()
        val logicStartedList = readLogicStarted()
        val logicCompletedList = readLogicCompleted()
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
                initialStateJson = initialStateJson,
                actions = actionsList,
                logicStartedEvents = logicStartedList,
                logicCompletedEvents = logicCompletedList,
                logicFailedEvents = logicFailedList,
                stateReads = stateReadsList,
                markers = readMarkers(),
                network = readNetwork()
            ),
            droppedRecords = droppedCount.load(),
            diagnosis = diagnosis
        )
        return json.encodeToString(export)
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
        flush()
        allStorages.forEach { it.clear() }
        capturedCrash = null
        enqueue(ResetWorkerState)
        flush()
    }

    /**
     * Stops the session capture and its worker. Drains pending records before
     * deleting storage so a mid-flight batch cannot resurrect deleted data.
     */
    public suspend fun stop() {
        started = false
        detachNetworkListener()
        flush()
        stopWorker()
        allStorages.forEach { it.delete() }
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
                ReaktivDebug.warn("SessionCapture worker failed to process batch: ${e.message}")
            } finally {
                processedCount.update { it + batch.size.toLong() }
            }
        }
    }

    private val previousModuleJson = mutableMapOf<String, JsonObject>()
    private var actionCount = 0
    private val normalizedSensitiveKeys = DEFAULT_SENSITIVE_KEYS.map { it.normalizeRedactionKey() }
    private val reportedRedactionIssues = mutableSetOf<String>()
    private val pendingRedactionIssues = ArrayList<String>()

    private fun currentRouteFromShadow(): String? {
        val navKey = previousModuleJson.keys.firstOrNull { it.endsWith(".NavigationState") } ?: return null
        val currentEntry = previousModuleJson[navKey]?.get("currentEntry") as? JsonObject ?: return null
        return (currentEntry["path"] as? JsonPrimitive)?.content
    }

    private fun encodeModuleObject(moduleName: String, state: ModuleState): JsonObject {
        val element = stateJson.encodeToJsonElement(PolymorphicSerializer(ModuleState::class), state)
        var current: JsonElement = element
        if (redactSensitiveKeys) {
            val obj = current as? JsonObject
            val strategy = stateJson.serializersModule.getPolymorphic(ModuleState::class, state)
            if (obj != null && strategy != null) {
                val outcome = redactModuleElement(
                    stateJson.serializersModule, strategy.descriptor, obj, normalizedSensitiveKeys
                )
                current = outcome.element
                outcome.unrestorablePaths.forEach { unsafePath ->
                    queueRedactionIssue("unsafe redaction in $moduleName: $unsafePath")
                }
            }
        }
        val redacted = redactor?.redact(moduleName, current) ?: current
        return redacted as? JsonObject ?: buildJsonObject {}
    }

    private fun verifyDecodable(moduleName: String, full: JsonObject) {
        try {
            val restored = restoreRedactedModuleElement(stateJson, full)
            stateJson.decodeFromString(PolymorphicSerializer(ModuleState::class), restored.toString())
        } catch (e: Exception) {
            queueRedactionIssue("captured state for $moduleName does not decode: ${e.message}")
        }
    }

    private fun queueRedactionIssue(detail: String) {
        if (reportedRedactionIssues.add(detail)) {
            pendingRedactionIssues.add(detail)
        }
    }

    private suspend fun reportRedactionIssues() {
        if (pendingRedactionIssues.isEmpty()) return
        val issues = pendingRedactionIssues.toList()
        pendingRedactionIssues.clear()
        for (issue in issues) {
            ReaktivDebug.error("RedactionWatchdog: $issue")
            val callId = LogicTracer.notifyMethodStart(
                logicClass = REDACTION_TRACE_CLASS,
                methodName = "unsafeCapture",
                params = mapOf("detail" to issue)
            )
            if (callId.isNotEmpty()) {
                LogicTracer.notifyMethodCompleted(
                    callId = callId,
                    result = issue,
                    resultType = "RedactionIssue",
                    durationMs = 0L
                )
            }
        }
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
        val actionLines = ArrayList<String>()
        val startedLines = ArrayList<String>()
        val completedLines = ArrayList<String>()
        val failedLines = ArrayList<String>()
        val crashLines = ArrayList<String>()
        val stateReadLines = ArrayList<String>()
        val markerLines = ArrayList<String>()
        val networkLines = ArrayList<String>()

        for (record in batch) {
            try {
                when (record) {
                    is DispatchedAction -> {
                        val moduleName = record.state::class.qualifiedName
                            ?: record.state::class.simpleName ?: "Unknown"
                        val full = encodeModuleObject(moduleName, record.state)
                        val (deltaJson, deltaKind) = diffAgainstShadow(moduleName, full)
                        if (deltaKind == DeltaKind.FULL || actionCount % VERIFY_SAMPLE_INTERVAL == 0) {
                            verifyDecodable(moduleName, full)
                        }
                        val event = CapturedAction(
                            clientId = clientId,
                            timestamp = record.timestamp,
                            actionType = record.action::class.simpleName ?: "Unknown",
                            actionData = record.action.toString(),
                            stateDeltaJson = deltaJson,
                            moduleName = moduleName,
                            deltaKind = deltaKind
                        )
                        actionLines.add(json.encodeToString(event))
                        actionCount += 1
                        _actions.tryEmit(event)
                    }
                    is PrebuiltAction -> {
                        actionLines.add(json.encodeToString(record.event))
                        actionCount += 1
                        _actions.tryEmit(record.event)
                    }
                    is InitialState -> {
                        val objects = record.states.mapValues { (key, state) ->
                            encodeModuleObject(key, state)
                        }
                        objects.forEach { (key, value) ->
                            previousModuleJson[key] = value
                            verifyDecodable(key, value)
                        }
                        initialStateJson = JsonObject(objects).toString()
                    }
                    is NetworkRecord -> networkLines.add(json.encodeToString(record.capture))
                    is LogicStarted -> startedLines.add(json.encodeToString(record.event))
                    is LogicCompleted -> completedLines.add(json.encodeToString(record.event))
                    is LogicFailed -> failedLines.add(json.encodeToString(record.event))
                    is StateReadRecord -> {
                        stateReadLines.add(json.encodeToString(record.read))
                        _stateReads.tryEmit(record.read)
                    }
                    is ResetWorkerState -> {
                        previousModuleJson.clear()
                        actionCount = 0
                        reportedRedactionIssues.clear()
                        pendingRedactionIssues.clear()
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
                        markerLines.add(json.encodeToString(enriched))
                        _markers.tryEmit(enriched)
                    }
                    is CrashRecord -> {
                        val enriched = record.info.copy(
                            route = record.info.route ?: currentRouteFromShadow(),
                            afterActionIndex = if (record.info.afterActionIndex >= 0) {
                                record.info.afterActionIndex
                            } else {
                                actionCount - 1
                            }
                        )
                        crashLines.add(json.encodeToString(enriched))
                        capturedCrash = enriched
                        _crashes.tryEmit(enriched)
                    }
                }
            } catch (e: Exception) {
                ReaktivDebug.warn("SessionCapture failed to encode record: ${e.message}")
            }
        }

        if (actionLines.isNotEmpty()) {
            actionsStorage.appendLines(actionLines)
            val cap = maxActions
            if (cap != null && actionsStorage.lineCount() > cap + cap / 4) {
                actionsStorage.trimTo(cap)
            }
        }
        if (startedLines.isNotEmpty()) logicStartedStorage.appendLines(startedLines)
        if (completedLines.isNotEmpty()) logicCompletedStorage.appendLines(completedLines)
        if (failedLines.isNotEmpty()) logicFailedStorage.appendLines(failedLines)
        if (crashLines.isNotEmpty()) crashStorage.appendLines(crashLines)
        if (stateReadLines.isNotEmpty()) stateReadStorage.appendLines(stateReadLines)
        if (markerLines.isNotEmpty()) markerStorage.appendLines(markerLines)
        if (networkLines.isNotEmpty()) networkStorage.appendLines(networkLines)
        trimLogicEvents()
        reportRedactionIssues()
    }

    private fun trimLogicEvents() {
        val maxLogicEvents = this.maxLogicEvents ?: return
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

    private fun readCrashes(): List<CrashInfo> =
        crashStorage.readLines().map { json.decodeFromString(it) }

    private fun readNetwork(): List<NetworkRequestCapture> =
        networkStorage.readLines().map { json.decodeFromString(it) }

    private fun readStateReads(): List<StateRead> =
        stateReadStorage.readLines().map { json.decodeFromString(it) }

    private fun readMarkers(): List<SessionMarker> =
        markerStorage.readLines().map { json.decodeFromString(it) }

    private companion object {
        const val BODY_SLICE_BYTES: Int = 256 * 1024
        const val HIGH_WATER_MARK: Long = 50_000L
        const val VERIFY_SAMPLE_INTERVAL: Int = 100
        const val REDACTION_TRACE_CLASS: String = "RedactionWatchdog"
    }
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
    val logicFailed: List<LogicMethodFailed>,
    val stateReads: List<StateRead> = emptyList(),
    val markers: List<SessionMarker> = emptyList(),
    val network: List<NetworkRequestCapture> = emptyList()
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
            network = networkGroups.getOrElse(index) { emptyList() }
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
