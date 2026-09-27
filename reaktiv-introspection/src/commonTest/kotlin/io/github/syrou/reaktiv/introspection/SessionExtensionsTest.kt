package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.capture.SessionHistory
import io.github.syrou.reaktiv.introspection.capture.chunked
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.CapturedLog
import io.github.syrou.reaktiv.introspection.protocol.SessionExport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionExtensionsTest {

    private val map: JsonElement = buildJsonObject {
        put("routes", 3)
        put("webPrefix", "/app/")
    }

    private fun action(index: Int) = CapturedAction(
        clientId = "c",
        timestamp = index.toLong(),
        actionType = "A$index",
        actionData = "",
        stateDeltaJson = "{}",
        moduleName = "M"
    )

    private fun log(index: Int) = CapturedLog(level = "INFO", category = "app", message = "line $index", timestampMs = index.toLong())

    @Test
    fun `a published extension is carried in the history and the export`() = runTest {
        val capture = SessionCapture()
        capture.start("tab", "Chrome 153 on Windows", "Web")

        capture.putExtension("navigation.links", map)

        assertEquals(mapOf("navigation.links" to map), capture.getSessionHistory().extensions)
        val export = reaktivJson().decodeFromString(SessionExport.serializer(), capture.exportSession())
        assertEquals(mapOf("navigation.links" to map), export.extensions)
        capture.stop()
    }

    @Test
    fun `publishing again under the same key replaces the value`() = runTest {
        val capture = SessionCapture()
        capture.start("tab", "Chrome 153 on Windows", "Web")
        val replacement = buildJsonObject { put("routes", 4) }

        capture.putExtension("navigation.links", map)
        capture.putExtension("navigation.links", replacement)

        assertEquals(mapOf("navigation.links" to replacement), capture.getSessionHistory().extensions)
        capture.stop()
    }

    @Test
    fun `extensions travel on the first chunk only`() {
        val history = SessionHistory(
            startTime = 1L,
            actions = (0 until 620).map { action(it) },
            logicStarted = emptyList(),
            logicCompleted = emptyList(),
            logicFailed = emptyList(),
            extensions = mapOf("navigation.links" to map)
        )

        val chunks = history.chunked(actionsPerChunk = 250)

        assertEquals(3, chunks.size)
        assertEquals(mapOf("navigation.links" to map), chunks[0].extensions)
        assertTrue(chunks.drop(1).all { it.extensions.isEmpty() })
    }

    @Test
    fun `device logs survive a history that has to be chunked`() {
        val history = SessionHistory(
            startTime = 1L,
            actions = (0 until 620).map { action(it) },
            logicStarted = emptyList(),
            logicCompleted = emptyList(),
            logicFailed = emptyList(),
            logs = (0 until 2500).map { log(it) }
        )

        val chunks = history.chunked(actionsPerChunk = 250, eventsPerChunk = 1000)

        assertEquals(history.logs, chunks.flatMap { it.logs })
        assertEquals(history.actions, chunks.flatMap { it.actions })
    }

    @Test
    fun `an export written before extensions existed still decodes`() {
        val legacy = """
            {"version":"3.7","sessionId":"s","exportedAt":1,
             "clientInfo":{"clientId":"c","clientName":"n","platform":"p"},
             "session":{"startTime":1,"endTime":2,"actions":[],"logicStartedEvents":[],
                        "logicCompletedEvents":[],"logicFailedEvents":[]}}
        """.trimIndent()

        val export = reaktivJson().decodeFromString(SessionExport.serializer(), legacy)

        assertEquals(emptyMap(), export.extensions)
    }
}
