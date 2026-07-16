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

    /** Alias for [route]. */
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
     * The ID of the [NavigationGraph] that directly owns this entry, derived from [path].
     * Returns `"root"` for top-level navigatables that live outside a named graph.
     */
    val graphId: String get() {
        val prefix = path.removeSuffix("/${navigatable.route}")
        return if (prefix == path || prefix.isEmpty()) "root"
        else prefix.substringAfterLast("/")
    }
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
 * @property targetNavigatable The resolved destination.
 * @property targetGraphId The graph that directly owns [targetNavigatable].
 * @property extractedParams Path parameters extracted from the route pattern (e.g. `{id}`).
 * @property navigationGraphId The graph referenced when this was a graph-reference resolution.
 * @property isGraphReference `true` when this resolution was triggered by a graph route rather
 *   than a direct screen route.
 */
public data class RouteResolution(
    val targetNavigatable: Navigatable,
    val targetGraphId: String,
    val extractedParams: Params,
    val navigationGraphId: String? = null,
    val isGraphReference: Boolean = false
) {
    /**
     * Returns the graph ID that should be used for path building and hierarchy computation.
     * Prefers [navigationGraphId] when present, otherwise falls back to [targetGraphId].
     */
    public fun getEffectiveGraphId(): String {
        return when {
            isGraphReference -> targetGraphId
            navigationGraphId != null -> navigationGraphId
            else -> targetGraphId
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
