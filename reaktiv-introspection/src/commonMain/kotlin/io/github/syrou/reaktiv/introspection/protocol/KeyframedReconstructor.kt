package io.github.syrou.reaktiv.introspection.protocol

import kotlinx.serialization.json.JsonObject

public class KeyframedReconstructor(
    initialStateJson: String,
    private val actions: List<CapturedAction>,
    private val interval: Int = 500
) {
    private val keyframes: List<Map<String, JsonObject>> = buildList {
        val shadow = ModuleShadow(initialStateJson)
        add(shadow.snapshot())
        actions.forEachIndexed { index, action ->
            shadow.apply(action)
            if ((index + 1) % interval == 0) {
                add(shadow.snapshot())
            }
        }
    }

    public val size: Int get() = actions.size

    public fun stateAt(index: Int): String {
        if (index < 0) return shadowOf(keyframes.first()).encode()
        val clamped = index.coerceAtMost(actions.size - 1)
        val frame = ((clamped + 1) / interval).coerceAtMost(keyframes.size - 1)
        val shadow = shadowOf(keyframes[frame])
        for (i in frame * interval..clamped) {
            shadow.apply(actions[i])
        }
        return shadow.encode()
    }

    private fun shadowOf(modules: Map<String, JsonObject>): ModuleShadow =
        ModuleShadow().apply { modules.forEach { (name, state) -> put(name, state) } }
}
