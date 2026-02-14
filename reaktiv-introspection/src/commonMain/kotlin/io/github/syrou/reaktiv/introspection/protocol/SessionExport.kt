package io.github.syrou.reaktiv.introspection.protocol

import kotlinx.serialization.Serializable

/**
 * JSON export format version for captured sessions.
 */
object SessionExportFormat {
    const val VERSION = "2.0"
}

/**
 * Complete exported session data.
 * This is the JSON format used for import/export of recorded sessions.
 *
 * Example JSON structure:
 * ```json
 * {
 *   "version": "1.0",
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
data class SessionExport(
    val version: String = SessionExportFormat.VERSION,
    val sessionId: String,
    val exportedAt: Long,
    val clientInfo: ExportedClientInfo,
    val crash: CrashInfo? = null,
    val session: SessionData
)

/**
 * Basic client information for export.
 */
@Serializable
data class ExportedClientInfo(
    val clientId: String,
    val clientName: String,
    val platform: String
)

/**
 * Crash information with timestamp and exception details.
 */
@Serializable
data class CrashInfo(
    val timestamp: Long,
    val exception: CrashException
)

/**
 * Represents exception information captured during a crash.
 */
@Serializable
data class CrashException(
    val exceptionType: String,
    val message: String?,
    val stackTrace: String,
    val causedBy: CrashException? = null
)

/**
 * The captured session data including actions and logic events.
 */
@Serializable
data class SessionData(
    val startTime: Long,
    val endTime: Long,
    val initialStateJson: String = "{}",
    val actions: List<CapturedAction>,
    val logicStartedEvents: List<CapturedLogicStart>,
    val logicCompletedEvents: List<CapturedLogicComplete>,
    val logicFailedEvents: List<CapturedLogicFailed>
)

/**
 * Converts a Throwable to a CrashException for serialization.
 */
fun Throwable.toCrashException(): CrashException {
    return CrashException(
        exceptionType = this::class.simpleName ?: "Unknown",
        message = this.message,
        stackTrace = this.stackTraceToString(),
        causedBy = this.cause?.toCrashException()
    )
}
