package io.github.syrou.reaktiv.core.tracing

/**
 * Utility object for obfuscating sensitive data in trace output.
 *
 * Used by the compiler plugin to process parameters marked with @Sensitive or @PII annotations.
 */
object Obfuscation {

    /**
     * Placeholder text for completely redacted values.
     */
    const val REDACTED = "[REDACTED]"

    /**
     * Completely redacts a value, returning "[REDACTED]".
     *
     * Used for parameters annotated with @Sensitive.
     *
     * @param value The value to redact (ignored)
     * @return The constant string "[REDACTED]"
     */
    fun redact(value: Any?): String = REDACTED

    /**
     * Partially masks a value while preserving some characters for debugging.
     *
     * Used for parameters annotated with @PII (Personally Identifiable Information).
     *
     * Masking behavior:
     * - null: Returns "null"
     * - Empty string: Returns ""
     * - String with 4 or fewer chars: Returns "****"
     * - Email (contains @): Masks local part, preserves domain (e.g., "jo***@example.com")
     * - Other strings: Shows first 2 and last 2 chars (e.g., "Jo***oe" for "JohnDoe")
     * - Other types: Converts to string and masks
     *
     * @param value The value to mask
     * @return Partially masked string representation
     */
    fun maskPII(value: Any?): String {
        if (value == null) return "null"

        val str = value.toString()
        if (str.isEmpty()) return ""
        if (str.length <= 4) return "****"

        return if (str.contains("@")) {
            maskEmail(str)
        } else {
            maskGeneric(str)
        }
    }

    private fun maskEmail(email: String): String {
        val atIndex = email.indexOf('@')
        if (atIndex <= 0) return maskGeneric(email)

        val localPart = email.substring(0, atIndex)
        val domain = email.substring(atIndex)

        val maskedLocal = when {
            localPart.length <= 2 -> "**"
            else -> localPart.take(2) + "***"
        }

        return maskedLocal + domain
    }

    private fun maskGeneric(str: String): String {
        return when {
            str.length <= 4 -> "****"
            str.length <= 6 -> str.take(1) + "***" + str.takeLast(1)
            else -> str.take(2) + "***" + str.takeLast(2)
        }
    }

    /**
     * Converts any value to a string representation suitable for tracing.
     *
     * Handles common types specially:
     * - null: Returns "null"
     * - String: Returns the string (with length limit)
     * - Collections: Returns truncated representation
     * - Other: Uses toString() with length limit
     *
     * @param value The value to convert
     * @param maxLength Maximum length of the output string (default 200)
     * @return String representation of the value
     */
    fun toTraceString(value: Any?, maxLength: Int = 200): String {
        if (value == null) return "null"

        val str = when (value) {
            is String -> "\"$value\""
            is Collection<*> -> {
                if (value.size > 5) {
                    "${value::class.simpleName}(size=${value.size}, first5=${value.take(5)})"
                } else {
                    value.toString()
                }
            }
            is Map<*, *> -> {
                if (value.size > 5) {
                    "${value::class.simpleName}(size=${value.size})"
                } else {
                    value.toString()
                }
            }
            is ByteArray -> "ByteArray(size=${value.size})"
            is CharArray -> "CharArray(size=${value.size})"
            is IntArray -> "IntArray(size=${value.size})"
            is LongArray -> "LongArray(size=${value.size})"
            is FloatArray -> "FloatArray(size=${value.size})"
            is DoubleArray -> "DoubleArray(size=${value.size})"
            is BooleanArray -> "BooleanArray(size=${value.size})"
            is ShortArray -> "ShortArray(size=${value.size})"
            is Array<*> -> {
                if (value.size > 5) {
                    "Array(size=${value.size}, first5=${value.take(5).toList()})"
                } else {
                    value.contentToString()
                }
            }
            else -> value.toString()
        }

        return if (str.length > maxLength) {
            str.take(maxLength - 3) + "..."
        } else {
            str
        }
    }
}
