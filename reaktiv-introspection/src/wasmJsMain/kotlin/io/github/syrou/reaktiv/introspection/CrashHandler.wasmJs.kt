package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.currentTimeMillis
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.introspection.protocol.CrashException
import io.github.syrou.reaktiv.introspection.protocol.CrashInfo
import io.github.syrou.reaktiv.introspection.protocol.CrashOrigin

private fun hookUncaughtErrors(win: JsAny, report: (String, String, String) -> Unit): Boolean =
    js("""
        (function(win, report) {
            if (typeof win.addEventListener !== 'function') {
                return false;
            }
            var attached = typeof win.__reaktivCrashReport === 'function';
            win.__reaktivCrashReport = report;
            if (attached) {
                return true;
            }
            function describe(value, fallback) {
                if (value !== null && typeof value === 'object') {
                    var type = value.name || (value.constructor && value.constructor.name) || 'Error';
                    var message = value.message != null ? String(value.message) : fallback;
                    return [String(type), message, value.stack ? String(value.stack) : ''];
                }
                if (value === null || value === undefined) {
                    return ['Error', fallback, ''];
                }
                return [typeof value === 'string' ? 'Error' : typeof value, String(value), ''];
            }
            function forward(value, fallback) {
                var parts = describe(value, fallback);
                try {
                    win.__reaktivCrashReport(parts[0], parts[1], parts[2]);
                } catch (ignored) {
                }
            }
            win.addEventListener('error', function(event) {
                forward(event ? event.error : null, event && event.message ? String(event.message) : '');
            });
            win.addEventListener('unhandledrejection', function(event) {
                forward(event ? event.reason : null, 'Unhandled promise rejection');
            });
            return true;
        })(win, report)
    """)

public actual class CrashHandler actual constructor(
    private val platformContext: PlatformContext,
    private val sessionCapture: SessionCapture
) {
    public actual fun install() {
        val listening = hookUncaughtErrors(platformContext.window) { type, message, stack ->
            sessionCapture.reportCrash(
                CrashInfo(
                    timestamp = currentTimeMillis(),
                    exception = CrashException(type, message.ifEmpty { null }, stack),
                    origin = CrashOrigin.UNCAUGHT
                )
            )
        }
        if (listening) {
            ReaktivDebug.general("Introspection: Crash handler installed (browser)")
        } else {
            ReaktivDebug.warn("Introspection: No window to listen on, uncaught browser errors are not captured")
        }
    }
}
