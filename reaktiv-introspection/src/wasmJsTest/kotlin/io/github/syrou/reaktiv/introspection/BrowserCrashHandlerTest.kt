package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.protocol.CrashOrigin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun fakeEventWindow(): JsAny = js("""({
    listeners: {},
    addEventListener: function(type, listener) {
        (this.listeners[type] = this.listeners[type] || []).push(listener);
    }
})""")

private fun listenerCount(win: JsAny, type: String): Int = js("(win.listeners[type] || []).length")

private fun fireError(win: JsAny, message: String) {
    js("""
        (function(win, message) {
            var error = new TypeError(message);
            (win.listeners['error'] || []).forEach(function(listener) {
                listener({ error: error, message: 'Uncaught TypeError: ' + message });
            });
        })(win, message)
    """)
}

private fun fireRejection(win: JsAny, reason: String) {
    js("""
        (function(win, reason) {
            (win.listeners['unhandledrejection'] || []).forEach(function(listener) {
                listener({ reason: reason });
            });
        })(win, reason)
    """)
}

private fun fireThrownBy(win: JsAny, run: () -> Unit) {
    js("""
        (function(win, run) {
            try {
                run();
            } catch (thrown) {
                (win.listeners['error'] || []).forEach(function(listener) {
                    listener({ error: thrown, message: 'Uncaught ' + thrown });
                });
            }
        })(win, run)
    """)
}

class BrowserCrashHandlerTest {

    private fun startedCapture(): SessionCapture =
        SessionCapture().also { it.start("tab", "Chrome 140 on Windows", "Web") }

    @Test
    fun `an uncaught browser error is captured as an uncaught crash`() = runTest {
        val win = fakeEventWindow()
        val capture = startedCapture()

        CrashHandler(PlatformContext(win), capture).install()
        fireError(win, "cannot read properties of null")
        capture.flush()

        val crash = assertNotNull(capture.getCapturedCrash())
        assertEquals(CrashOrigin.UNCAUGHT, crash.origin)
        assertEquals("TypeError", crash.exception.exceptionType)
        assertEquals("cannot read properties of null", crash.exception.message)
        assertTrue(crash.exception.stackTrace.isNotEmpty())
        capture.stop()
    }

    @Test
    fun `an unhandled promise rejection is captured with its reason`() = runTest {
        val win = fakeEventWindow()
        val capture = startedCapture()

        CrashHandler(PlatformContext(win), capture).install()
        fireRejection(win, "fetch aborted")
        capture.flush()

        val crash = assertNotNull(capture.getCapturedCrash())
        assertEquals("Error", crash.exception.exceptionType)
        assertEquals("fetch aborted", crash.exception.message)
        capture.stop()
    }

    @Test
    fun `a kotlin exception escaping into the browser keeps its message`() = runTest {
        val win = fakeEventWindow()
        val capture = startedCapture()

        CrashHandler(PlatformContext(win), capture).install()
        fireThrownBy(win) { throw IllegalStateException("click handler failed") }
        capture.flush()

        val crash = assertNotNull(capture.getCapturedCrash())
        assertTrue(
            crash.exception.message.orEmpty().contains("click handler failed"),
            "got ${crash.exception.exceptionType}: ${crash.exception.message}"
        )
        capture.stop()
    }

    @Test
    fun `installing again reports to the newest capture without adding listeners`() = runTest {
        val win = fakeEventWindow()
        val first = startedCapture()
        val second = startedCapture()

        CrashHandler(PlatformContext(win), first).install()
        CrashHandler(PlatformContext(win), second).install()
        fireError(win, "after reset")
        first.flush()
        second.flush()

        assertEquals(1, listenerCount(win, "error"))
        assertEquals(1, listenerCount(win, "unhandledrejection"))
        assertNull(first.getCapturedCrash())
        assertEquals("after reset", second.getCapturedCrash()?.exception?.message)
        first.stop()
        second.stop()
    }
}
