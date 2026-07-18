package io.github.syrou.reaktiv.introspection

/**
 * Binds a stack capturer to the current thread.
 *
 * Called on the thread being monitored (the main thread heartbeat). The returned
 * function can be invoked later from another thread to snapshot that thread's
 * current stack trace, which is how a main-thread stall is attributed to the code
 * actually blocking it. Returns null on platforms that cannot capture another
 * thread's stack (wasm, native).
 */
internal expect fun bindThreadStackCapturer(): () -> String?
