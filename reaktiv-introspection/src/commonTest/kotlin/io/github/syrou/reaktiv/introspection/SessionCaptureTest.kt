package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionCaptureTest {

    private fun createCapture(maxActions: Int = 100, maxLogicEvents: Int = 200): SessionCapture {
        val capture = SessionCapture(maxActions = maxActions, maxLogicEvents = maxLogicEvents)
        capture.start("test-client", "TestApp", "Test")
        return capture
    }

    private fun capturedAction(index: Int) = CapturedAction(
        clientId = "test-client",
        timestamp = 1000L + index,
        actionType = "TestAction$index",
        actionData = "data-$index",
        stateDeltaJson = "{}",
        moduleName = "TestModule"
    )

    private fun capturedLogicStart(index: Int) = LogicMethodStart(
        logicClass = "TestLogic",
        methodName = "method$index",
        params = emptyMap(),
        callId = "call-$index",
        timestampMs = 1000L + index
    )

    private fun capturedLogicComplete(index: Int) = LogicMethodCompleted(
        callId = "call-$index",
        result = "ok",
        resultType = "String",
        durationMs = 100L,
        timestampMs = 2000L + index
    )

    @Test
    fun `captureAction stores exactly one entry per call`() = runTest {
        val capture = createCapture()

        capture.captureAction(capturedAction(1))
        capture.captureAction(capturedAction(2))
        capture.captureAction(capturedAction(3))

        val history = capture.getSessionHistory()
        assertEquals(3, history.actions.size)
        assertEquals("TestAction1", history.actions[0].actionType)
        assertEquals("TestAction2", history.actions[1].actionType)
        assertEquals("TestAction3", history.actions[2].actionType)
    }

    @Test
    fun `captureAction respects maxActions limit`() = runTest {
        val capture = createCapture(maxActions = 3)

        for (i in 1..5) {
            capture.captureAction(capturedAction(i))
        }

        val history = capture.getSessionHistory()
        assertEquals(3, history.actions.size)
        assertEquals("TestAction3", history.actions[0].actionType)
        assertEquals("TestAction4", history.actions[1].actionType)
        assertEquals("TestAction5", history.actions[2].actionType)
    }

    @Test
    fun `captureLogicStarted stores exactly one entry per call`() = runTest {
        val capture = createCapture()

        capture.captureLogicStarted(capturedLogicStart(1))
        capture.captureLogicStarted(capturedLogicStart(2))

        val history = capture.getSessionHistory()
        assertEquals(2, history.logicStarted.size)
    }

    @Test
    fun `captureLogicCompleted stores exactly one entry per call`() = runTest {
        val capture = createCapture()

        capture.captureLogicCompleted(capturedLogicComplete(1))
        capture.captureLogicCompleted(capturedLogicComplete(2))

        val history = capture.getSessionHistory()
        assertEquals(2, history.logicCompleted.size)
    }

    @Test
    fun `does not capture when not started`() = runTest {
        val capture = SessionCapture()

        capture.captureAction(capturedAction(1))
        capture.captureLogicStarted(capturedLogicStart(1))

        val history = capture.getSessionHistory()
        assertEquals(0, history.actions.size)
        assertEquals(0, history.logicStarted.size)
    }

    @Test
    fun `exportSession produces valid JSON`() = runTest {
        val capture = createCapture()

        capture.captureAction(capturedAction(1))
        capture.captureLogicStarted(capturedLogicStart(1))
        capture.captureLogicCompleted(capturedLogicComplete(1))

        val json = capture.exportSession()
        assertTrue(json.contains("\"version\""))
        assertTrue(json.contains("\"sessionId\""))
        assertTrue(json.contains("\"clientInfo\""))
        assertTrue(json.contains("\"session\""))
        assertTrue(json.contains("TestAction1"))
        assertTrue(json.contains("TestLogic"))
    }

    @Test
    fun `exportCrashSession includes crash info`() = runTest {
        val capture = createCapture()
        capture.captureAction(capturedAction(1))

        val json = capture.exportCrashSession(RuntimeException("test crash"))
        assertTrue(json.contains("\"crash\""))
        assertTrue(json.contains("test crash"))
        assertTrue(json.contains("TestAction1"))
    }

    @Test
    fun `captureCrashFromThrowable stores crash for later export`() = runTest {
        val capture = createCapture()
        capture.captureAction(capturedAction(1))

        capture.reportCrash(RuntimeException("stored crash"))
        val json = capture.exportSession()

        assertTrue(json.contains("\"crash\""))
        assertTrue(json.contains("stored crash"))
        assertTrue(json.contains("TestAction1"))
    }

    @Test
    fun `clear removes all captured data but keeps session active`() = runTest {
        val capture = createCapture()

        capture.captureAction(capturedAction(1))
        capture.captureLogicStarted(capturedLogicStart(1))
        capture.clear()

        assertTrue(capture.isStarted())
        val history = capture.getSessionHistory()
        assertEquals(0, history.actions.size)
        assertEquals(0, history.logicStarted.size)
    }

    @Test
    fun `stop deactivates capture`() = runTest {
        val capture = createCapture()

        capture.captureAction(capturedAction(1))
        capture.stop()

        assertEquals(false, capture.isStarted())
        capture.captureAction(capturedAction(2))

        val history = capture.getSessionHistory()
        assertEquals(0, history.actions.size)
    }
}
