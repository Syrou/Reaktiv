package io.github.syrou.reaktiv.core.util

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

public fun interface ReaktivLogSink {
    public fun log(level: String, category: String, message: String)
}

@OptIn(ExperimentalAtomicApi::class)
public object ReaktivDebug {
    private val enabled = AtomicBoolean(false)

    public val isEnabled: Boolean
        get() = enabled.load()

    private val sinks = CopyOnWriteRegistry<ReaktivLogSink>()

    public fun enable() {
        enabled.store(true)
    }

    public fun disable() {
        enabled.store(false)
    }

    public fun addSink(sink: ReaktivLogSink) {
        sinks.add(sink)
    }

    public fun removeSink(sink: ReaktivLogSink) {
        sinks.remove(sink)
    }

    private fun emit(level: String, category: String, message: String) {
        sinks.forEachCatching({}) { it.log(level, category, message) }
    }

    private fun debug(category: String, message: String) {
        emit("DEBUG", category, message)
        if (isEnabled) {
            println("[$category] $message")
        }
    }

    public fun log(level: String, category: String, message: String, throwable: Throwable? = null) {
        val detail = throwable?.let { "$message: ${it.message}" } ?: message
        emit(level.uppercase(), category, detail)
    }

    public fun nav(message: String): Unit = debug("NAV", message)
    public fun store(message: String): Unit = debug("STORE", message)
    public fun general(message: String): Unit = debug("GENERAL", message)
    public fun trace(message: String): Unit = debug("TRACE", message)

    public fun warn(message: String) {
        emit("WARN", "GENERAL", message)
        if (isEnabled) {
            println("[WARN] $message")
        }
    }

    public fun error(message: String, throwable: Throwable? = null) {
        error("GENERAL", message, throwable)
    }

    public fun error(category: String, message: String, throwable: Throwable?) {
        val detail = throwable?.let { "$message: ${it.message}" } ?: message
        emit("ERROR", category, detail)
        if (isEnabled) {
            println("[ERROR] [$category] $message")
            throwable?.printStackTrace()
        }
    }
}
