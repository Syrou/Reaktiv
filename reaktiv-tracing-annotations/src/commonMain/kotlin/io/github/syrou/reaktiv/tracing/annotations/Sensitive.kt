package io.github.syrou.reaktiv.tracing.annotations

/**
 * Marks a parameter as containing sensitive data that should be obfuscated in traces.
 *
 * When the tracing compiler plugin encounters a parameter annotated with @Sensitive,
 * the parameter value will be replaced with "[REDACTED]" in the trace output.
 *
 * Usage:
 * ```kotlin
 * class UserLogic(storeAccessor: StoreAccessor) : ModuleLogic<UserAction>() {
 *     suspend fun login(
 *         username: String,
 *         @Sensitive password: String
 *     ) {
 *         // password will appear as "[REDACTED]" in traces
 *     }
 * }
 * ```
 *
 * @see PII for personally identifiable information
 * @see NoTrace for completely excluding methods from tracing
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class Sensitive
