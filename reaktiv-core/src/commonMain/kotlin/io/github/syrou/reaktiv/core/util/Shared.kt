package io.github.syrou.reaktiv.core.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
public fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

public fun reaktivJson(
    serializersModule: SerializersModule? = null,
    encodeDefaults: Boolean = false,
    prettyPrint: Boolean = false
): Json = Json {
    ignoreUnknownKeys = true
    this.encodeDefaults = encodeDefaults
    this.prettyPrint = prettyPrint
    serializersModule?.let { this.serializersModule = it }
}
