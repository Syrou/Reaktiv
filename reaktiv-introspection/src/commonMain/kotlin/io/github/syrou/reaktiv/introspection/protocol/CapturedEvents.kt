package io.github.syrou.reaktiv.introspection.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
public enum class DeltaKind { FULL, FIELDS }

/**
 * Represents a captured action dispatch event.
 */
@Serializable
public data class CapturedAction(
    val clientId: String,
    val timestamp: Long,
    val actionType: String,
    val actionData: String,
    val stateDeltaJson: String,
    val moduleName: String = "",
    val deltaKind: DeltaKind = DeltaKind.FULL
)

public fun mergeCapturedDeltas(pending: CapturedAction, incoming: CapturedAction): CapturedAction {
    if (incoming.deltaKind == DeltaKind.FULL) {
        return incoming
    }
    if (pending.deltaKind == DeltaKind.FULL) {
        return incoming.copy(
            deltaKind = DeltaKind.FULL,
            stateDeltaJson = mergeFieldJson(pending.stateDeltaJson, incoming.stateDeltaJson)
        )
    }
    return incoming.copy(stateDeltaJson = mergeFieldJson(pending.stateDeltaJson, incoming.stateDeltaJson))
}

public fun mergeFields(base: JsonObject, overlay: JsonObject): JsonObject =
    JsonObject(base + overlay)

public fun mergeFieldJson(baseJson: String, overlayJson: String): String {
    return try {
        mergeFields(
            Json.parseToJsonElement(baseJson).jsonObject,
            Json.parseToJsonElement(overlayJson).jsonObject
        ).toString()
    } catch (e: Exception) {
        overlayJson
    }
}
