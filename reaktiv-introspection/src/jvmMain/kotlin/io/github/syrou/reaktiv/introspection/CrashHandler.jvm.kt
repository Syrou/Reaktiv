package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.introspection.capture.SessionCapture

public actual class CrashHandler actual constructor(
    private val platformContext: PlatformContext,
    private val sessionCapture: SessionCapture
) {
    public actual fun install() {
        // No-op on JVM
    }
}
