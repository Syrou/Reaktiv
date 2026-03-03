package io.github.syrou.reaktiv.navigation.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Defines an intercept guard that applies to all navigation into a group of graphs,
 * including deep links.
 *
 * When [NavigationGraphBuilder.intercept] blocks are nested, the guards are chained so that
 * the outermost guard always runs first. Navigation proceeds only when every guard in the chain
 * returns [GuardResult.Allow]. The first non-Allow result stops evaluation immediately — inner
 * guards are never called.
 *
 * The chain is accumulated during graph construction via [prependOuter] and stored in
 * [outerGuards] (outermost first). At runtime [NavigationLogic] iterates [outerGuards] before
 * evaluating [guard] (the innermost guard).
 *
 * Example — three-level chain (startup → auth → premium):
 * ```kotlin
 * intercept(guard = { store ->
 *     if (store.selectState<AppState>().value.startupReady) GuardResult.Allow
 *     else GuardResult.Reject
 * }) {
 *     intercept(guard = { store ->
 *         if (store.selectState<AuthState>().value.isAuthenticated) GuardResult.Allow
 *         else GuardResult.RedirectTo(loginScreen)
 *     }) {
 *         intercept(guard = { store ->
 *             if (store.selectState<AuthState>().value.hasPremium) GuardResult.Allow
 *             else GuardResult.RedirectTo(upgradeScreen)
 *         }) {
 *             graph("premium") {
 *                 entry(premiumHome)
 *                 screens(premiumHome)
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * Created by the [NavigationGraphBuilder.intercept] DSL method.
 *
 * @param guard The innermost guard evaluated last in the chain; returns a [GuardResult] decision
 * @param loadingThreshold How long to wait before showing the global loading modal (default 200ms)
 * @param outerGuards Outer guards accumulated during graph construction, evaluated outermost-first
 *
 * @see NavigationGraphBuilder.intercept
 */
data class InterceptDefinition(
    val guard: NavigationGuard,
    val loadingThreshold: Duration = 200.milliseconds,
    internal val outerGuards: List<Pair<NavigationGuard, Duration>> = emptyList()
) {
    /**
     * Returns a new [InterceptDefinition] that evaluates [outer]'s full guard chain before
     * this definition's own [outerGuards] and [guard].
     *
     * The resulting order is: outer's outerGuards → outer's guard → this.outerGuards → this.guard.
     * This preserves any guards already accumulated on both sides, making the combinator safe
     * to apply at arbitrary nesting depth.
     */
    internal fun prependOuter(outer: InterceptDefinition): InterceptDefinition {
        val outerFullChain = outer.outerGuards + listOf(outer.guard to outer.loadingThreshold)
        return copy(outerGuards = outerFullChain + outerGuards)
    }
}
