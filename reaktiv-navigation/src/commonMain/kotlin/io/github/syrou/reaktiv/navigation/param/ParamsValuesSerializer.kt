package io.github.syrou.reaktiv.navigation.param

import io.github.syrou.reaktiv.core.serialization.AnySerializer
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive

internal object ParamsValuesSerializer : KSerializer<Map<String, Any>> {
    override val descriptor: SerialDescriptor = AnySerializer.descriptor

    override fun serialize(encoder: Encoder, value: Map<String, Any>) {
        val json = (encoder as? JsonEncoder)?.json
            ?: throw SerializationException("Params can be serialized only with JSON")
        val encoded = value.mapValues { (key, param) ->
            if (param is TypedParam<*>) param.toJsonElement(key, json) else param
        }
        encoder.encodeSerializableValue(AnySerializer, encoded)
    }

    @Suppress("UNCHECKED_CAST")
    override fun deserialize(decoder: Decoder): Map<String, Any> {
        val decoded = decoder.decodeSerializableValue(AnySerializer) as? Map<String, Any?>
            ?: throw SerializationException("Params must be encoded as a JSON object")
        return decoded.filterValues { it != null } as Map<String, Any>
    }

    @Suppress("UNCHECKED_CAST")
    private fun TypedParam<*>.toJsonElement(key: String, json: Json): JsonElement {
        val typed = this as TypedParam<Any?>
        return try {
            json.encodeToJsonElement(typed.serializer, typed.value)
        } catch (e: SerializationException) {
            ReaktivDebug.warn(
                "Params: '$key' could not be encoded with its serializer, so it is stored as text " +
                    "and will not restore as its type. ${e.message}"
            )
            JsonPrimitive(typed.value.toString())
        }
    }
}
