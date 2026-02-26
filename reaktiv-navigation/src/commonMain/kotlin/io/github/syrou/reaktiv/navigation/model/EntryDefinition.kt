package io.github.syrou.reaktiv.navigation.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Defines per-graph entry behaviour: a dynamic route selector that determines which
 * screen to navigate to when entering a graph directly.
 *
 * [route] is evaluated only when navigating directly to the graph route and determines
 * which screen inside the graph to show. Use [intercept] at the parent level to guard
 * all routes inside the graph (including deep links and direct screen navigation).
 *
 * Created by the dynamic [NavigationGraphBuilder.entry] DSL overload.
 *
 * Example:
 * ```kotlin
 * intercept(guard = requireContentReady) {
 *     graph("content") {
 *         entry(
 *             route = { store ->
 *                 val state = store.selectState<ContentState>().value
 *                 if (state.releases.isNotEmpty()) ReleasesScreen else ArtistScreen
 *             }
 *         )
 *         screens(ReleasesScreen, ArtistScreen)
 *     }
 * }
 * ```
 *
 * @param route Dynamic selector returning which [NavigationNode] inside the graph to navigate to
 * @param loadingThreshold How long to wait before showing the global loading modal (default 200ms)
 */
data class EntryDefinition(
    val route: RouteSelector? = null,
    val loadingThreshold: Duration = 200.milliseconds
)
