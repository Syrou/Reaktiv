import androidx.compose.runtime.Composable
import androidx.compose.runtime.MonotonicFrameClock
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.InteractiveTransitionController
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class InteractiveTransitionControllerTest {

    private fun testScreen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
        }
    }

    private fun start(route: String, position: Int) = NavigationEntry(
        navigatable = testScreen(route),
        path = route,
        params = Params.empty(),
        stackPosition = position
    )

    private fun contentBack() = InteractiveTransitionController.ScrubKind.ContentBack(
        topEntry = start("detail", 1),
        revealedEntry = start("home", 0)
    )

    @Test
    fun `beginScrub succeeds only from Idle`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val controller = InteractiveTransitionController()
        assertTrue(controller.beginScrub(contentBack()))
        assertFalse(controller.beginScrub(contentBack()))
        assertEquals(InteractiveTransitionController.Phase.Scrubbing, controller.phase)
    }

    @Test
    fun `scrubTo clamps progress to unit range`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val controller = InteractiveTransitionController()
        controller.beginScrub(contentBack())
        controller.scrubTo(1.5f)
        assertEquals(1f, controller.progress)
        controller.scrubTo(-0.2f)
        assertEquals(0f, controller.progress)
        controller.scrubTo(0.42f)
        assertEquals(0.42f, controller.progress)
    }

    @Test
    fun `scrubTo is ignored when idle`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val controller = InteractiveTransitionController()
        controller.scrubTo(0.8f)
        assertEquals(0f, controller.progress)
    }

    @Test
    fun `settle commit animates to full progress`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val controller = InteractiveTransitionController()
        controller.beginScrub(contentBack())
        controller.scrubTo(0.4f)
        withContext(TestFrameClock()) {
            controller.settle(commit = true)
        }
        assertEquals(1f, controller.progress)
        assertEquals(InteractiveTransitionController.Phase.Settling, controller.phase)
    }

    @Test
    fun `settle cancel animates back to zero`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val controller = InteractiveTransitionController()
        controller.beginScrub(contentBack())
        controller.scrubTo(0.25f)
        withContext(TestFrameClock()) {
            controller.settle(commit = false)
        }
        assertEquals(0f, controller.progress)
    }

    @Test
    fun `settle can reverse a committed settle when dismissal is declined`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val controller = InteractiveTransitionController()
            controller.beginScrub(contentBack())
            controller.scrubTo(0.9f)
            withContext(TestFrameClock()) {
                controller.settle(commit = true)
                assertEquals(1f, controller.progress)
                controller.settle(commit = false)
            }
            assertEquals(0f, controller.progress)
        }

    @Test
    fun `reset returns controller to idle zero state`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val controller = InteractiveTransitionController()
        controller.beginScrub(contentBack())
        controller.scrubTo(0.7f)
        controller.reset()
        assertEquals(InteractiveTransitionController.Phase.Idle, controller.phase)
        assertEquals(0f, controller.progress)
        assertEquals(null, controller.scrubKind)
        assertTrue(controller.beginScrub(contentBack()))
    }

    @Test
    fun `handoff consumes exactly once with matching keys`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val controller = InteractiveTransitionController()
            controller.armHandoff(poppedKey = "detail@1", targetKey = "home@0")
            assertTrue(controller.consumeHandoff(oldKey = "detail@1", newKey = "home@0"))
            assertFalse(controller.consumeHandoff(oldKey = "detail@1", newKey = "home@0"))
        }

    @Test
    fun `handoff is cleared by the next transition even when keys mismatch`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val controller = InteractiveTransitionController()
            controller.armHandoff(poppedKey = "detail@1", targetKey = "home@0")
            assertFalse(controller.consumeHandoff(oldKey = "other@2", newKey = "home@0"))
            assertFalse(controller.consumeHandoff(oldKey = "detail@1", newKey = "home@0"))
        }

    @Test
    fun `reset does not clear a pending handoff`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val controller = InteractiveTransitionController()
            controller.armHandoff(poppedKey = "detail@1", targetKey = "home@0")
            controller.reset()
            assertTrue(controller.consumeHandoff(oldKey = "detail@1", newKey = "home@0"))
        }

    @Test
    fun `modal handoff consumes once and clears on mismatch`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val controller = InteractiveTransitionController()
            controller.armModalHandoff("modal@2")
            assertTrue(controller.consumeModalHandoff("modal@2"))
            assertFalse(controller.consumeModalHandoff("modal@2"))
            controller.armModalHandoff("modal@2")
            assertFalse(controller.consumeModalHandoff("other@3"))
            assertFalse(controller.consumeModalHandoff("modal@2"))
        }

    @Test
    fun `controller recovers when a settle is cancelled mid animation and reset runs`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val controller = InteractiveTransitionController()
            controller.beginScrub(contentBack())
            controller.scrubTo(0.4f)

            val stuckClock = object : MonotonicFrameClock {
                override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R =
                    awaitCancellation()
            }
            val job = launch(stuckClock) {
                try {
                    controller.settle(commit = true)
                } finally {
                    controller.reset()
                }
            }
            testScheduler.runCurrent()
            assertEquals(InteractiveTransitionController.Phase.Settling, controller.phase)

            job.cancelAndJoin()

            assertEquals(InteractiveTransitionController.Phase.Idle, controller.phase)
            assertEquals(0f, controller.progress)
            assertEquals(null, controller.scrubKind)
            assertTrue(controller.beginScrub(contentBack()))
        }

    @Test
    fun `shouldCommit decision matrix`() {
        val threshold = 700f
        assertTrue(InteractiveTransitionController.shouldCommit(0.31f, 0f, threshold))
        assertFalse(InteractiveTransitionController.shouldCommit(0.29f, 0f, threshold))
        assertTrue(InteractiveTransitionController.shouldCommit(0.1f, 800f, threshold))
        assertFalse(InteractiveTransitionController.shouldCommit(0.8f, -800f, threshold))
        assertTrue(InteractiveTransitionController.shouldCommit(0.8f, 0f, threshold))
        assertFalse(InteractiveTransitionController.shouldCommit(0.3f, 0f, threshold))
    }
}
