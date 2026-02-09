package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.introspection.capture.SessionCapture

actual class CrashHandler actual constructor(
    private val platformContext: PlatformContext,
    private val sessionCapture: SessionCapture
) {
    actual fun install() {
        // No-op on WASM
    }
}
