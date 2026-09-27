package io.github.syrou.reaktiv.navigation.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class NavigationLinkMap(
    val graphs: List<LinkGraph>,
    val routes: List<LinkRoute>,
    val aliases: List<LinkAlias> = emptyList(),
    val webPrefix: String? = null
)

@Serializable
public data class LinkGraph(
    val id: String,
    val path: String,
    val parent: String? = null,
    val start: LinkStart = LinkStart.None,
    val guards: List<Int> = emptyList(),
    val access: LinkAccess = LinkAccess.Linkable
)

@Serializable
public data class LinkRoute(
    val path: String,
    val route: String,
    val graph: String,
    val screen: String? = null,
    val kind: LinkKind = LinkKind.Screen,
    val params: List<String> = emptyList(),
    val guards: List<Int> = emptyList(),
    val access: LinkAccess = LinkAccess.Linkable
)

@Serializable
public data class LinkAlias(
    val pattern: String,
    val target: String,
    val params: List<String> = emptyList()
)

@Serializable
public sealed interface LinkStart {
    @Serializable
    @SerialName("route")
    public data class Route(val path: String) : LinkStart

    @Serializable
    @SerialName("graph")
    public data class Graph(val id: String) : LinkStart

    @Serializable
    @SerialName("dynamic")
    public data object Dynamic : LinkStart

    @Serializable
    @SerialName("none")
    public data object None : LinkStart
}

@Serializable
public enum class LinkKind {
    @SerialName("screen")
    Screen,

    @SerialName("modal")
    Modal
}

@Serializable
public enum class LinkAccess {
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
public sealed interface LinkOutcome {
    @Serializable
    @SerialName("landed")
    public data class Landed(val location: String) : LinkOutcome

    @Serializable
    @SerialName("redirected")
    public data class Redirected(val to: String) : LinkOutcome

    @Serializable
    @SerialName("rejected")
    public data object Rejected : LinkOutcome

    @Serializable
    @SerialName("not-found")
    public data class NotFound(val reason: String) : LinkOutcome

    @Serializable
    @SerialName("missing-params")
    public data class MissingParams(val names: List<String>) : LinkOutcome

    @Serializable
    @SerialName("ignored")
    public data class Ignored(val reason: String) : LinkOutcome
}
