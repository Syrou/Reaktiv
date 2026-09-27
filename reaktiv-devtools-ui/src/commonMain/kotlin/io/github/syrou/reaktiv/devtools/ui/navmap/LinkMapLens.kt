package io.github.syrou.reaktiv.devtools.ui.navmap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val NAVIGATION_LINKS_EXTENSION: String = "navigation.links"
internal const val NAVIGATION_LINKS_SERVICE: String = "navigation-links"
internal const val OPEN_LINK_REQUEST: String = "open"

@Serializable
internal data class MapModel(
    val graphs: List<MapGraph> = emptyList(),
    val routes: List<MapRoute> = emptyList(),
    val aliases: List<MapAlias> = emptyList(),
    val webPrefix: String? = null
)

@Serializable
internal data class MapGraph(
    val id: String,
    val path: String = "",
    val parent: String? = null,
    val start: MapStart = MapStart.None,
    val guards: List<Int> = emptyList(),
    val access: MapAccess = MapAccess.Linkable
)

@Serializable
internal data class MapRoute(
    val path: String,
    val route: String = path,
    val graph: String = "root",
    val screen: String? = null,
    val kind: MapKind = MapKind.Screen,
    val params: List<String> = emptyList(),
    val guards: List<Int> = emptyList(),
    val access: MapAccess = MapAccess.Linkable
) {
    val canOpen: Boolean get() = access == MapAccess.Linkable || access == MapAccess.Fallback
}

@Serializable
internal data class MapAlias(
    val pattern: String,
    val target: String,
    val params: List<String> = emptyList()
)

@Serializable
internal sealed interface MapStart {
    @Serializable
    @SerialName("route")
    data class Route(val path: String) : MapStart

    @Serializable
    @SerialName("graph")
    data class Graph(val id: String) : MapStart

    @Serializable
    @SerialName("dynamic")
    data object Dynamic : MapStart

    @Serializable
    @SerialName("none")
    data object None : MapStart
}

@Serializable
internal enum class MapKind {
    @SerialName("screen")
    Screen,

    @SerialName("modal")
    Modal
}

@Serializable
internal enum class MapAccess {
    @SerialName("linkable")
    Linkable,

    @SerialName("fallback")
    Fallback,

    @SerialName("internal")
    Internal,

    @SerialName("shadowed")
    Shadowed
}

@Serializable
internal sealed interface MapLinkOutcome {
    @Serializable
    @SerialName("landed")
    data class Landed(val location: String) : MapLinkOutcome

    @Serializable
    @SerialName("redirected")
    data class Redirected(val to: String) : MapLinkOutcome

    @Serializable
    @SerialName("rejected")
    data object Rejected : MapLinkOutcome

    @Serializable
    @SerialName("not-found")
    data class NotFound(val reason: String) : MapLinkOutcome

    @Serializable
    @SerialName("missing-params")
    data class MissingParams(val names: List<String>) : MapLinkOutcome

    @Serializable
    @SerialName("ignored")
    data class Ignored(val reason: String) : MapLinkOutcome
}

private val lensJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

internal fun parseLinkMap(element: JsonElement?): MapModel? {
    if (element !is JsonObject) return null
    return runCatching { lensJson.decodeFromJsonElement(MapModel.serializer(), element) }.getOrNull()
}

internal fun parseLinkOutcome(element: JsonElement?): MapLinkOutcome? {
    if (element !is JsonObject) return null
    return runCatching { lensJson.decodeFromJsonElement(MapLinkOutcome.serializer(), element) }.getOrNull()
}

internal fun openLinkPayload(link: String, params: Map<String, String>): JsonElement = JsonObject(
    mapOf(
        "link" to JsonPrimitive(link),
        "params" to JsonObject(params.mapValues { JsonPrimitive(it.value) })
    )
)

internal fun MapModel.graph(id: String): MapGraph? = graphs.firstOrNull { it.id == id }

internal fun MapModel.route(path: String): MapRoute? = routes.firstOrNull { it.path == path }

internal fun MapModel.graphChain(id: String): List<MapGraph> {
    val chain = mutableListOf<MapGraph>()
    var current = graph(id)
    while (current != null && current.id !in chain.map { it.id }) {
        chain.add(0, current)
        current = current.parent?.let(::graph)
    }
    return chain
}

internal fun MapModel.aliasesTo(path: String): List<MapAlias> = aliases.filter { it.target == path }

internal fun MapModel.webPathOf(route: MapRoute): String? = webPrefix?.let { it + route.path }

internal fun MapModel.graphsStartingAt(path: String): List<MapGraph> =
    graphs.filter { (it.start as? MapStart.Route)?.path == path }

internal const val APP_LINKS_REQUEST: String = "app-links"

@Serializable
internal data class AppLinkPathModel(
    val path: String,
    val pattern: String,
    val target: String = "",
    val screen: String? = null,
    val params: List<String> = emptyList()
)

@Serializable
internal data class AppLinkFilesModel(
    val candidates: List<AppLinkPathModel> = emptyList(),
    val paths: List<AppLinkPathModel> = emptyList(),
    val appleAppSiteAssociation: String? = null,
    val assetLinks: String? = null,
    val androidManifestIntentFilter: String = ""
)

internal fun parseAppLinkFiles(element: JsonElement?): AppLinkFilesModel? {
    if (element !is JsonObject) return null
    return runCatching { lensJson.decodeFromJsonElement(AppLinkFilesModel.serializer(), element) }.getOrNull()
}

internal fun appLinksPayload(
    host: String,
    basePath: String?,
    appleAppIds: List<String>,
    androidPackage: String?,
    androidCertFingerprints: List<String>,
    androidDynamicPaths: Boolean,
    paths: Set<String>?
): JsonElement = JsonObject(
    buildMap {
        put("host", JsonPrimitive(host))
        basePath?.let { put("basePath", JsonPrimitive(it)) }
        put("appleAppIds", JsonArray(appleAppIds.map(::JsonPrimitive)))
        androidPackage?.let { put("androidPackage", JsonPrimitive(it)) }
        put("androidCertFingerprints", JsonArray(androidCertFingerprints.map(::JsonPrimitive)))
        put("androidDynamicPaths", JsonPrimitive(androidDynamicPaths))
        paths?.let { chosen -> put("paths", JsonArray(chosen.sorted().map(::JsonPrimitive))) }
    }
)
