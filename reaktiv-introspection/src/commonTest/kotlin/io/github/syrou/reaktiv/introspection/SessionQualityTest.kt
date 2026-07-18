package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.capture.SessionHistory
import io.github.syrou.reaktiv.introspection.capture.chunked
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.SessionExport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
data class QualityTestState(val secret: String = "token-123", val count: Int = 0) : ModuleState

class SessionQualityTest {

    private val json = reaktivJson(encodeDefaults = true)

    private val testSerializers = SerializersModule {
        polymorphic(ModuleState::class) {
            subclass(QualityTestState::class)
        }
    }

    @Test
    fun `metadata and dropped records round trip through export`() = runTest {
        val capture = SessionCapture()
        capture.start(
            "client-1", "QualityApp", "TestPlatform",
            metadata = ClientMetadata(appVersion = "1.2.3", osVersion = "14", locale = "sv_SE")
        )

        val export = json.decodeFromString<SessionExport>(capture.exportSession())

        assertEquals("3.3", export.version)
        assertEquals("1.2.3", export.clientInfo.metadata?.appVersion)
        assertEquals("sv_SE", export.clientInfo.metadata?.locale)
        assertEquals(0, export.droppedRecords)
        capture.stop()
    }

    @Test
    fun `redactor applies to per action deltas and initial state`() = runTest {
        val capture = SessionCapture(
            redactor = StateRedactor { _, state ->
                buildJsonObject {
                    state.jsonObject.forEach { (key, value) ->
                        put(key, if (key == "secret") JsonPrimitive("REDACTED") else value)
                    }
                }
            }
        )
        capture.start("client-2", "RedactApp", "TestPlatform")
        capture.attachStateSerializers(testSerializers)

        capture.captureInitialState(
            mapOf("QualityTestState" to QualityTestState(secret = "real-token", count = 1))
        )
        capture.captureDispatchedAction(
            object : io.github.syrou.reaktiv.core.ModuleAction(QualityTestState::class) {},
            QualityTestState(secret = "other-token", count = 2)
        )

        val export = json.decodeFromString<SessionExport>(capture.exportSession())

        val initial = json.parseToJsonElement(export.session.initialStateJson).jsonObject
        val initialModule = initial.values.first().jsonObject
        assertEquals("REDACTED", initialModule["secret"]?.jsonPrimitive?.content)

        val delta = json.parseToJsonElement(export.session.actions.single().stateDeltaJson).jsonObject
        assertEquals("REDACTED", delta["secret"]?.jsonPrimitive?.content)
        assertEquals(2, delta["count"]?.jsonPrimitive?.content?.toInt())
        capture.stop()
    }

    @Test
    fun `version 3_0 exports without new fields still decode`() = runTest {
        val legacy = """
            {
              "version": "3.0",
              "sessionId": "legacy-1",
              "exportedAt": 5000,
              "clientInfo": { "clientId": "c", "clientName": "n", "platform": "p" },
              "session": {
                "startTime": 1000,
                "endTime": 4000,
                "actions": [],
                "logicStartedEvents": [],
                "logicCompletedEvents": [],
                "logicFailedEvents": []
              }
            }
        """.trimIndent()

        val export = json.decodeFromString<SessionExport>(legacy)

        assertEquals("3.0", export.version)
        assertEquals(0, export.droppedRecords)
        assertEquals(null, export.clientInfo.metadata)
    }
}

class SessionHistoryChunkingTest {

    private fun action(index: Int) = CapturedAction(
        clientId = "c",
        timestamp = index.toLong(),
        actionType = "A$index",
        actionData = "",
        stateDeltaJson = "{}",
        moduleName = "M"
    )

    @Test
    fun `small history stays a single chunk`() {
        val history = SessionHistory(
            startTime = 1L,
            initialStateJson = """{"a":1}""",
            actions = (0 until 10).map { action(it) },
            logicStarted = emptyList(),
            logicCompleted = emptyList(),
            logicFailed = emptyList()
        )
        val chunks = history.chunked()
        assertEquals(1, chunks.size)
        assertEquals(history, chunks.single())
    }

    @Test
    fun `large history splits preserving order and first chunk initial state`() {
        val history = SessionHistory(
            startTime = 1L,
            initialStateJson = """{"a":1}""",
            actions = (0 until 620).map { action(it) },
            logicStarted = emptyList(),
            logicCompleted = emptyList(),
            logicFailed = emptyList()
        )
        val chunks = history.chunked(actionsPerChunk = 250)
        assertEquals(3, chunks.size)
        assertEquals(250, chunks[0].actions.size)
        assertEquals(250, chunks[1].actions.size)
        assertEquals(120, chunks[2].actions.size)
        assertEquals("""{"a":1}""", chunks[0].initialStateJson)
        assertEquals("{}", chunks[1].initialStateJson)
        assertEquals(history.actions, chunks.flatMap { it.actions })
    }
}
