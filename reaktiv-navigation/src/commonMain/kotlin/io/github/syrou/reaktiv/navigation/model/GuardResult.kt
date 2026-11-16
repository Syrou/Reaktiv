package io.github.syrou.reaktiv.navigation.model

import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.definition.NavigationTarget
import io.github.syrou.reaktiv.navigation.param.Params

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
     * @param params The params the redirect target opens with, such as why an upgrade screen was shown
     */
    public data class RedirectTo(
        val target: NavigationTarget,
        val params: Params = Params.empty()
    ) : GuardResult() {
        public constructor(route: String, params: Params = Params.empty()) : this(NavigationTarget.Path(route), params)

        /**
         * Redirect to a typed screen object.
         *
         * @param navigatable The screen or modal to redirect to
         * @param params The params the redirect target opens with
         */
        public constructor(
            navigatable: Navigatable,
            params: Params = Params.empty()
        ) : this(NavigationTarget.NavigatableObject(navigatable), params)

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
     * @param params The params the redirect target opens with
     */
    public data class PendAndRedirectTo(
        val target: NavigationTarget,
        val metadata: Map<String, String> = emptyMap(),
        val displayHint: String? = null,
        val params: Params = Params.empty()
    ) : GuardResult() {
        public constructor(
            route: String,
            metadata: Map<String, String> = emptyMap(),
            displayHint: String? = null,
            params: Params = Params.empty()
        ) : this(NavigationTarget.Path(route), metadata, displayHint, params)

        val route: String get() = target.shortRoute()

        /**
         * Store the original navigation and redirect to a typed screen.
         *
         * @param navigatable The screen or modal to redirect to
         * @param metadata Arbitrary string key-value pairs stored with the pending navigation
         * @param displayHint Optional human-readable hint shown during the auth flow
         * @param params The params the redirect target opens with
         */
        public constructor(
            navigatable: Navigatable,
            metadata: Map<String, String> = emptyMap(),
            displayHint: String? = null,
            params: Params = Params.empty()
        ) : this(NavigationTarget.NavigatableObject(navigatable), metadata, displayHint, params)
    }
}

private fun NavigationTarget.shortRoute(): String = when (this) {
    is NavigationTarget.Path -> path
    is NavigationTarget.NavigatableObject -> navigatable.route
    is NavigationTarget.NavigatableObjectWithGraph -> navigatable.route
}
