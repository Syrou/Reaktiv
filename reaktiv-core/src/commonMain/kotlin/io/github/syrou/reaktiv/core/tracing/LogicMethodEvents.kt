package io.github.syrou.reaktiv.core.tracing

/**
 * Event fired when a traced logic method starts execution.
 *
 * @param logicClass Fully qualified name of the Logic class
 * @param methodName Name of the method being executed
 * @param params Map of parameter names to their string representations (may be obfuscated)
 * @param callId Unique identifier for this method invocation (for correlating start/complete/fail)
 * @param timestampMs Epoch milliseconds when the method started
 * @param sourceFile Source file path relative to project root (for IDE navigation)
 * @param lineNumber Line number where the method is defined (for IDE navigation)
 * @param githubSourceUrl Full GitHub URL to the source line (built at compile time by the tracing plugin)
 */
data class LogicMethodStart(
    val logicClass: String,
    val methodName: String,
    val params: Map<String, String>,
    val callId: String,
    val timestampMs: Long,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val githubSourceUrl: String? = null
)

/**
 * Event fired when a traced logic method completes successfully.
 *
 * @param callId Unique identifier matching the corresponding [LogicMethodStart]
 * @param result String representation of the return value (may be obfuscated), null for Unit returns
 * @param resultType Simple name of the result type
 * @param durationMs Time in milliseconds from method start to completion
 */
data class LogicMethodCompleted(
    val callId: String,
    val result: String?,
    val resultType: String,
    val durationMs: Long
)

/**
 * Event fired when a traced logic method fails with an exception.
 *
 * @param callId Unique identifier matching the corresponding [LogicMethodStart]
 * @param exceptionType Simple name of the exception class
 * @param exceptionMessage The exception message (may be null)
 * @param stackTrace Full stack trace string (may be null on some platforms)
 * @param durationMs Time in milliseconds from method start to failure
 */
data class LogicMethodFailed(
    val callId: String,
    val exceptionType: String,
    val exceptionMessage: String?,
    val stackTrace: String?,
    val durationMs: Long
)
