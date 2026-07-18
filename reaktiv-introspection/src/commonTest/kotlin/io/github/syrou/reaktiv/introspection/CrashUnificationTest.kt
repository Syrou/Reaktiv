package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.protocol.CrashOrigin
import io.github.syrou.reaktiv.introspection.protocol.SessionExport
import io.github.syrou.reaktiv.introspection.tracing.IntrospectionLogicObserver
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Serializable
data class CrashTestEntry(val path: String)

@Serializable
data class NavigationState(
    val currentEntry: CrashTestEntry,
    val depth: Int = 0
) : ModuleState

@Serializable
data class CrashTestCounterState(val count: Int = 0) : ModuleState

class CrashUnificationTest {

    private val serializers = SerializersModule {
        polymorphic(ModuleState::class) {
            subclass(NavigationState::class)
            subclass(CrashTestCounterState::class)
        }
    }

    private fun startedCapture(): SessionCapture {
        val capture = SessionCapture()
        capture.start("crash-client", "CrashApp", "JVM")
        capture.attachStateSerializers(serializers)
        return capture
    }

    private fun counterAction() = object : ModuleAction(CrashTestCounterState::class) {}
    private fun navAction() = object : ModuleAction(NavigationState::class) {}

    private suspend fun decodeExport(capture: SessionCapture): SessionExport =
        reaktivJson().decodeFromString(capture.exportSession())

    @Test
    fun `crash is enriched with route and action index by the worker`() = runTest {
        val capture = startedCapture()

        capture.captureDispatchedAction(counterAction(), CrashTestCounterState(count = 1))
        capture.captureDispatchedAction(navAction(), NavigationState(CrashTestEntry("home/profile")))
        capture.captureDispatchedAction(counterAction(), CrashTestCounterState(count = 2))
        capture.reportCrash(RuntimeException("boom"))

        val export = decodeExport(capture)
        val crash = export.crash
        assertNotNull(crash)
        assertEquals("home/profile", crash.route)
        assertEquals(2, crash.afterActionIndex)
        assertEquals(CrashOrigin.MANUAL, crash.origin)
        capture.stop()
    }

    @Test
    fun `crash without navigation state or actions has no location`() = runTest {
        val capture = startedCapture()

        capture.reportCrash(RuntimeException("early"))

        val export = decodeExport(capture)
        val crash = export.crash
        assertNotNull(crash)
        assertNull(crash.route)
        assertEquals(-1, crash.afterActionIndex)
        capture.stop()
    }

    @Test
    fun `logic failure crash carries method identity and logic origin`() = runTest {
        val capture = startedCapture()
        val observer = IntrospectionLogicObserver(capture)

        observer.onMethodStart(
            LogicMethodStart(
                logicClass = "UserLogic",
                methodName = "fetchUser",
                params = emptyMap(),
                callId = "call-7",
                timestampMs = 1000L
            )
        )
        observer.onMethodFailed(
            LogicMethodFailed(
                callId = "call-7",
                exceptionType = "IllegalStateException",
                exceptionMessage = "no user",
                stackTrace = "stack",
                durationMs = 12L,
                timestampMs = 1012L
            )
        )

        val export = decodeExport(capture)
        val crash = export.crash
        assertNotNull(crash)
        assertEquals(CrashOrigin.LOGIC_METHOD, crash.origin)
        assertEquals("UserLogic", crash.logicClass)
        assertEquals("fetchUser", crash.methodName)
        assertEquals("call-7", crash.callId)
        assertEquals("IllegalStateException", crash.exception.exceptionType)
        capture.stop()
    }

    @Test
    fun `every reported crash is exported and crash holds the last one`() = runTest {
        val capture = startedCapture()

        capture.captureDispatchedAction(counterAction(), CrashTestCounterState(count = 1))
        capture.reportCrash(RuntimeException("first"))
        capture.captureDispatchedAction(counterAction(), CrashTestCounterState(count = 2))
        capture.reportCrash(RuntimeException("second"))

        val export = decodeExport(capture)
        assertEquals(2, export.crashes.size)
        assertEquals("first", export.crashes[0].exception.message)
        assertEquals("second", export.crashes[1].exception.message)
        assertEquals(0, export.crashes[0].afterActionIndex)
        assertEquals(1, export.crashes[1].afterActionIndex)
        assertEquals("second", export.crash?.exception?.message)
        capture.stop()
    }

    @Test
    fun `exportCrashSession marks the crash as uncaught`() = runTest {
        val capture = startedCapture()

        val json = capture.exportCrashSession(RuntimeException("fatal"))
        val export = reaktivJson().decodeFromString<SessionExport>(json)

        assertEquals(CrashOrigin.UNCAUGHT, export.crash?.origin)
        assertEquals(1, export.crashes.size)
        capture.stop()
    }

    @Test
    fun `clear removes stored crashes`() = runTest {
        val capture = startedCapture()

        capture.reportCrash(RuntimeException("gone"))
        capture.flush()
        capture.clear()

        val export = decodeExport(capture)
        assertNull(export.crash)
        assertEquals(0, export.crashes.size)
        capture.stop()
    }
}
