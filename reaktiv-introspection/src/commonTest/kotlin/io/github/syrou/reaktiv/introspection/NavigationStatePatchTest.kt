package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.introspection.protocol.NavigationStatePatch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NavigationStatePatchTest {

    @Test
    fun `clears bootstrapping flags on the navigation state entry`() {
        val input = """
            {
              "com.example.OtherState": { "type": "com.example.OtherState", "count": 3 },
              "io.github.syrou.reaktiv.navigation.NavigationState": {
                "type": "io.github.syrou.reaktiv.navigation.NavigationState",
                "isBootstrapping": true
              }
            }
        """.trimIndent()

        val patched = Json.parseToJsonElement(NavigationStatePatch.clearBootstrapping(input)).jsonObject
        val nav = patched["io.github.syrou.reaktiv.navigation.NavigationState"]!!.jsonObject

        assertFalse(nav["isBootstrapping"]!!.jsonPrimitive.boolean)
        assertFalse(nav["isEvaluatingNavigation"]!!.jsonPrimitive.boolean)
        assertEquals(3, patched["com.example.OtherState"]!!.jsonObject["count"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `leaves state without navigation entry untouched`() {
        val input = """{"com.example.OtherState": {"count": 1}}"""
        assertEquals(input, NavigationStatePatch.clearBootstrapping(input))
    }

    @Test
    fun `passes malformed json through unchanged`() {
        val input = "{invalid json!!!"
        assertEquals(input, NavigationStatePatch.clearBootstrapping(input))
    }
}
