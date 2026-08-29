package io.github.syrou.reaktiv.devtools.ui

/**
 * Nesting depth per call id, following `parentCallId` chains.
 *
 * Memoised, and guarded against cycles so a malformed chain yields depth 0 rather than
 * recursing forever.
 *
 * Views disagree about which parents count, so [parentCounts] decides. The event stream hides
 * synthetic pipeline spans and therefore does not indent under them, while the flame chart draws
 * them and does. That is a genuine difference in what each view shows, so it stays a parameter
 * rather than being resolved one way for both.
 */
internal fun logicCallDepths(
    events: List<LogicMethodEvent>,
    parentCounts: (LogicMethodEvent.Started) -> Boolean = { true }
): (String) -> Int {
    val startsByCallId = events.filterIsInstance<LogicMethodEvent.Started>().associateBy { it.callId }
    val cache = mutableMapOf<String, Int>()

    fun depthOf(callId: String, guard: MutableSet<String>): Int {
        cache[callId]?.let { return it }
        if (!guard.add(callId)) return 0
        val parentId = startsByCallId[callId]?.event?.parentCallId
        val parent = parentId?.let { startsByCallId[it] }
        val depth = if (parent != null && parentCounts(parent)) depthOf(parentId, guard) + 1 else 0
        cache[callId] = depth
        return depth
    }

    return { callId -> depthOf(callId, mutableSetOf()) }
}
