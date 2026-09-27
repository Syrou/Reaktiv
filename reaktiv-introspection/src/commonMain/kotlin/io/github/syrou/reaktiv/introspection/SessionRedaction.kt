package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.tracing.Obfuscation
import io.github.syrou.reaktiv.core.tracing.ParamRedaction
import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.CapturedLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal class SessionRedaction(
    private val stateJson: Json,
    sensitiveKeys: Set<String>,
    private val redactor: StateRedactor?
) {
    private val keys = sensitiveKeys.map { it.normalizeRedactionKey() }
    private val unrestorable = LinkedHashSet<String>()

    val issues: Set<String> get() = unrestorable

    fun initialState(json: String): String {
        val root = runCatching { stateJson.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return json
        return JsonObject(
            root.mapValues { (moduleName, value) -> (value as? JsonObject)?.let { module(moduleName, it) } ?: value }
        ).toString()
    }

    fun action(action: CapturedAction): CapturedAction {
        val delta = runCatching { stateJson.parseToJsonElement(action.stateDeltaJson) }.getOrNull() as? JsonObject
        return action.copy(
            actionData = text(action.actionData),
            stateDeltaJson = delta?.let { module(action.moduleName, it).toString() } ?: action.stateDeltaJson
        )
    }

    fun logicStart(event: LogicMethodStart): LogicMethodStart =
        event.copy(params = event.params.mapValues { (name, value) -> param(name, value, event.redactions[name]) })

    fun logicCompleted(event: LogicMethodCompleted): LogicMethodCompleted =
        event.copy(result = event.result?.let(::text))

    fun exchange(exchange: NetworkRequestCapture): NetworkRequestCapture {
        val named = exchange.sensitiveHeaders.map { it.lowercase() }.toSet()
        return exchange.copy(
            url = url(exchange.url),
            requestHeaders = headers(exchange.requestHeaders, named),
            responseHeaders = headers(exchange.responseHeaders, named),
            requestBody = exchange.requestBody?.let { body(it, exchange.requestContentType) },
            responseBody = exchange.responseBody?.let { body(it, exchange.responseContentType) }
        )
    }

    fun log(log: CapturedLog): CapturedLog = log.copy(message = text(log.message))

    fun text(value: String): String {
        if (keys.isEmpty()) return value
        return KEY_VALUE.replace(value) { match ->
            val key = match.groupValues[1]
            if (isSensitive(key)) "$key${match.groupValues[2]}$REDACTED_PLACEHOLDER" else match.value
        }
    }

    private fun isSensitive(name: String): Boolean = keys.isNotEmpty() && name.isSensitiveRedactionKey(keys)

    private fun module(moduleName: String, element: JsonObject): JsonObject {
        val typeName = (element[CLASS_DISCRIMINATOR_KEY] as? JsonPrimitive)?.contentOrNull
        val descriptor = typeName?.let { name ->
            runCatching {
                stateJson.serializersModule.getPolymorphic(ModuleState::class, serializedClassName = name)
            }.getOrNull()?.descriptor
        }
        val walked = if (descriptor != null) {
            val outcome = redactModuleElement(stateJson.serializersModule, descriptor, element, keys)
            outcome.unrestorablePaths.forEach { unrestorable += "$moduleName: $it" }
            outcome.element
        } else {
            json(element) as? JsonObject ?: element
        }
        return (redactor?.redact(moduleName, walked) ?: walked) as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun json(element: JsonElement): JsonElement =
        redactSensitive(element, keys, REDACTED_PLACEHOLDER, CLASS_DISCRIMINATOR_KEY)

    private fun param(name: String, value: String, strategy: ParamRedaction?): String = when (strategy) {
        ParamRedaction.Sensitive -> REDACTED_PLACEHOLDER
        ParamRedaction.Pii -> Obfuscation.maskPII(value)
        null -> if (isSensitive(name)) REDACTED_PLACEHOLDER else text(value)
    }

    private fun headers(headers: Map<String, List<String>>, named: Set<String>): Map<String, List<String>> =
        headers.mapValues { (name, values) ->
            val lower = name.lowercase()
            if (lower in named || lower in SECRET_HEADERS || isSensitive(name)) listOf(REDACTED_PLACEHOLDER) else values
        }

    private fun body(body: String, contentType: String?): String {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            runCatching { Json.parseToJsonElement(body) }.getOrNull()?.let { return json(it).toString() }
        }
        if (contentType?.contains("x-www-form-urlencoded", ignoreCase = true) == true) return pairs(body)
        return text(body)
    }

    private fun url(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url
        val fragmentStart = url.indexOf('#', queryStart).takeIf { it >= 0 } ?: url.length
        return url.substring(0, queryStart + 1) + pairs(url.substring(queryStart + 1, fragmentStart)) +
            url.substring(fragmentStart)
    }

    private fun pairs(encoded: String): String = encoded.split('&').joinToString("&") { pair ->
        val separator = pair.indexOf('=')
        if (separator > 0 && isSensitive(pair.substring(0, separator))) {
            pair.substring(0, separator + 1) + REDACTED_PLACEHOLDER
        } else {
            pair
        }
    }

    private companion object {
        val KEY_VALUE = Regex("""([A-Za-z_][A-Za-z0-9_\-]*)(\s*[=:]\s*)("[^"]*"|[^,;&)\]}\s]+)""")
        val SECRET_HEADERS = setOf("authorization", "proxy-authorization", "cookie", "set-cookie")
    }
}
