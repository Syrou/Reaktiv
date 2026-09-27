package io.github.syrou.reaktiv.navigation.history

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class HistoryEntryState(
    val idx: Int,
    val digest: String?,
    val trail: List<String>,
    val snapshot: LocationSnapshot?
) {

    fun encode(codec: LocationCodec): String = JsonObject(
        buildMap {
            put(MARKER, JsonPrimitive(VERSION))
            put("i", JsonPrimitive(idx))
            digest?.let { put("d", JsonPrimitive(it)) }
            put("t", JsonArray(trail.map(::JsonPrimitive)))
            snapshot?.let { put("s", codec.encodeElement(it)) }
        }
    ).toString()

    fun lossy(): HistoryEntryState = copy(snapshot = null)

    companion object {
        private const val MARKER = "reaktiv"
        private const val VERSION = 1
        const val TRAIL_LIMIT: Int = 32

        fun decode(text: String?, codec: LocationCodec): HistoryEntryState? {
            if (text == null) return null
            return try {
                val root = Json.parseToJsonElement(text).jsonObject
                if (root[MARKER]?.jsonPrimitive?.intOrNull != VERSION) return null
                HistoryEntryState(
                    idx = root.getValue("i").jsonPrimitive.intOrNull ?: return null,
                    digest = root["d"]?.jsonPrimitive?.contentOrNull,
                    trail = root["t"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                    snapshot = root["s"]?.let(codec::decodeElement)
                )
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            } catch (e: NoSuchElementException) {
                null
            }
        }
    }
}
