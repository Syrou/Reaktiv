package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.tracing.LogicObserver
import io.github.syrou.reaktiv.core.tracing.LogicTracer
import io.github.syrou.reaktiv.core.tracing.ParamRedaction
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.protocol.SessionExport
import io.github.syrou.reaktiv.tracing.annotations.PII
import io.github.syrou.reaktiv.tracing.annotations.Sensitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private const val SECRET = "hunter2-correct-horse"
private const val EMAIL = "joakim@example.com"

private class RedactionProbeLogic : ModuleLogic() {

    suspend fun signIn(
        username: String,
        @Sensitive password: String,
        @PII email: String
    ): Boolean = username.isNotEmpty() && password == SECRET && email == EMAIL
}

private class RecordingObserver : LogicObserver {
    val starts = mutableListOf<LogicMethodStart>()

    override fun onMethodStart(event: LogicMethodStart) {
        starts.add(event)
    }

    override fun onMethodCompleted(event: LogicMethodCompleted) = Unit

    override fun onMethodFailed(event: LogicMethodFailed) = Unit
}

class TracedParameterRedactionTest {

    private suspend fun tracedSignIn(): LogicMethodStart {
        val observer = RecordingObserver()
        LogicTracer.addObserver(observer)
        try {
            assertTrue(RedactionProbeLogic().signIn("joakim", SECRET, EMAIL))
            return observer.starts.single { it.methodName == "signIn" }
        } finally {
            LogicTracer.removeObserver(observer)
        }
    }

    private suspend fun exported(start: LogicMethodStart): Map<String, String> {
        val capture = SessionCapture()
        capture.start("client-params", "ParamApp", "TestPlatform")
        try {
            capture.captureLogicStarted(start)
            val export = reaktivJson(encodeDefaults = true).decodeFromString<SessionExport>(capture.exportSession())
            return export.session.logicStartedEvents.single().params
        } finally {
            capture.stop()
        }
    }

    @Test
    fun annotatedParametersAreTracedRawAndMarkedForRedaction() = runTest {
        val start = tracedSignIn()

        assertEquals(SECRET, start.params["password"])
        assertEquals(EMAIL, start.params["email"])
        assertEquals(mapOf("password" to ParamRedaction.Sensitive, "email" to ParamRedaction.Pii), start.redactions)
    }

    @Test
    fun sensitiveParameterNeverAppearsInAnExport() = runTest {
        val params = exported(tracedSignIn())

        assertEquals("[REDACTED]", params["password"])
        assertFalse(
            params.values.any { it.contains(SECRET) },
            "The raw secret must not appear in any exported parameter: $params"
        )
    }

    @Test
    fun piiParameterIsMaskedInAnExportButStillRecognisable() = runTest {
        val traced = exported(tracedSignIn()).getValue("email")

        assertFalse(traced.contains("joakim"), "PII local part must be masked, got $traced")
        assertTrue(traced.endsWith("@example.com"), "PII masking keeps the domain, got $traced")
    }

    @Test
    fun unannotatedParameterIsStillTraced() = runTest {
        val start = tracedSignIn()

        assertEquals("joakim", start.params["username"])
        assertEquals("joakim", exported(start)["username"])
    }
}
