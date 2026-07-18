package io.github.syrou.reaktiv.introspection.protocol

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.introspection.ClientMetadata
import kotlinx.serialization.Serializable

/**
 * JSON export format version for captured sessions.
 */
public object SessionExportFormat {
    public const val VERSION: String = "3.2"
}

/**
 * Complete exported session data.
 * This is the JSON format used for import/export of recorded sessions.
 *
 * Example JSON structure:
 * ```json
 * {
 *   "version": "3.0",
 *   "sessionId": "uuid",
 *   "exportedAt": 1704067200000,
 *   "clientInfo": { "clientId": "...", "clientName": "...", "platform": "..." },
 *   "crash": { "timestamp": 1704067200000, "exception": { ... } },
 *   "session": { "startTime": ..., "endTime": ..., "actions": [...], "logicEvents": [...] }
 * }
 * ```
 *
 * This format is compatible with DevTools ghost device import.
 */
@Serializable
public data class SessionExport(
    val version: String = SessionExportFormat.VERSION,
    val sessionId: String,
    val exportedAt: Long,
    val clientInfo: ExportedClientInfo,
    val crash: CrashInfo? = null,
    val session: SessionData,
    val droppedRecords: Long = 0,
    val crashes: List<CrashInfo> = emptyList()
)

/**
 * Basic client information for export.
 */
@Serializable
public data class ExportedClientInfo(
    val clientId: String,
    val clientName: String,
    val platform: String,
    val metadata: ClientMetadata? = null
)

/**
 * Crash information with timestamp and exception details.
 */
@Serializable
public enum class CrashOrigin { LOGIC_METHOD, UNCAUGHT, MANUAL }

@Serializable
public data class CrashInfo(
    val timestamp: Long,
    val exception: CrashException,
    val origin: CrashOrigin = CrashOrigin.MANUAL,
    val logicClass: String? = null,
    val methodName: String? = null,
    val callId: String? = null,
    val route: String? = null,
    val afterActionIndex: Int = -1
)

/**
 * Represents exception information captured during a crash.
 */
@Serializable
public data class CrashException(
    val exceptionType: String,
    val message: String?,
    val stackTrace: String,
    val causedBy: CrashException? = null
)

/**
 * The captured session data including actions and logic events.
 */
@Serializable
public data class SessionData(
    val startTime: Long,
    val endTime: Long,
    val initialStateJson: String = "{}",
    val actions: List<CapturedAction>,
    val logicStartedEvents: List<LogicMethodStart>,
    val logicCompletedEvents: List<LogicMethodCompleted>,
    val logicFailedEvents: List<LogicMethodFailed>
)

/**
 * Converts a Throwable to a CrashException for serialization.
 */
public fun Throwable.toCrashException(): CrashException {
    return CrashException(
        exceptionType = this::class.simpleName ?: "Unknown",
        message = this.message,
        stackTrace = this.stackTraceToString(),
        causedBy = this.cause?.toCrashException()
    )
}

/**
 * Converts a traced logic failure into the canonical crash envelope.
 */
public fun LogicMethodFailed.toCrashInfo(): CrashInfo {
    return CrashInfo(
        timestamp = timestampMs,
        exception = CrashException(
            exceptionType = exceptionType,
            message = exceptionMessage,
            stackTrace = stackTrace ?: ""
        ),
        origin = CrashOrigin.LOGIC_METHOD,
        callId = callId
    )
}
