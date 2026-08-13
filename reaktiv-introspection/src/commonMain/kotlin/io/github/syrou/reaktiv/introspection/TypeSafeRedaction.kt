package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.serialization.Redacted
import io.github.syrou.reaktiv.core.serialization.RedactedAs
import kotlin.reflect.KClass
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.capturedKClass
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule

public object RedactionFallbacks {
    public val formatFallbacks: Map<String, String> = mapOf(
        "kotlinx.datetime.Instant" to "1970-01-01T00:00:00Z",
        "kotlin.time.Instant" to "1970-01-01T00:00:00Z",
        "kotlinx.datetime.LocalDate" to "1970-01-01",
        "kotlinx.datetime.LocalDateTime" to "1970-01-01T00:00",
        "kotlinx.datetime.LocalTime" to "00:00",
        "kotlin.time.Duration" to "PT0S",
        "kotlin.uuid.Uuid" to "00000000-0000-0000-0000-000000000000"
    )

    public val enumFallbackNames: Set<String> = setOf("REDACTED", "UNKNOWN", "UNSPECIFIED")
}

internal class TypeSafeRedactionOutcome(
    val element: JsonObject,
    val unrestorablePaths: List<String>
)

internal fun redactModuleElement(
    module: SerializersModule,
    descriptor: SerialDescriptor,
    element: JsonObject,
    normalizedKeys: List<String>
): TypeSafeRedactionOutcome {
    val unsafe = mutableListOf<String>()
    val walker = RedactionWalker(module, normalizedKeys, unsafe)
    val masked = walker.mask(
        descriptor = descriptor,
        element = element,
        masking = false,
        keyIsSensitive = false,
        annotations = emptyList(),
        path = "$"
    )
    return TypeSafeRedactionOutcome(masked as? JsonObject ?: element, unsafe)
}

public fun restoreRedactedModuleElement(json: Json, element: JsonObject): JsonObject {
    val typeName = (element[CLASS_DISCRIMINATOR_KEY] as? JsonPrimitive)?.contentOrNull ?: return element
    val strategy = runCatching {
        json.serializersModule.getPolymorphic(ModuleState::class, serializedClassName = typeName)
    }.getOrNull() ?: return element
    val walker = RedactionWalker(json.serializersModule, emptyList(), mutableListOf())
    val restored = runCatching {
        walker.restore(strategy.descriptor, element, emptyList())
    }.getOrNull()
    return restored as? JsonObject ?: element
}

