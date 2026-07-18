package io.github.syrou.reaktiv.introspection

internal actual fun bindThreadStackCapturer(): () -> String? {
    val thread = Thread.currentThread()
    return {
        runCatching {
            thread.stackTrace
                .take(STACK_FRAME_LIMIT)
                .joinToString("\n") { "    at $it" }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

private const val STACK_FRAME_LIMIT = 40
