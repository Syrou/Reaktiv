package io.github.syrou.reaktiv.navigation.model

import io.github.syrou.reaktiv.navigation.definition.Navigatable

/**
 * The decision returned by a navigation guard or access check.
 *
 * Guards return a [GuardResult] to indicate whether navigation should proceed, be rejected,
 * or be redirected to another route.
 *
 * Example:
 * ```kotlin
 * val requireAuth: NavigationGuard = { store ->
 *     if (store.selectState<AuthState>().value.isAuthenticated) GuardResult.Allow
 *     else GuardResult.RedirectTo(LoginScreen)
 * }
 * ```
 */
sealed class GuardResult {
    /**
     * Allow the navigation to proceed normally.
     */
    object Allow : GuardResult()

    /**
     * Silently drop the navigation without any redirect or state change.
     */
    object Reject : GuardResult()

    /**
     * Redirect to another route without storing the original destination.
     *
     * @param route The route to redirect to
     */
    data class RedirectTo(val route: String) : GuardResult() {
        /**
         * Redirect to a typed screen object.
         *
         * @param navigatable The screen or modal to redirect to
         */
        constructor(navigatable: Navigatable) : this(navigatable.route)
    }

    /**
     * Store the original navigation as a [PendingNavigation] in state, then redirect.
     *
     * The pending navigation can be resumed after authentication via
     * [NavigationLogic.resumePendingNavigation].
     *
     * @param route The route to redirect to when guard denies access
     * @param metadata Arbitrary string key-value pairs stored with the pending navigation
     * @param displayHint Optional human-readable hint shown during the auth flow
     */
    data class PendAndRedirectTo(
        val route: String,
        val metadata: Map<String, String> = emptyMap(),
        val displayHint: String? = null
    ) : GuardResult() {
        /**
         * Store the original navigation and redirect to a typed screen.
         *
         * @param navigatable The screen or modal to redirect to
         * @param metadata Arbitrary string key-value pairs stored with the pending navigation
         * @param displayHint Optional human-readable hint shown during the auth flow
         */
        constructor(
            navigatable: Navigatable,
            metadata: Map<String, String> = emptyMap(),
            displayHint: String? = null
        ) : this(navigatable.route, metadata, displayHint)
    }
}
