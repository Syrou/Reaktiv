package io.github.syrou.reaktiv.navigation.model

import io.github.syrou.reaktiv.navigation.param.Params
import kotlinx.serialization.Serializable

/**
 * Stores intent to navigate to a protected route while the user is being redirected for authentication.
 *
 * Placed in [NavigationState.pendingNavigation] by [GuardResult.PendAndRedirectTo].
 * Consumed by [NavigationLogic.resumePendingNavigation] after authentication succeeds.
 *
 * Example:
 * ```kotlin
 * val pending = navState.pendingNavigation
 * if (pending != null) {
 *     val hint = pending.displayHint
 *     if (hint != null) {
 *         PendingActionBanner(message = hint)
 *     }
 * }
 * ```
 *
 * @param route The canonical route the user originally tried to navigate to
 * @param params The parameters that were passed with the original navigation
 * @param metadata Arbitrary string key-value pairs for application use (e.g. invite tokens)
 * @param displayHint Optional human-readable hint that can be shown during the auth flow
 */
@Serializable
data class PendingNavigation(
    val route: String,
    val params: Params,
    val metadata: Map<String, String> = emptyMap(),
    val displayHint: String? = null
)
