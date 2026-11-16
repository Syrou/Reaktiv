package io.github.syrou.reaktiv.navigation.model

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.definition.NavigationNode
import io.github.syrou.reaktiv.navigation.param.Params
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A suspend function evaluated before navigation is committed.
 *
 * Returns a [GuardResult] to allow, reject, or redirect the navigation. It runs with a [GuardContext] as its
 * receiver, so a guard can check the item the navigation names as well as the store, the same way for a link and
 * for a `navigateTo` inside the app.
 *
 * Example:
 * ```kotlin
 * val requireAuth: NavigationGuard = { store ->
 *     if (store.selectState<AuthState>().value.isAuthenticated) GuardResult.Allow
 *     else GuardResult.RedirectTo(LoginScreen)
 * }
 *
 * val requireOwnProject: NavigationGuard = { store ->
 *     val projectId = params.getString("projectId")
 *     if (projectId in store.selectState<ProjectState>().value.ownProjectIds) GuardResult.Allow
 *     else GuardResult.RedirectTo(ProjectListScreen)
 * }
 * ```
 */
public typealias NavigationGuard = suspend GuardContext.(StoreAccessor) -> GuardResult

/**
 * What a [NavigationGuard] knows about the navigation it guards.
 *
 * @property params the params of the screen being entered, as the navigation, a link or a resumed pending
 * navigation passes them. A guard with a cache key keeps its answer per params, since its answer can depend on them.
 */
public class GuardContext(
    public val params: Params,
)

public typealias CacheKeySelector = suspend (StoreAccessor) -> Any?

/**
 * How long a guard may take before the loading modal shows, unless its `intercept` sets its own threshold.
 */
internal val DEFAULT_GUARD_LOADING_THRESHOLD: Duration = 200.milliseconds

/**
 * A suspend function that selects which destination to navigate to when entering a graph.
 *
 * Returns a [NavigationNode] — either a typed [io.github.syrou.reaktiv.navigation.definition.Navigatable]
 * (screen or modal object) for full-path resolution, or a [io.github.syrou.reaktiv.navigation.definition.NavigationPath]
 * for a plain route string. Used internally by [EntryDefinition.route].
 */
public typealias RouteSelector = suspend (StoreAccessor) -> NavigationNode
