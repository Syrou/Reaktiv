package io.github.syrou.reaktiv.devtools.protocol

import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.DeltaKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

public const val SIZE_GROWTH_STREAK_THRESHOLD: Int = 10

public const val SIZE_GROWTH_PERCENT_THRESHOLD: Int = 50

public data class ModuleSizeStats(
    val moduleName: String,
    val currentBytes: Int,
    val maxBytes: Int,
    val firstBytes: Int,
    val samples: Int,
    val growthStreak: Int
) {
    public val shortName: String get() = moduleName.substringAfterLast('.')
    public val growthPercent: Int
        get() = if (firstBytes > 0) ((currentBytes - firstBytes) * 100) / firstBytes else 0
    public val isSuspicious: Boolean
        get() = growthStreak >= SIZE_GROWTH_STREAK_THRESHOLD && growthPercent >= SIZE_GROWTH_PERCENT_THRESHOLD
}

public class StateSizeTracker {

    private class MutableSize(
        var currentBytes: Int,
        var maxBytes: Int,
        val firstBytes: Int,
        var samples: Int,
        var growthStreak: Int
    )

    private val shadows = mutableMapOf<String, JsonObject>()
    private val sizes = LinkedHashMap<String, MutableSize>()

    public var processed: Int = 0
        private set

    public fun feedInitial(initialStateJson: String) {
        val root = runCatching { Json.parseToJsonElement(initialStateJson).jsonObject }.getOrNull() ?: return
        for ((moduleName, element) in root) {
            val obj = element as? JsonObject ?: continue
            shadows[moduleName] = obj
            val bytes = obj.toString().length
            sizes[moduleName] = MutableSize(
                currentBytes = bytes,
                maxBytes = bytes,
                firstBytes = bytes,
                samples = 1,
                growthStreak = 0
            )
        }
    }

    public fun feed(action: CapturedAction) {
        processed += 1
        if (action.moduleName.isBlank()) return
        val delta = runCatching { Json.parseToJsonElement(action.stateDeltaJson).jsonObject }.getOrNull() ?: return
        val merged = when (action.deltaKind) {
            DeltaKind.FULL -> delta
            DeltaKind.FIELDS -> {
                val previous = shadows[action.moduleName]
                if (previous != null) JsonObject(previous + delta) else delta
            }
        }
        shadows[action.moduleName] = merged
        val bytes = merged.toString().length

        val entry = sizes[action.moduleName]
        if (entry == null) {
            sizes[action.moduleName] = MutableSize(
                currentBytes = bytes,
                maxBytes = bytes,
                firstBytes = bytes,
                samples = 1,
                growthStreak = 0
            )
        } else {
            entry.growthStreak = if (bytes > entry.currentBytes) entry.growthStreak + 1 else 0
            entry.currentBytes = bytes
            if (bytes > entry.maxBytes) entry.maxBytes = bytes
            entry.samples += 1
        }
    }

    public fun snapshot(): List<ModuleSizeStats> = sizes.map { (moduleName, size) ->
        ModuleSizeStats(
            moduleName = moduleName,
            currentBytes = size.currentBytes,
            maxBytes = size.maxBytes,
            firstBytes = size.firstBytes,
            samples = size.samples,
            growthStreak = size.growthStreak
        )
    }.sortedByDescending { it.currentBytes }
}
