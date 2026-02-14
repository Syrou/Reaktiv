package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.introspection.protocol.toCaptured
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for LogicTracer event to Captured type converters.
 */
class EventConvertersTest {

    @Test
    fun testLogicMethodStartToCaptured() {
        val clientId = "test-client-123"
        val event = LogicMethodStart(
            logicClass = "com.example.TestLogic",
            methodName = "doSomething",
            params = mapOf("key1" to "value1", "key2" to "value2"),
            callId = "call-456",
            timestampMs = 1000000L,
            sourceFile = "TestLogic.kt",
            lineNumber = 42,
            githubSourceUrl = "https://github.com/user/repo/blob/main/TestLogic.kt#L42"
        )

        val captured = event.toCaptured(clientId)

        assertEquals(clientId, captured.clientId)
        assertEquals("call-456", captured.callId)
        assertEquals("com.example.TestLogic", captured.logicClass)
        assertEquals("doSomething", captured.methodName)
        assertEquals(mapOf("key1" to "value1", "key2" to "value2"), captured.params)
        assertEquals("TestLogic.kt", captured.sourceFile)
        assertEquals(42, captured.lineNumber)
        assertEquals("https://github.com/user/repo/blob/main/TestLogic.kt#L42", captured.githubSourceUrl)
        assertNotNull(captured.timestamp)
    }

    @Test
    fun testLogicMethodStartToCapturedWithNullOptionalFields() {
        val clientId = "test-client-123"
        val event = LogicMethodStart(
            logicClass = "com.example.TestLogic",
            methodName = "doSomething",
            params = emptyMap(),
            callId = "call-456",
            timestampMs = 1000000L,
            sourceFile = null,
            lineNumber = null,
            githubSourceUrl = null
        )

        val captured = event.toCaptured(clientId)

        assertEquals(clientId, captured.clientId)
        assertEquals("call-456", captured.callId)
        assertEquals("com.example.TestLogic", captured.logicClass)
        assertEquals("doSomething", captured.methodName)
        assertEquals(emptyMap(), captured.params)
        assertEquals(null, captured.sourceFile)
        assertEquals(null, captured.lineNumber)
        assertEquals(null, captured.githubSourceUrl)
    }

    @Test
    fun testLogicMethodCompletedToCaptured() {
        val clientId = "test-client-123"
        val event = LogicMethodCompleted(
            callId = "call-456",
            result = "success-result",
            resultType = "String",
            durationMs = 150L
        )

        val captured = event.toCaptured(clientId)

        assertEquals(clientId, captured.clientId)
        assertEquals("call-456", captured.callId)
        assertEquals("success-result", captured.result)
        assertEquals("String", captured.resultType)
        assertEquals(150L, captured.durationMs)
        assertNotNull(captured.timestamp)
    }

    @Test
    fun testLogicMethodCompletedToCapturedWithNullResult() {
        val clientId = "test-client-123"
        val event = LogicMethodCompleted(
            callId = "call-456",
            result = null,
            resultType = "Unit",
            durationMs = 50L
        )

        val captured = event.toCaptured(clientId)

        assertEquals(clientId, captured.clientId)
        assertEquals("call-456", captured.callId)
        assertEquals(null, captured.result)
        assertEquals("Unit", captured.resultType)
        assertEquals(50L, captured.durationMs)
    }

    @Test
    fun testLogicMethodFailedToCaptured() {
        val clientId = "test-client-123"
        val event = LogicMethodFailed(
            callId = "call-456",
            exceptionType = "IllegalStateException",
            exceptionMessage = "Something went wrong",
            stackTrace = "at com.example.TestLogic.doSomething(TestLogic.kt:42)\n...",
            durationMs = 75L
        )

        val captured = event.toCaptured(clientId)

        assertEquals(clientId, captured.clientId)
        assertEquals("call-456", captured.callId)
        assertEquals("IllegalStateException", captured.exceptionType)
        assertEquals("Something went wrong", captured.exceptionMessage)
        assertEquals("at com.example.TestLogic.doSomething(TestLogic.kt:42)\n...", captured.stackTrace)
        assertEquals(75L, captured.durationMs)
        assertNotNull(captured.timestamp)
    }

    @Test
    fun testLogicMethodFailedToCapturedWithNullOptionalFields() {
        val clientId = "test-client-123"
        val event = LogicMethodFailed(
            callId = "call-456",
            exceptionType = "RuntimeException",
            exceptionMessage = null,
            stackTrace = null,
            durationMs = 25L
        )

        val captured = event.toCaptured(clientId)

        assertEquals(clientId, captured.clientId)
        assertEquals("call-456", captured.callId)
        assertEquals("RuntimeException", captured.exceptionType)
        assertEquals(null, captured.exceptionMessage)
        assertEquals(null, captured.stackTrace)
        assertEquals(25L, captured.durationMs)
    }

    @Test
    fun testTimestampIsGenerated() {
        val clientId = "test-client-123"
        val event = LogicMethodStart(
            logicClass = "TestLogic",
            methodName = "test",
            params = emptyMap(),
            callId = "call-1",
            timestampMs = 1000000L
        )

        val captured = event.toCaptured(clientId)

        assertNotNull(captured.timestamp)
    }
}
