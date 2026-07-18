package io.github.syrou.reaktiv.introspection.protocol

public class KeyframedReconstructor(
    initialStateJson: String,
    private val actions: List<CapturedAction>,
    private val interval: Int = 500
) {
    private val keyframes: List<String> = buildList {
        var state = initialStateJson
        add(state)
        actions.forEachIndexed { index, action ->
            state = StateReconstructor.applyDelta(state, action)
            if ((index + 1) % interval == 0) {
                add(state)
            }
        }
    }

    public val size: Int get() = actions.size

    public fun stateAt(index: Int): String {
        if (index < 0) return keyframes.first()
        val clamped = index.coerceAtMost(actions.size - 1)
        val frame = ((clamped + 1) / interval).coerceAtMost(keyframes.size - 1)
        var state = keyframes[frame]
        for (i in frame * interval..clamped) {
            state = StateReconstructor.applyDelta(state, actions[i])
        }
        return state
    }
}
