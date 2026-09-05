package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.CrashListener
import io.github.syrou.reaktiv.core.CrashRecovery
import io.github.syrou.reaktiv.core.ExperimentalReaktivApi
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.ModuleWithLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.tracing.LogicTracer
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.protocol.CrashInfo
import io.github.syrou.reaktiv.introspection.protocol.CrashOrigin
import io.github.syrou.reaktiv.introspection.tooling.ToolingLogic
import io.github.syrou.reaktiv.introspection.tooling.createToolingModule
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalReaktivApi::class)
class StoreCrashCaptureTest {

    @Serializable
    data class CrashProbeState(val touched: Int = 0) : ModuleState

    sealed class CrashProbeAction : ModuleAction(CrashProbeModule::class) {
        data object Touch : CrashProbeAction()
    }

    class CrashProbeLogic(private val storeAccessor: StoreAccessor) : ModuleLogic() {
        fun explode(message: String) {
            storeAccessor.launch { throw IllegalStateException(message) }
        }
    }

    object CrashProbeModule : ModuleWithLogic<CrashProbeState, CrashProbeAction, CrashProbeLogic> {
        override val initialState = CrashProbeState()
        override val reducer = { state: CrashProbeState, _: CrashProbeAction -> state.copy(touched = state.touched + 1) }
        override val createLogic = { accessor: StoreAccessor -> CrashProbeLogic(accessor) }
    }

    private fun config() = IntrospectionConfig(
        clientId = "crash-capture-test",
        clientName = "CrashCaptureTest",
        platform = "JVM"
    )

    @AfterTest
    fun tearDown() {
        LogicTracer.clearObservers()
    }

    @Test
    fun `an exception escaping a store coroutine is captured as an uncaught crash`() = runTest(timeout = 10.seconds) {
        val store = createStore {
            module(createToolingModule(config(), PlatformContext()) {})
            module(CrashProbeModule)
        }
        try {
            store.initialized.first { it }
            store.addCrashListener(object : CrashListener {
                override suspend fun onLogicCrash(exception: Throwable, action: ModuleAction?): CrashRecovery =
                    CrashRecovery.NAVIGATE_TO_CRASH_SCREEN
            })
            val capture = store.selectLogic<ToolingLogic>().getSessionCapture()
            val crash = async(start = CoroutineStart.UNDISPATCHED) { capture.crashes.first() }

            store.selectLogic<CrashProbeLogic>().explode("boom")

            val info = crash.await()
            assertEquals(CrashOrigin.UNCAUGHT, info.origin)
            assertEquals("IllegalStateException", info.exception.exceptionType)
            assertEquals("boom", info.exception.message)
        } finally {
            store.cleanup()
        }
    }

    @Test
    fun `the same crash reported twice within the window is recorded once`() = runTest(timeout = 10.seconds) {
        val capture = SessionCapture()
        capture.start("dedup-client", "DedupApp", "JVM")
        try {
            val recorded = async(start = CoroutineStart.UNDISPATCHED) { capture.crashes.take(2).toList() }
            val boom = IllegalStateException("boom")
            capture.reportCrash(boom, CrashOrigin.LOGIC_METHOD)
            capture.reportCrash(boom, CrashOrigin.UNCAUGHT)
            capture.reportCrash(IllegalStateException("other"), CrashOrigin.UNCAUGHT)

            val crashes: List<CrashInfo> = recorded.await()
            assertEquals(listOf("boom", "other"), crashes.map { it.exception.message })
            assertEquals(CrashOrigin.LOGIC_METHOD, crashes.first().origin)
        } finally {
            capture.stop()
        }
    }
}
