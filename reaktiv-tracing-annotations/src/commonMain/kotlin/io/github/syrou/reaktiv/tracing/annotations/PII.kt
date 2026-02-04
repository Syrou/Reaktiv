package io.github.syrou.reaktiv.tracing.annotations

/**
 * Marks a parameter as containing Personally Identifiable Information (PII).
 *
 * When the tracing compiler plugin encounters a parameter annotated with @PII,
 * the parameter value will be partially masked in the trace output, showing
 * only a portion of the value for debugging purposes while protecting privacy.
 *
 * Masking behavior by type:
 * - String: Shows first and last 2 characters with asterisks in between (e.g., "jo***oe" for "johndoe")
 * - Email: Shows first 2 chars of local part and domain (e.g., "jo***@example.com")
 * - Other types: Converted to string and masked similarly
 *
 * Usage:
 * ```kotlin
 * class UserLogic(storeAccessor: StoreAccessor) : ModuleLogic<UserAction>() {
 *     suspend fun updateProfile(
 *         @PII email: String,
 *         @PII phoneNumber: String
 *     ) {
 *         // email might appear as "jo***@example.com"
 *         // phoneNumber might appear as "+1***89"
 *     }
 * }
 * ```
 *
 * @see Sensitive for completely redacting sensitive data
 * @see NoTrace for excluding methods from tracing
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class PII
