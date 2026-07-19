package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.CrashDiagnosis
import io.github.syrou.reaktiv.introspection.protocol.CrashException
import io.github.syrou.reaktiv.introspection.protocol.CrashInfo
import io.github.syrou.reaktiv.introspection.protocol.CrashOrigin
import io.github.syrou.reaktiv.introspection.protocol.DeltaKind
import io.github.syrou.reaktiv.introspection.protocol.buildCrashDiagnosis
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CrashDiagnosisTest {

    private fun action(
        type: String,
        data: String,
        module: String,
        delta: String,
        kind: DeltaKind = DeltaKind.FIELDS
    ) = CapturedAction(
        clientId = "c",
        timestamp = 0L,
        actionType = type,
        actionData = data,
        stateDeltaJson = delta,
        moduleName = module,
        deltaKind = kind
    )

    @Test
    fun pinpointsFailingLogicRouteAndSuspectNullField() {
        val crash = CrashInfo(
            timestamp = 100L,
            exception = CrashException("NullPointerException", "response.body is null", "stack"),
            origin = CrashOrigin.UNCAUGHT,
            callId = "c1",
            route = "/streams",
            afterActionIndex = 2
        )
        val actions = listOf(
            action("SelectTab", "SelectTab(streams)", "app.TwitchStreamsState", """{"type":"TwitchStreamsState","tab":"streams"}"""),
            action("ClearStreams", "ClearStreams", "app.TwitchStreamsState", """{"type":"TwitchStreamsState","streams":null}"""),
            action("LoadStreams", "LoadStreams(page=3)", "app.TwitchStreamsState", """{"type":"TwitchStreamsState","isLoading":true}""")
        )
        val started = listOf(
            LogicMethodStart(
                logicClass = "app.TwitchStreamsLogic",
                methodName = "loadStreams",
                params = mapOf("page" to "3"),
                callId = "c1",
                timestampMs = 90L,
                sourceFile = "TwitchStreamsLogic.kt",
                lineNumber = 42,
                githubSourceUrl = "https://github.com/x/y/blob/main/TwitchStreamsLogic.kt#L42"
            )
        )
        val failed = listOf(
            LogicMethodFailed("c1", "NullPointerException", "response.body is null", null, 10L, 100L)
        )

        val d = buildCrashDiagnosis(crash, actions, started, failed)

        assertEquals("NullPointerException", d.exceptionType)
        assertEquals("/streams", d.route)
        assertEquals(2, d.afterActionIndex)
        val location = d.location
        assertNotNull(location)
        assertTrue(location.contains("loadStreams"), "location: $location")
        assertTrue(location.contains("TwitchStreamsLogic.kt:42"), "location: $location")
        assertEquals("https://github.com/x/y/blob/main/TwitchStreamsLogic.kt#L42", d.sourceUrl)
        assertEquals("LoadStreams(page=3)", d.triggeringAction)
        assertTrue(d.recentStateChanges.any { it == "TwitchStreamsState.streams" }, "changes: ${d.recentStateChanges}")
        assertTrue(d.suspects.any { it.contains("null", ignoreCase = true) }, "suspects: ${d.suspects}")
        assertTrue(d.text.contains("Crash: NullPointerException"), d.text)
        assertTrue(d.text.contains("Reproduce:"), d.text)
        assertTrue(d.text.contains("action #2"), d.text)
    }

    @Test
    fun degradesGracefullyWithNoActionsOrLogic() {
        val crash = CrashInfo(
            timestamp = 1L,
            exception = CrashException("IllegalStateException", null, ""),
            origin = CrashOrigin.MANUAL
        )
        val d = buildCrashDiagnosis(crash, emptyList(), emptyList(), emptyList())
        assertEquals("IllegalStateException", d.exceptionType)
        assertTrue(d.text.contains("Crash: IllegalStateException"), d.text)
    }

    @Test
    fun roundTripsThroughJson() {
        val crash = CrashInfo(
            timestamp = 1L,
            exception = CrashException("NullPointerException", "boom", "stack"),
            afterActionIndex = 0
        )
        val d = buildCrashDiagnosis(crash, emptyList(), emptyList(), emptyList())
        val json = Json.encodeToString(CrashDiagnosis.serializer(), d)
        val decoded = Json.decodeFromString(CrashDiagnosis.serializer(), json)
        assertEquals(d.text, decoded.text)
        assertEquals(d.exceptionType, decoded.exceptionType)
    }
}
