package io.github.syrou.reaktiv.core.util

import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker

@OptIn(ObsoleteWorkersApi::class)
public actual fun currentThreadName(): String =
    runCatching { Worker.current.name }.getOrDefault("native")
