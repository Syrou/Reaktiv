package io.github.syrou.reaktiv.core.util

object ReaktivDebug {
    var isEnabled: Boolean = false
        private set
    private var enabledCategories: Set<String> = setOf("NAV", "STATE", "ACTION", "STORE", "GENERAL", "TRACE")
    
    
    fun enable() {
        isEnabled = true
        println("🔍 Reaktiv Debug: ENABLED")
    }
    
    
    fun disable() {
        isEnabled = false
        println("🔍 Reaktiv Debug: DISABLED")
    }
    
    
    fun enableOnly(vararg categories: String) {
        isEnabled = true
        enabledCategories = categories.toSet()
        println("🔍 Reaktiv Debug: ENABLED for categories: ${categories.joinToString()}")
    }
    
    
    fun developmentMode() {
        enable()
    }
    
    
    fun productionMode() {
        disable()
    }
    private fun log(category: String, message: String) {
        if (isEnabled && category in enabledCategories) {
            println("🔍 [$category] $message")
        }
    }
    fun nav(message: String) = log("NAV", message)
    fun state(message: String) = log("STATE", message)
    fun action(message: String) = log("ACTION", message)
    fun store(message: String) = log("STORE", message)
    fun compose(message: String) = log("COMPOSE", message)
    fun general(message: String) = log("GENERAL", message)
    fun trace(message: String) = log("TRACE", message)
    fun debug(category: String, message: String) = log(category, message)
    fun warn(message: String) {
        if (isEnabled) {
            println("⚠️ [WARN] $message")
        }
    }
    fun error(message: String, throwable: Throwable? = null) {
        if (isEnabled) {
            println("❌ [ERROR] $message")
            throwable?.printStackTrace()
        }
    }
}