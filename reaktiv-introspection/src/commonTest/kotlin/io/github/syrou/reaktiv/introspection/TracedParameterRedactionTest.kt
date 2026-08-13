package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.tracing.LogicObserver
import io.github.syrou.reaktiv.core.tracing.LogicTracer
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

    @Test
    fun sensitiveParameterNeverAppearsInTheTrace() = runTest {
        val observer = RecordingObserver()
        LogicTracer.addObserver(observer)
        try {
            assertTrue(RedactionProbeLogic().signIn("joakim", SECRET, EMAIL))

            val params = observer.starts.single { it.methodName == "signIn" }.params
            assertEquals("[REDACTED]", params["password"])
            assertFalse(
                params.values.any { it.contains(SECRET) },
                "The raw secret must not appear in any traced parameter: $params"
            )
        } finally {
            LogicTracer.removeObserver(observer)
        }
    }

    @Test
    fun piiParameterIsMaskedButStillRecognisable() = runTest {
        val observer = RecordingObserver()
        LogicTracer.addObserver(observer)
        try {
            RedactionProbeLogic().signIn("joakim", SECRET, EMAIL)

            val params = observer.starts.single { it.methodName == "signIn" }.params
            val traced = params.getValue("email")
            assertFalse(traced.contains("joakim"), "PII local part must be masked, got $traced")
            assertTrue(traced.endsWith("@example.com"), "PII masking keeps the domain, got $traced")
        } finally {
            LogicTracer.removeObserver(observer)
        }
    }

    @Test
    fun unannotatedParameterIsStillTraced() = runTest {
        val observer = RecordingObserver()
        LogicTracer.addObserver(observer)
        try {
            RedactionProbeLogic().signIn("joakim", SECRET, EMAIL)

            val params = observer.starts.single { it.methodName == "signIn" }.params
            assertEquals("joakim", params["username"])
        } finally {
            LogicTracer.removeObserver(observer)
        }
    }
}
