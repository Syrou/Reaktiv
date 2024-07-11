package io.github.syrou.reaktiv.core.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

typealias StringAnyMap = Map<String, @Serializable(with = AnySerializer::class) Any>

object AnySerializer : KSerializer<Any> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Any")

    override fun serialize(encoder: Encoder, value: Any) {
        val jsonEncoder =
            encoder as? JsonEncoder ?: throw SerializationException("This serializer can be used only with JSON")
        val jsonElement = when (value) {
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is List<*> -> JsonArray(value.map { serialize(it) })
            is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to serialize(it.value) })
            else -> JsonPrimitive(value.toString())
        }
        jsonEncoder.encodeJsonElement(jsonElement)
    }

    override fun deserialize(decoder: Decoder): Any {
        val jsonDecoder =
            decoder as? JsonDecoder ?: throw SerializationException("This serializer can be used only with JSON")
        return deserialize(jsonDecoder.decodeJsonElement())
    }

    private fun serialize(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is List<*> -> JsonArray(value.map { serialize(it) })
            is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to serialize(it.value) })
            else -> JsonPrimitive(value.toString())
        }
    }

    private fun deserialize(element: JsonElement): Any {
        return when (element) {
            is JsonPrimitive -> {
                when {
                    element.isString -> element.content
                    element.intOrNull != null -> element.int
                    element.longOrNull != null -> element.long
                    element.floatOrNull != null -> element.float
                    element.doubleOrNull != null -> element.double
                    element.booleanOrNull != null -> element.boolean
                    else -> element.content
                }
            }

            is JsonArray -> element.map { deserialize(it) }
            is JsonObject -> element.mapValues { deserialize(it.value) }
            else -> throw SerializationException("Unsupported JSON element: $element")
        }
    }
}