package io.github.syrou.reaktiv.navigation.model

import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationTarget

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
public sealed class GuardResult {
    /**
     * Allow the navigation to proceed normally.
     */
    public object Allow : GuardResult()

    /**
     * Silently drop the navigation without any redirect or state change.
     */
    public object Reject : GuardResult()

    /**
     * Redirect to another route without storing the original destination.
     *
     * @param route The route to redirect to
     */
    public data class RedirectTo(val target: NavigationTarget) : GuardResult() {
        public constructor(route: String) : this(NavigationTarget.Path(route))

        /**
         * Redirect to a typed screen object.
         *
         * @param navigatable The screen or modal to redirect to
         */
        public constructor(navigatable: Navigatable) : this(NavigationTarget.NavigatableObject(navigatable))

        val route: String get() = target.shortRoute()
    }

    /**
     * Store the original navigation as a [PendingNavigation] in state, then redirect.
     *
     * The pending navigation can be resumed after authentication via
     * `navigation { clearBackStack(); resumePendingNavigation() }`.
     *
     * @param route The route to redirect to when guard denies access
     * @param metadata Arbitrary string key-value pairs stored with the pending navigation
     * @param displayHint Optional human-readable hint shown during the auth flow
     */
    public data class PendAndRedirectTo(
        val target: NavigationTarget,
        val metadata: Map<String, String> = emptyMap(),
        val displayHint: String? = null
    ) : GuardResult() {
        public constructor(
            route: String,
            metadata: Map<String, String> = emptyMap(),
            displayHint: String? = null
        ) : this(NavigationTarget.Path(route), metadata, displayHint)

        val route: String get() = target.shortRoute()

        /**
         * Store the original navigation and redirect to a typed screen.
         *
         * @param navigatable The screen or modal to redirect to
         * @param metadata Arbitrary string key-value pairs stored with the pending navigation
         * @param displayHint Optional human-readable hint shown during the auth flow
         */
        public constructor(
            navigatable: Navigatable,
            metadata: Map<String, String> = emptyMap(),
            displayHint: String? = null
        ) : this(NavigationTarget.NavigatableObject(navigatable), metadata, displayHint)
    }
}

private fun NavigationTarget.shortRoute(): String = when (this) {
    is NavigationTarget.Path -> path
    is NavigationTarget.NavigatableObject -> navigatable.route
    is NavigationTarget.NavigatableObjectWithGraph -> navigatable.route
}
