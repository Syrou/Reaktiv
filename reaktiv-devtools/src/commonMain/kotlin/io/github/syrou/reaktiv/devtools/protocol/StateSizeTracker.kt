package io.github.syrou.reaktiv.devtools.protocol

import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.ModuleShadow
import kotlinx.serialization.json.JsonObject

public const val SIZE_GROWTH_STREAK_THRESHOLD: Int = 10

public const val SIZE_GROWTH_PERCENT_THRESHOLD: Int = 50

public data class ModuleSizeStats(
    val moduleName: String,
    val currentBytes: Int,
    val maxBytes: Int,
    val firstBytes: Int,
    val samples: Int,
    val growthStreak: Int,
    val topGrowingField: String? = null,
    val topGrowingFieldGrowthBytes: Int = 0
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

    private class MutableFieldSize(val firstBytes: Int, var currentBytes: Int)

    private val shadow = ModuleShadow()
    private val sizes = LinkedHashMap<String, MutableSize>()
    private val fieldSizes = mutableMapOf<String, LinkedHashMap<String, MutableFieldSize>>()

    public var processed: Int = 0
        private set

    public fun feedInitial(initialStateJson: String) {
        shadow.seed(initialStateJson)
        for ((moduleName, obj) in shadow.snapshot()) {
            val bytes = obj.toString().length
            sizes[moduleName] = MutableSize(
                currentBytes = bytes,
                maxBytes = bytes,
                firstBytes = bytes,
                samples = 1,
                growthStreak = 0
            )
            trackFieldSizes(moduleName, obj)
        }
    }

    private fun trackFieldSizes(moduleName: String, merged: JsonObject) {
        val fields = fieldSizes.getOrPut(moduleName) { LinkedHashMap() }
        for ((key, value) in merged) {
            if (key == "type") continue
            val bytes = value.toString().length
            val entry = fields[key]
            if (entry == null) {
                fields[key] = MutableFieldSize(firstBytes = bytes, currentBytes = bytes)
            } else {
                entry.currentBytes = bytes
            }
        }
    }

    private fun topGrowingField(moduleName: String): Pair<String, Int>? =
        fieldSizes[moduleName]?.mapNotNull { (field, size) ->
            val growth = size.currentBytes - size.firstBytes
            if (growth > 0) field to growth else null
        }?.maxByOrNull { it.second }

    public fun feed(action: CapturedAction) {
        processed += 1
        val merged = shadow.apply(action) ?: return
        trackFieldSizes(action.moduleName, merged)
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
        val topField = topGrowingField(moduleName)
        ModuleSizeStats(
            moduleName = moduleName,
            currentBytes = size.currentBytes,
            maxBytes = size.maxBytes,
            firstBytes = size.firstBytes,
            samples = size.samples,
            growthStreak = size.growthStreak,
            topGrowingField = topField?.first,
            topGrowingFieldGrowthBytes = topField?.second ?: 0
        )
    }.sortedByDescending { it.currentBytes }
}