private class RedactionWalker(
    private val module: SerializersModule,
    private val normalizedKeys: List<String>,
    private val unsafe: MutableList<String>
) {

    fun mask(
        descriptor: SerialDescriptor,
        element: JsonElement,
        masking: Boolean,
        keyIsSensitive: Boolean,
        annotations: List<Annotation>,
        path: String
    ): JsonElement {
        if (element is JsonNull) return element
        if (isLenientJson(descriptor)) return if (masking) maskLenient(element) else element
        val resolved = resolve(descriptor, element)
        if (resolved == null) {
            if (masking) unsafe.add("$path cannot be resolved as ${baseName(descriptor)}")
            return element
        }
        if (isLenientJson(resolved)) return if (masking) maskLenient(element) else element
        val nullable = descriptor.isNullable || resolved.isNullable
        return when (resolved.kind) {
            SerialKind.ENUM -> if (masking) JsonPrimitive(REDACTED_PLACEHOLDER) else element
            PrimitiveKind.STRING -> maskString(resolved, element, masking, annotations, nullable, path)
            PrimitiveKind.CHAR -> if (masking) JsonPrimitive("*") else element
            PrimitiveKind.BOOLEAN -> element
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG,
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE ->
                if (masking && keyIsSensitive) maskNumber(element) else element
            StructureKind.CLASS, StructureKind.OBJECT -> maskClass(resolved, element, masking, path)
            StructureKind.LIST -> maskList(resolved, element, masking, keyIsSensitive, annotations, path)
            StructureKind.MAP -> maskMap(resolved, element, masking, path)
            else -> element
        }
    }

    fun restore(
        descriptor: SerialDescriptor,
        element: JsonElement,
        annotations: List<Annotation>
    ): JsonElement {
        if (element is JsonNull) return element
        if (isLenientJson(descriptor)) return element
        val resolved = resolve(descriptor, element) ?: return element
        if (isLenientJson(resolved)) return element
        val nullable = descriptor.isNullable || resolved.isNullable
        return when (resolved.kind) {
            SerialKind.ENUM -> restoreEnum(resolved, element, annotations, nullable)
            PrimitiveKind.STRING -> restoreString(resolved, element, annotations, nullable)
            StructureKind.CLASS, StructureKind.OBJECT -> restoreClass(resolved, element)
            StructureKind.LIST -> restoreList(resolved, element, annotations)
            StructureKind.MAP -> restoreMap(resolved, element)
            else -> element
        }
    }

    private fun maskString(
        resolved: SerialDescriptor,
        element: JsonElement,
        masking: Boolean,
        annotations: List<Annotation>,
        nullable: Boolean,
        path: String
    ): JsonElement {
        if (!masking) return element
        if (baseName(resolved) != "kotlin.String") {
            val pinned = annotations.filterIsInstance<RedactedAs>().firstOrNull()
            val restorable = pinned != null || nullable ||
                RedactionFallbacks.formatFallbacks.containsKey(baseName(resolved))
            if (!restorable) unsafe.add("$path has no restore value for ${baseName(resolved)}")
        }
        return JsonPrimitive(REDACTED_PLACEHOLDER)
    }

    private fun maskNumber(element: JsonElement): JsonElement {
        val primitive = element as? JsonPrimitive ?: return element
        return when {
            primitive.longOrNull != null -> JsonPrimitive(0)
            primitive.doubleOrNull != null -> JsonPrimitive(0.0)
            else -> element
        }
    }

    private fun maskClass(
        resolved: SerialDescriptor,
        element: JsonElement,
        masking: Boolean,
        path: String
    ): JsonElement {
        val obj = element as? JsonObject ?: return element
        return buildJsonObject {
            obj.forEach { (key, value) ->
                if (key == CLASS_DISCRIMINATOR_KEY) {
                    put(key, value)
                    return@forEach
                }
                val index = resolved.getElementIndex(key)
                if (index == CompositeDecoder.UNKNOWN_NAME) {
                    if (masking || key.isSensitiveRedactionKey(normalizedKeys)) {
                        unsafe.add("$path.$key is not a declared field of ${baseName(resolved)}")
                    }
                    put(key, value)
                    return@forEach
                }
                val childAnnotations = resolved.getElementAnnotations(index)
                val annotated = childAnnotations.any { it is Redacted || it is RedactedAs }
                val childSensitive = key.isSensitiveRedactionKey(normalizedKeys) || annotated
                put(
                    key,
                    mask(
                        descriptor = resolved.getElementDescriptor(index),
                        element = value,
                        masking = masking || childSensitive,
                        keyIsSensitive = childSensitive,
                        annotations = childAnnotations,
                        path = "$path.$key"
                    )
                )
            }
        }
    }

    private fun maskList(
        resolved: SerialDescriptor,
        element: JsonElement,
        masking: Boolean,
        keyIsSensitive: Boolean,
        annotations: List<Annotation>,
        path: String
    ): JsonElement {
        val array = element as? JsonArray ?: return element
        val itemDescriptor = resolved.getElementDescriptor(0)
        return JsonArray(array.mapIndexed { index, item ->
            mask(itemDescriptor, item, masking, keyIsSensitive, annotations, "$path[$index]")
        })
    }

    private fun maskMap(
        resolved: SerialDescriptor,
        element: JsonElement,
        masking: Boolean,
        path: String
    ): JsonElement {
        val obj = element as? JsonObject ?: return element
        val valueDescriptor = resolved.getElementDescriptor(1)
        return buildJsonObject {
            obj.forEach { (key, value) ->
                val childSensitive = key.isSensitiveRedactionKey(normalizedKeys)
                put(
                    key,
                    mask(valueDescriptor, value, masking || childSensitive, childSensitive, emptyList(), "$path.$key")
                )
            }
        }
    }

    private fun restoreEnum(
        resolved: SerialDescriptor,
        element: JsonElement,
        annotations: List<Annotation>,
        nullable: Boolean
    ): JsonElement {
        if (!isSentinel(element)) return element
        val names = resolved.elementNames.toList()
        val pinned = annotations.filterIsInstance<RedactedAs>().firstOrNull()?.replacement
        if (pinned != null && pinned in names) return JsonPrimitive(pinned)
        val convention = names.firstOrNull { it.uppercase() in RedactionFallbacks.enumFallbackNames }
        if (convention != null) return JsonPrimitive(convention)
        if (nullable) return JsonNull
        return names.firstOrNull()?.let { JsonPrimitive(it) } ?: element
    }

    private fun restoreString(
        resolved: SerialDescriptor,
        element: JsonElement,
        annotations: List<Annotation>,
        nullable: Boolean
    ): JsonElement {
        if (!isSentinel(element)) return element
        if (baseName(resolved) == "kotlin.String") return element
        val pinned = annotations.filterIsInstance<RedactedAs>().firstOrNull()?.replacement
        if (pinned != null) return JsonPrimitive(pinned)
        if (nullable) return JsonNull
        val fallback = RedactionFallbacks.formatFallbacks[baseName(resolved)]
        return fallback?.let { JsonPrimitive(it) } ?: element
    }

    private fun restoreClass(resolved: SerialDescriptor, element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        return buildJsonObject {
            obj.forEach { (key, value) ->
                if (key == CLASS_DISCRIMINATOR_KEY) {
                    put(key, value)
                    return@forEach
                }
                val index = resolved.getElementIndex(key)
                if (index == CompositeDecoder.UNKNOWN_NAME) {
                    put(key, value)
                    return@forEach
                }
                put(
                    key,
                    restore(resolved.getElementDescriptor(index), value, resolved.getElementAnnotations(index))
                )
            }
        }
    }

    private fun restoreList(
        resolved: SerialDescriptor,
        element: JsonElement,
        annotations: List<Annotation>
    ): JsonElement {
        val array = element as? JsonArray ?: return element
        val itemDescriptor = resolved.getElementDescriptor(0)
        return JsonArray(array.map { restore(itemDescriptor, it, annotations) })
    }

    private fun restoreMap(resolved: SerialDescriptor, element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        val valueDescriptor = resolved.getElementDescriptor(1)
        return buildJsonObject {
            obj.forEach { (key, value) -> put(key, restore(valueDescriptor, value, emptyList())) }
        }
    }

    private fun resolve(descriptor: SerialDescriptor, element: JsonElement): SerialDescriptor? {
        var current = descriptor
        var guard = 0
        while (guard++ < 8) {
            current = when {
                current.isInline -> current.getElementDescriptor(0)
                current.kind == SerialKind.CONTEXTUAL -> {
                    val kClass = current.capturedKClass ?: return null
                    @Suppress("UNCHECKED_CAST")
                    val contextualBase = kClass as KClass<Any>
                    module.getContextual(contextualBase)?.descriptor ?: return null
                }
                current.kind is PolymorphicKind -> resolveConcrete(current, element) ?: return null
                else -> return current
            }
        }
        return null
    }

    private fun resolveConcrete(descriptor: SerialDescriptor, element: JsonElement): SerialDescriptor? {
        val obj = element as? JsonObject ?: return null
        val typeName = (obj[CLASS_DISCRIMINATOR_KEY] as? JsonPrimitive)?.contentOrNull ?: return null
        if (descriptor.kind == PolymorphicKind.SEALED) {
            return descriptor.getElementDescriptor(1).elementDescriptors.firstOrNull {
                it.serialName == typeName
            }
        }
        val kClass = descriptor.capturedKClass ?: return null
        @Suppress("UNCHECKED_CAST")
        val base = kClass as KClass<Any>
        return module.getPolymorphic(base, serializedClassName = typeName)?.descriptor
    }

    private fun isSentinel(element: JsonElement): Boolean =
        element is JsonPrimitive && element.isString && element.content == REDACTED_PLACEHOLDER

    private fun isLenientJson(descriptor: SerialDescriptor): Boolean =
        baseName(descriptor).startsWith("kotlinx.serialization.json.")

    private fun baseName(descriptor: SerialDescriptor): String =
        descriptor.serialName.removeSuffix("?")

    private fun maskLenient(element: JsonElement): JsonElement = when (element) {
        is JsonNull -> element
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) ->
                if (key == CLASS_DISCRIMINATOR_KEY) put(key, value) else put(key, maskLenient(value))
            }
        }
        is JsonArray -> JsonArray(element.map { maskLenient(it) })
        is JsonPrimitive -> if (element.isString) JsonPrimitive(REDACTED_PLACEHOLDER) else element
    }
}
