package io.github.syrou.reaktiv.introspection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Redaction must not change the JSON type of what it masks.
 *
 * Replacing a value outright made captured state undecodable, which broke replication and
 * reconstruction for any module holding a sensitive key. Both shapes here are taken from a
 * real failure: a Boolean and an object that each became the mask string.
 */
class RedactionShapeTest {

    private val redactor = sensitiveKeyRedactor()

    private fun redact(json: String): JsonObject =
        redactor.redact("module", Json.parseToJsonElement(json)).jsonObject

    @Test
    fun `a boolean under a sensitive key is left alone`() {
        val result = redact("""{"user":{"hasHyperwalletToken":true}}""")
        val value = result["user"]!!.jsonObject["hasHyperwalletToken"]!!.jsonPrimitive
        assertFalse(value.isString, "Masking a Boolean with a string makes the state undecodable")
        assertTrue(value.boolean, "A Boolean is a flag about a secret, never the secret itself")
    }

    @Test
    fun `a redacted object stays an object`() {
        val result = redact("""{"confirmPassword":{"value":"hunter2","valid":true,"attempts":3}}""")
        val confirm = result["confirmPassword"]
        assertTrue(confirm is JsonObject, "Masking an object with a string makes the state undecodable")
        assertEquals(REDACTED_PLACEHOLDER, confirm.jsonObject["value"]!!.jsonPrimitive.content)
        assertTrue(confirm.jsonObject["valid"]!!.jsonPrimitive.boolean, "Non-string values are not corrupted")
        assertEquals("3", confirm.jsonObject["attempts"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a redacted string still carries the mask`() {
        val result = redact("""{"password":"hunter2"}""")
        assertEquals(REDACTED_PLACEHOLDER, result["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a polymorphic value under a sensitive key keeps its discriminator`() {
        val result = redact(
            """{"credentials":{"type":"com.example.BasicAuth","secret":"s","retries":2}}"""
        )
        val credentials = result["credentials"]!!.jsonObject
        assertEquals(
            "com.example.BasicAuth",
            credentials["type"]!!.jsonPrimitive.content,
            "Masking the discriminator leaves a type name no serializer can resolve"
        )
        assertEquals(REDACTED_PLACEHOLDER, credentials["secret"]!!.jsonPrimitive.content)
        assertEquals("2", credentials["retries"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a discriminator is preserved at any depth`() {
        val result = redact(
            """{"token":{"inner":{"type":"com.example.Nested","password":"p"}}}"""
        )
        val inner = result["token"]!!.jsonObject["inner"]!!.jsonObject
        assertEquals("com.example.Nested", inner["type"]!!.jsonPrimitive.content)
        assertEquals(REDACTED_PLACEHOLDER, inner["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a number named by a sensitive key is zeroed`() {
        val result = redact("""{"ssn":123456789,"user":{"cardNumber":4111111111111111}}""")
        assertEquals("0", result["ssn"]!!.jsonPrimitive.content)
        assertEquals("0", result["user"]!!.jsonObject["cardNumber"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an ordinary number inside a secret's object is kept`() {
        val result = redact("""{"password":{"value":"x","attempts":3,"ssn":42}}""")
        val password = result["password"]!!.jsonObject
        assertEquals(REDACTED_PLACEHOLDER, password["value"]!!.jsonPrimitive.content)
        assertEquals("3", password["attempts"]!!.jsonPrimitive.content, "Only the key itself decides")
        assertEquals("0", password["ssn"]!!.jsonPrimitive.content)
    }

    @Test
    fun `non sensitive values are untouched`() {
        val result = redact("""{"count":7,"enabled":true,"name":"ok"}""")
        assertEquals("7", result["count"]!!.jsonPrimitive.content)
        assertTrue(result["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("ok", result["name"]!!.jsonPrimitive.content)
    }
}
