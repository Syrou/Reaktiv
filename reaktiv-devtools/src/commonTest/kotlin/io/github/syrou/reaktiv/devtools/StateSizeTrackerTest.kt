package io.github.syrou.reaktiv.devtools

import io.github.syrou.reaktiv.devtools.protocol.StateSizeTracker
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.DeltaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateSizeTrackerTest {

    private fun action(
        moduleName: String,
        deltaJson: String,
        deltaKind: DeltaKind = DeltaKind.FIELDS,
        timestamp: Long = 0L
    ) = CapturedAction(
        clientId = "c",
        timestamp = timestamp,
        actionType = "A",
        actionData = "",
        stateDeltaJson = deltaJson,
        moduleName = moduleName,
        deltaKind = deltaKind
    )

    @Test
    fun `initial state seeds per module sizes`() {
        val tracker = StateSizeTracker()
        tracker.feedInitial("""{"com.example.AState": {"x": 1}, "com.example.BState": {"y": "hello"}}""")

        val stats = tracker.snapshot()
        assertEquals(2, stats.size)
        assertTrue(stats.all { it.currentBytes > 0 })
        assertEquals(1, stats[0].samples)
    }

    @Test
    fun `field deltas merge into the shadow and update size`() {
        val tracker = StateSizeTracker()
        tracker.feedInitial("""{"com.example.AState": {"x": 1, "items": []}}""")
        val before = tracker.snapshot().first().currentBytes

        tracker.feed(action("com.example.AState", """{"items": [1,2,3,4,5,6,7,8,9]}"""))

        val after = tracker.snapshot().first()
        assertTrue(after.currentBytes > before)
        assertEquals(2, after.samples)
        assertEquals("AState", after.shortName)
    }

    @Test
    fun `monotonic growth builds a streak and flags the module`() {
        val tracker = StateSizeTracker()
        tracker.feedInitial("""{"com.example.AState": {"items": []}}""")

        var items = ""
        repeat(12) { index ->
            items += ""","entry-$index-padding-padding""""
            tracker.feed(action("com.example.AState", """{"items": ["seed"$items]}"""))
        }

        val stats = tracker.snapshot().first()
        assertTrue(stats.growthStreak >= 10)
        assertTrue(stats.growthPercent >= 50)
        assertTrue(stats.isSuspicious)
    }

    @Test
    fun `shrinking resets the growth streak`() {
        val tracker = StateSizeTracker()
        tracker.feedInitial("""{"com.example.AState": {"items": []}}""")

        repeat(11) { index ->
            tracker.feed(action("com.example.AState", """{"items": [${"1,".repeat(index * 5)}1]}"""))
        }
        tracker.feed(action("com.example.AState", """{"items": []}"""))

        val stats = tracker.snapshot().first()
        assertEquals(0, stats.growthStreak)
        assertFalse(stats.isSuspicious)
    }

    @Test
    fun `full deltas replace the shadow`() {
        val tracker = StateSizeTracker()
        tracker.feedInitial("""{"com.example.AState": {"x": 1, "big": "payload-payload-payload"}}""")

        tracker.feed(action("com.example.AState", """{"x": 2}""", deltaKind = DeltaKind.FULL))

        val stats = tracker.snapshot().first()
        assertTrue(stats.currentBytes < stats.maxBytes)
    }

    @Test
    fun `malformed deltas are ignored`() {
        val tracker = StateSizeTracker()
        tracker.feedInitial("""{"com.example.AState": {"x": 1}}""")
        val before = tracker.snapshot().first().currentBytes

        tracker.feed(action("com.example.AState", "{invalid json!!!"))

        assertEquals(before, tracker.snapshot().first().currentBytes)
        assertEquals(1, tracker.processed)
    }
}
