package io.github.syrou.reaktiv.navigation.model

import androidx.compose.runtime.Stable
import io.github.syrou.reaktiv.navigation.alias.ActionResource
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.definition.Navigatable
import io.github.syrou.reaktiv.navigation.param.Params
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A single entry in the navigation back stack representing one visited destination.
 *
 * Entries are immutable; the navigation system creates new instances when state changes.
 * The [path] is the full slash-separated route (e.g. `"auth/login"`), while [route] is
 * just the last segment (e.g. `"login"`).
 *
 * `NavigationEntry` is a runtime type holding a direct reference to its [navigatable];
 * screens and modals themselves are never serialized. Persistence is handled by
 * [NavigationEntrySerializer], which stores only the path, params, and stack position
 * and rehydrates the [navigatable] from the registered navigation graph on restore.
 *
 * @property navigatable The destination this entry targets. Read titles and actions
 *   directly: `entry.navigatable.titleResource`, `entry.navigatable.actionResource`.
 * @property path Full path from the root graph, e.g. `"profile/settings"`.
 * @property params Parameters passed to this destination at navigation time.
 * @property stackPosition Zero-based index of this entry in the back stack.
 */
@Stable
public data class NavigationEntry(
    val navigatable: Navigatable,
    val path: String,
    val params: Params,
    val stackPosition: Int = 0
) {
    /** The short route of the [navigatable] (the last segment of [path]). */
    val route: String get() = navigatable.route

    @Deprecated("Alias for route.", ReplaceWith("route"), DeprecationLevel.WARNING)
    val navigatableRoute: String get() = navigatable.route

    /** The [navigatable]'s title resource, directly invokable in composition. */
    val titleResource: TitleResource? get() = navigatable.titleResource

    /** The [navigatable]'s action resource, directly invokable in composition. */
    val actionResource: ActionResource? get() = navigatable.actionResource

    /**
     * A stable identity key combining [path] and [params], suitable for use as a Compose key.
     * Changes when the entry's destination or parameters change.
     */
    val stableKey: String get() = "${path}_${params.hashCode()}"

    /**
     * The graph IDs enclosing this entry, outermost first, derived from [path].
     *
     * Empty for a top-level navigatable that lives outside a named graph. Read from the path
     * rather than a lookup table, so it works for nested graphs without the caller knowing the
     * hierarchy.
     */
    val graphChain: List<String> get() {
        val prefix = path.removeSuffix("/${navigatable.route}")
        if (prefix == path || prefix.isEmpty()) return emptyList()
        return prefix.split('/').filter { it.isNotEmpty() }
    }

    /**
     * The ID of the [NavigationGraph] that directly owns this entry, derived from [path].
     * Returns `"root"` for top-level navigatables that live outside a named graph.
     */
    val graphId: String get() = graphChain.lastOrNull() ?: "root"
}

/**
 * Serializes [NavigationEntry] as `(path, params, stackPosition)` and rehydrates the
 * [Navigatable] reference from the registered navigation graph on deserialization.
 *
 * Registered contextually by `NavigationModule` via `CustomTypeRegistrar`, capturing the
 * module's route registry, so persisted state restores real navigatable references without
 * screens or modals ever being serialized.
 */
public class NavigationEntrySerializer(
    private val resolvePath: (String) -> Navigatable?
) : KSerializer<NavigationEntry> {

    @Serializable
    private data class Surrogate(
        val path: String,
        val params: Params,
        val stackPosition: Int = 0
    )

    override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: NavigationEntry) {
        encoder.encodeSerializableValue(
            Surrogate.serializer(),
            Surrogate(value.path, value.params, value.stackPosition)
        )
    }

    override fun deserialize(decoder: Decoder): NavigationEntry {
        val surrogate = decoder.decodeSerializableValue(Surrogate.serializer())
        val navigatable = resolvePath(surrogate.path)
            ?: throw SerializationException(
                "Cannot restore NavigationEntry: no navigatable is registered for path " +
                        "'${surrogate.path}'. Define a notFoundScreen to restore entries " +
                        "whose routes no longer exist."
            )
        return NavigationEntry(
            navigatable = navigatable,
            path = surrogate.path,
            params = surrogate.params,
            stackPosition = surrogate.stackPosition
        )
    }
}

/**
 * The result of resolving a route string to a concrete [Navigatable] within the graph hierarchy.
 *
 * Two graph IDs appear here and they answer different questions. [requestedGraphId] is the graph
 * the caller named, and [owningGraphId] is the graph that turned out to own the destination. They
 * differ whenever a graph's start destination is itself a graph reference: asking for `wizard`
 * whose start is `wizard/addons` whose start is `addons/review` resolves to `review`, owned by
 * `addons`, requested as `wizard`. A guard declared on `wizard` has to fire for that navigation,
 * which is why both are kept.
 *
 * @property targetNavigatable The resolved destination.
 * @property owningGraphId The graph that directly owns [targetNavigatable].
 * @property extractedParams Path parameters extracted from the route pattern (e.g. `{id}`).
 * @property requestedGraphId The graph the caller named, when resolution started from a graph
 *   route rather than a destination path. Null when a destination was named directly.
 * @property isGraphReference Unused. Removed in a later release.
 */
public data class RouteResolution(
    val targetNavigatable: Navigatable,
    val owningGraphId: String,
    val extractedParams: Params,
    val requestedGraphId: String? = null,
    val isGraphReference: Boolean = false
) {
    @Deprecated(
        "Renamed to owningGraphId, which says which of the two graph ids this is.",
        ReplaceWith("owningGraphId"),
        DeprecationLevel.WARNING
    )
    val targetGraphId: String get() = owningGraphId

    @Deprecated(
        "Renamed to requestedGraphId, which says which of the two graph ids this is.",
        ReplaceWith("requestedGraphId"),
        DeprecationLevel.WARNING
    )
    val navigationGraphId: String? get() = requestedGraphId

    /**
     * Returns the graph ID that should be used for path building and hierarchy computation.
     * Prefers [requestedGraphId] when present, otherwise falls back to [owningGraphId].
     */
    @Deprecated(
        "Unused, and it reconciles two graph ids that should be one. Read owningGraphId or requestedGraphId directly.",
        level = DeprecationLevel.WARNING
    )
    public fun getEffectiveGraphId(): String {
        return when {
            isGraphReference -> owningGraphId
            requestedGraphId != null -> requestedGraphId
            else -> owningGraphId
        }
    }
}

/**
 * A lightweight pairing of a resolved [Navigatable] and the graph ID it belongs to.
 *
 * @property navigatable The resolved destination.
 * @property actualGraphId The graph that owns [navigatable].
 */
public data class ScreenResolution(
    val navigatable: Navigatable,
    val actualGraphId: String
)

/**
 * Creates a [NavigationEntry] for this [Navigatable] at the given [path].
 *
 * @param path Full slash-separated path for the entry.
 * @param params Parameters to attach to the entry.
 * @param stackPosition The entry's zero-based position in the back stack.
 * @return A new [NavigationEntry] targeting this navigatable.
 */
public fun Navigatable.toNavigationEntry(
    path: String,
    params: Params = Params.empty(),
    stackPosition: Int = 0
): NavigationEntry = NavigationEntry(
    navigatable = this,
    path = path,
    params = params,
    stackPosition = stackPosition
)
