package io.github.syrou.reaktiv.core.util

public object ReaktivDebug {
    public var isEnabled: Boolean = false
        private set

    public fun enable() {
        isEnabled = true
    }

    public fun disable() {
        isEnabled = false
    }

    private fun log(category: String, message: String) {
        if (isEnabled) {
            println("[$category] $message")
        }
    }

    public fun nav(message: String): Unit = log("NAV", message)
    public fun store(message: String): Unit = log("STORE", message)
    public fun general(message: String): Unit = log("GENERAL", message)
    public fun trace(message: String): Unit = log("TRACE", message)

    public fun warn(message: String) {
        if (isEnabled) {
            println("[WARN] $message")
        }
    }

    public fun error(message: String, throwable: Throwable? = null) {
        if (isEnabled) {
            println("[ERROR] $message")
            throwable?.printStackTrace()
        }
    }
}
