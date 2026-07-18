package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.tracing.StateRead
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.capture.chunked
import io.github.syrou.reaktiv.introspection.protocol.SessionExport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StateReadCaptureTest {

    @Test
    fun `captured state reads are exported with the session`() = runTest {
        val capture = SessionCapture()
        capture.start("read-client", "ReadApp", "JVM")

        capture.captureStateRead(StateRead("com.example.AuthState", "com.example.LoginScreen"))
        capture.captureStateRead(StateRead("com.example.NavState", "com.example.MainRender"))

        val export = reaktivJson().decodeFromString<SessionExport>(capture.exportSession())
        assertEquals(2, export.session.stateReads.size)
        assertEquals("com.example.AuthState", export.session.stateReads[0].stateClass)
        capture.stop()
    }

    @Test
    fun `state reads ride the first history chunk only`() = runTest {
        val capture = SessionCapture()
        capture.start("read-client", "ReadApp", "JVM")

        capture.captureStateRead(StateRead("com.example.AuthState", "com.example.LoginScreen"))
        for (i in 1..3) {
            capture.captureAction(
                io.github.syrou.reaktiv.introspection.protocol.CapturedAction(
                    clientId = "read-client",
                    timestamp = i.toLong(),
                    actionType = "A$i",
                    actionData = "",
                    stateDeltaJson = "{}",
                    moduleName = "M"
                )
            )
        }

        val chunks = capture.getSessionHistory().chunked(actionsPerChunk = 2)
        assertEquals(2, chunks.size)
        assertEquals(1, chunks[0].stateReads.size)
        assertTrue(chunks[1].stateReads.isEmpty())
        capture.stop()
    }

    @Test
    fun `clear removes captured state reads`() = runTest {
        val capture = SessionCapture()
        capture.start("read-client", "ReadApp", "JVM")

        capture.captureStateRead(StateRead("com.example.AuthState", "com.example.LoginScreen"))
        capture.flush()
        capture.clear()

        val export = reaktivJson().decodeFromString<SessionExport>(capture.exportSession())
        assertEquals(0, export.session.stateReads.size)
        capture.stop()
    }
}
