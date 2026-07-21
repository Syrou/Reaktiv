package io.github.syrou.reaktiv.introspection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SensitiveDataRedactionTest {

    private fun redact(input: String, redactor: StateRedactor): JsonObject =
        redactor.redact("Test", Json.parseToJsonElement(input)).jsonObject

    @Test
    fun masksCommonSensitiveKeysAcrossNamingStyles() {
        val result = redact(
            """{"password":"hunter2","apiKey":"abc","access_token":"t","username":"joe","count":3}""",
            sensitiveKeyRedactor()
        )
        assertEquals(REDACTED_PLACEHOLDER, result["password"]!!.jsonPrimitive.content)
        assertEquals(REDACTED_PLACEHOLDER, result["apiKey"]!!.jsonPrimitive.content)
        assertEquals(REDACTED_PLACEHOLDER, result["access_token"]!!.jsonPrimitive.content)
        assertEquals("joe", result["username"]!!.jsonPrimitive.content)
        assertEquals("3", result["count"]!!.jsonPrimitive.content)
    }

    @Test
    fun masksNestedObjectsAndArraysUnderSensitiveKeys() {
        val result = redact(
            """{"user":{"name":"joe","credentials":{"secret":"s"}},"tokens":["a","b"]}""",
            sensitiveKeyRedactor()
        )
        val user = result["user"]!!.jsonObject
        assertEquals("joe", user["name"]!!.jsonPrimitive.content)

        // Masking keeps the JSON shape: an object stays an object and an array stays an array,
        // with every leaf beneath masked. Collapsing them to the mask string changed their type
        // and made the captured state undecodable, breaking replication and reconstruction.
        val credentials = user["credentials"]!!.jsonObject
        assertEquals(REDACTED_PLACEHOLDER, credentials["secret"]!!.jsonPrimitive.content)

        val tokens = result["tokens"]!!.jsonArray
        assertEquals(2, tokens.size)
        tokens.forEach { assertEquals(REDACTED_PLACEHOLDER, it.jsonPrimitive.content) }
    }

    @Test
    fun preservesTypeDiscriminatorAndNonSensitiveFields() {
        val result = redact("""{"type":"LoginState","email":"a@b.c"}""", sensitiveKeyRedactor())
        assertEquals("LoginState", result["type"]!!.jsonPrimitive.content)
        assertEquals("a@b.c", result["email"]!!.jsonPrimitive.content)
    }

    @Test
    fun defaultConfigRedactsSensitiveKeys() {
        val redactor = IntrospectionConfig(platform = "Test").resolveRedactor()
        assertNotNull(redactor)
        assertEquals(REDACTED_PLACEHOLDER, redact("""{"password":"x"}""", redactor)["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun disablingRedactionWithoutCustomYieldsNoRedactor() {
        assertNull(IntrospectionConfig(platform = "Test", redactSensitiveKeys = false).resolveRedactor())
    }

    @Test
    fun customRedactorComposesOnTopOfBuiltIn() {
        val custom = StateRedactor { _, state ->
            buildJsonObject {
                state.jsonObject.forEach { (key, value) ->
                    put(key, if (key == "email") JsonPrimitive("[HIDDEN]") else value)
                }
            }
        }
        val redactor = IntrospectionConfig(platform = "Test", redactor = custom).resolveRedactor()
        assertNotNull(redactor)
        val result = redact("""{"password":"x","email":"a@b.c"}""", redactor)
        assertEquals(REDACTED_PLACEHOLDER, result["password"]!!.jsonPrimitive.content)
        assertEquals("[HIDDEN]", result["email"]!!.jsonPrimitive.content)
    }
}
