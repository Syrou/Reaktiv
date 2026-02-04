package io.github.syrou.reaktiv.tracing.annotations

/**
 * Excludes a method from automatic tracing by the compiler plugin.
 *
 * Methods annotated with @NoTrace will not have tracing code injected,
 * even if they are public suspend methods in a ModuleLogic subclass.
 *
 * Use this for:
 * - High-frequency methods where tracing overhead is unacceptable
 * - Internal helper methods that don't need visibility
 * - Methods containing extremely sensitive operations
 *
 * Usage:
 * ```kotlin
 * class PaymentLogic(storeAccessor: StoreAccessor) : ModuleLogic<PaymentAction>() {
 *
 *     suspend fun processPayment(amount: Double) {
 *         // This method WILL be traced
 *     }
 *
 *     @NoTrace
 *     suspend fun internalCryptoOperation(data: ByteArray) {
 *         // This method will NOT be traced
 *     }
 * }
 * ```
 *
 * @see Sensitive for redacting specific parameter values
 * @see PII for masking personally identifiable information
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class NoTrace
