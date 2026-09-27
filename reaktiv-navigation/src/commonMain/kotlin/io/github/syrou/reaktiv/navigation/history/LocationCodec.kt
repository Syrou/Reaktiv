package io.github.syrou.reaktiv.navigation.history

import io.github.syrou.reaktiv.core.util.isSensitiveKey
import io.github.syrou.reaktiv.navigation.PrecomputedNavigationData
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.model.PendingNavigation
import io.github.syrou.reaktiv.navigation.param.Params
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.github.syrou.reaktiv.navigation.util.RouteTemplate

internal data class SnapshotEntry(val path: String, val params: JsonObject)

internal data class LocationSnapshot(val entries: List<SnapshotEntry>) {
    val top: SnapshotEntry? get() = entries.lastOrNull()
}

internal class LocationCodec(
    private val precomputedData: PrecomputedNavigationData,
    private val json: Json
) {

    fun isAddressable(entry: NavigationEntry): Boolean = precomputedData.isAddressable(entry.navigatable)

    fun snapshotOf(backStack: List<NavigationEntry>): LocationSnapshot =
        LocationSnapshot(backStack.filter(::isAddressable).map(::snapshotEntryOf))

    fun encode(snapshot: LocationSnapshot): String = encodeElement(snapshot).toString()

    fun encodeElement(snapshot: LocationSnapshot): JsonObject {
        val entries = snapshot.entries.map { entry ->
            JsonObject(mapOf("p" to JsonPrimitive(entry.path), "q" to entry.params))
        }
        return JsonObject(mapOf("v" to JsonPrimitive(VERSION), "e" to JsonArray(entries)))
    }

    fun decode(text: String): LocationSnapshot? = try {
        decodeElement(json.parseToJsonElement(text))
    } catch (e: SerializationException) {
        null
    }

    fun decodeElement(element: JsonElement): LocationSnapshot? = try {
        val root = element.jsonObject
        if (root["v"]?.jsonPrimitive?.intOrNull != VERSION) {
            null
        } else {
            LocationSnapshot(
                root.getValue("e").jsonArray.map { item ->
                    val entry = item.jsonObject
                    SnapshotEntry(entry.getValue("p").jsonPrimitive.content, entry.getValue("q").jsonObject)
                }
            )
        }
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: NoSuchElementException) {
        null
    }

    fun encodePending(pending: PendingNavigation): String {
        val pathParams = RouteTemplate.parse(pending.route).paramNames
        val safeParams = pending.params.keys().filter { it !in pathParams && isSensitiveKey(it) }
            .fold(pending.params) { params, key -> params.without(key) }
        val safe = pending.copy(params = safeParams, metadata = pending.metadata.filterKeys { !isSensitiveKey(it) })
        return json.encodeToString(PendingNavigation.serializer(), safe)
    }

    fun decodePending(text: String): PendingNavigation? = try {
        json.decodeFromString(PendingNavigation.serializer(), text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    fun digest(text: String): String {
        var hash = FNV_OFFSET
        for (byte in text.encodeToByteArray()) {
            hash = (hash xor byte.toUByte().toULong()) * FNV_PRIME
        }
        return hash.toString(16).padStart(16, '0')
    }

    fun entriesOf(snapshot: LocationSnapshot, live: List<NavigationEntry>): List<NavigationEntry>? {
        val liveAddressable = live.filter(::isAddressable)
        return snapshot.entries.mapIndexed { index, entry ->
            val reusable = liveAddressable.getOrNull(index)?.takeIf { snapshotEntryOf(it) == entry }
            reusable ?: decodeEntry(entry, index) ?: return null
        }
    }

    private fun decodeEntry(entry: SnapshotEntry, index: Int): NavigationEntry? {
        val navigatable = precomputedData.routeToNavigatable[entry.path] ?: return null
        val params = try {
            json.decodeFromJsonElement(Params.serializer(), JsonObject(mapOf("values" to entry.params)))
        } catch (e: SerializationException) {
            return null
        }
        if (RouteTemplate.parse(entry.path).missing(params::getString).isNotEmpty()) return null
        return NavigationEntry(navigatable = navigatable, path = entry.path, params = params, stackPosition = index)
    }

    private fun snapshotEntryOf(entry: NavigationEntry): SnapshotEntry {
        val encoded = json.encodeToJsonElement(Params.serializer(), entry.params).jsonObject
        val values = encoded["values"]?.jsonObject ?: JsonObject(emptyMap())
        val pathParams = RouteTemplate.parse(entry.path).paramNames
        val kept = values.filterKeys { it in pathParams || !isSensitiveKey(it) }
        return SnapshotEntry(entry.path, sortKeys(JsonObject(kept)) as JsonObject)
    }

    private fun sortKeys(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to sortKeys(it.value) })
        is JsonArray -> JsonArray(element.map(::sortKeys))
        else -> element
    }

    private companion object {
        const val VERSION = 1
        val FNV_OFFSET: ULong = 0xcbf29ce484222325UL
        val FNV_PRIME: ULong = 0x100000001b3UL
    }
}
