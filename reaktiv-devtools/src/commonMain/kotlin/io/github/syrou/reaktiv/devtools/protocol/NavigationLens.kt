package io.github.syrou.reaktiv.devtools.protocol

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mirrors the value `reaktiv-navigation` emits for the span around a navigate call.
 *
 * Like [GUARD_TRACE_CLASS] this cannot reference its producer, because navigation deliberately does
 * not depend on the tracing runtime (BC-52). The two are a string contract.
 */
public const val NAVIGATION_TRACE_CLASS: String = "Navigation"

/**
 * One entry in a captured back stack.
 *
 * @property path The entry's full slash-separated path.
 * @property route The last path segment, which is the destination's own route.
 * @property params Parameters attached to the entry, rendered as text.
 */
public data class NavigationEntrySnapshot(
    val path: String,
    val params: Map<String, String>
) {
    public val route: String get() = path.substringAfterLast('/')
    public val graphChain: List<String> get() =
        path.removeSuffix("/$route").takeIf { it != path }
            ?.split('/')?.filter { it.isNotEmpty() }
            ?: emptyList()
}

/**
 * The navigation position at a point in a session, read out of a captured state tree.
 *
 * The DevTools inspects arbitrary stores and does not depend on `reaktiv-navigation`, so this is
 * parsed from JSON rather than deserialized into the real types. Every field is optional in
 * practice: a store with no navigation module produces no snapshot at all, and one captured by an
 * older release may be missing newer fields.
 *
 * @property moduleKey The state-tree key the navigation module was found under.
 * @property backStack Entries from the bottom of the stack to the top.
 * @property currentPath The path of the entry that is currently showing.
 * @property graphChain Graph IDs enclosing the current entry, outermost first.
 * @property isCurrentModal Whether the current entry is a modal.
 * @property isBootstrapping Whether navigation had not finished resolving its start destination.
 * @property isEvaluating Whether a guard or entry lambda was being evaluated.
 * @property modalContextPaths Paths that had an active modal context.
 */
public data class NavigationSnapshot(
    val moduleKey: String,
    val backStack: List<NavigationEntrySnapshot>,
    val currentPath: String?,
    val graphChain: List<String>,
    val isCurrentModal: Boolean,
    val isBootstrapping: Boolean,
    val isEvaluating: Boolean,
    val modalContextPaths: List<String>
)

private val lensJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject.entrySnapshot(): NavigationEntrySnapshot? {
    val path = this["path"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
    val params = (this["params"] as? JsonObject)
        ?.mapValues { (_, v) -> runCatching { v.jsonPrimitive.content }.getOrElse { v.toString() } }
        ?: emptyMap()
    return NavigationEntrySnapshot(path, params)
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()?.takeIf { it.isNotEmpty() && it != "null" }

/**
 * Reads the navigation position out of a full state tree, or returns null when the tree holds no
 * navigation module.
 *
 * @param stateJson A full state tree keyed by state class qualified name.
 */
public fun parseNavigationState(stateJson: String): NavigationSnapshot? {
    val root = runCatching { lensJson.parseToJsonElement(stateJson).jsonObject }.getOrNull() ?: return null
    val key = root.keys.firstOrNull { it.endsWith(".NavigationState") } ?: return null
    val nav = root[key] as? JsonObject ?: return null

    val backStack = (nav["backStack"] as? kotlinx.serialization.json.JsonArray ?: nav["backStack"]?.let {
        runCatching { it.jsonArray }.getOrNull()
    })
        ?.mapNotNull { (it as? JsonObject)?.entrySnapshot() }
        ?: emptyList()

    val current = (nav["currentEntry"] as? JsonObject)?.entrySnapshot()
    val derived = nav["derived"] as? JsonObject

    val graphChain = (derived?.get("currentGraphHierarchy"))
        ?.let { runCatching { it.jsonArray }.getOrNull() }
        ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
        ?: current?.graphChain
        ?: emptyList()

    fun flag(name: String, from: JsonObject?): Boolean =
        from?.get(name)?.let { runCatching { it.jsonPrimitive.content == "true" }.getOrNull() } ?: false

    return NavigationSnapshot(
        moduleKey = key,
        backStack = backStack,
        currentPath = current?.path ?: backStack.lastOrNull()?.path,
        graphChain = graphChain,
        isCurrentModal = flag("isCurrentModal", derived),
        isBootstrapping = flag("isBootstrapping", nav),
        isEvaluating = flag("isEvaluatingNavigation", nav),
        modalContextPaths = (nav["activeModalContexts"] as? JsonObject)?.keys?.toList() ?: emptyList()
    )
}

/**
 * What a navigation attempt or guard evaluation did.
 *
 * @property timestampMs When it started.
 * @property name The span name, `navigate` for an attempt or `guard(zone)` for an evaluation.
 * @property target The route that was asked for.
 * @property outcome The verdict, such as `Allow`, `Reject`, `Redirected(login)` or `Success`.
 *   Null while still running.
 * @property durationMs How long it took, or null while still running.
 * @property isGuard Whether this was a guard or entry evaluation rather than a navigate call.
 */
public data class NavigationAttempt(
    val timestampMs: Long,
    val name: String,
    val target: String,
    val outcome: String?,
    val durationMs: Long?,
    val isGuard: Boolean
) {
    /** True when the verdict stopped or diverted the navigation. */
    public val diverted: Boolean
        get() = outcome != null && (
            outcome.startsWith("Reject") ||
                outcome.startsWith("Redirect") ||
                outcome.startsWith("PendAndRedirect") ||
                outcome.startsWith("Dropped")
            )
}

/**
 * Pairs navigation and guard spans with their verdicts, newest last.
 *
 * A navigation that is still in flight appears with a null outcome rather than being omitted, so
 * a guard that never returns is visible rather than silently absent.
 */
public fun buildNavigationLog(
    starts: List<LogicMethodStart>,
    completions: List<LogicMethodCompleted>
): List<NavigationAttempt> {
    val byCallId = completions.associateBy { it.callId }
    return starts
        .filter { it.logicClass == NAVIGATION_TRACE_CLASS || it.logicClass == GUARD_TRACE_CLASS }
        .map { start ->
            val done = byCallId[start.callId]
            NavigationAttempt(
                timestampMs = start.timestampMs,
                name = start.methodName,
                target = start.params["target"] ?: "",
                outcome = done?.result,
                durationMs = done?.durationMs,
                isGuard = start.logicClass == GUARD_TRACE_CLASS
            )
        }
        .sortedBy { it.timestampMs }
}
