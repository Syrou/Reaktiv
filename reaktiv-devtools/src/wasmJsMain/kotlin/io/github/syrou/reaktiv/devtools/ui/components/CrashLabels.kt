package io.github.syrou.reaktiv.devtools.ui.components

import io.github.syrou.reaktiv.introspection.protocol.CrashInfo
import io.github.syrou.reaktiv.introspection.protocol.CrashOrigin

internal fun crashOriginLabel(origin: CrashOrigin): String = when (origin) {
    CrashOrigin.LOGIC_METHOD -> "Logic method failure"
    CrashOrigin.UNCAUGHT -> "Uncaught exception"
    CrashOrigin.MANUAL -> "Manual report"
}

internal fun crashLocationLabel(info: CrashInfo): String? {
    val logicClass = info.logicClass
    val methodName = info.methodName
    return when {
        logicClass != null && methodName != null -> "$logicClass.$methodName"
        logicClass != null -> logicClass
        methodName != null -> methodName
        else -> null
    }
}
