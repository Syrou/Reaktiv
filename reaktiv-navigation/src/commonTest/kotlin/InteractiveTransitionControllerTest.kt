import androidx.compose.runtime.Composable
import androidx.compose.runtime.MonotonicFrameClock
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.ScrubState
import io.github.syrou.reaktiv.navigation.ScrubType
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
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
        top = start("detail", 1),
        revealed = start("home", 0)
    )

    @Test
    fun `beginScrub succeeds only from Idle`() = runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
        val controller = InteractiveTransitionController()
        assertTrue(controller.beginScrub(contentBack()))
        assertFalse(controller.beginScrub(contentBack()))
        assertIs<InteractiveTransitionController.Phase.Scrubbing>(controller.phase)
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
        assertIs<InteractiveTransitionController.Phase.Settling>(controller.phase)
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
            assertIs<InteractiveTransitionController.Phase.Settling>(controller.phase)

            job.cancelAndJoin()

            assertEquals(InteractiveTransitionController.Phase.Idle, controller.phase)
            assertEquals(0f, controller.progress)
            assertEquals(null, controller.scrubKind)
            assertTrue(controller.beginScrub(contentBack()))
        }

    @Test
    fun `a local scrub that ends without landing sends one ScrubEnd`() {
        val sent = mutableListOf<NavigationAction>()
        val controller = InteractiveTransitionController { sent += it }
        controller.beginScrub(contentBack())
        controller.scrubTo(0.5f)
        controller.reset()
        controller.reset()
        assertEquals(1, sent.count { it == NavigationAction.ScrubEnd })
    }

    @Test
    fun `a cancelled settle and the reset after it send one ScrubEnd`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val sent = mutableListOf<NavigationAction>()
            val controller = InteractiveTransitionController { sent += it }
            controller.beginScrub(contentBack())
            controller.scrubTo(0.2f)
            withContext(TestFrameClock()) {
                controller.settle(commit = false)
            }
            controller.reset()
            assertEquals(1, sent.count { it == NavigationAction.ScrubEnd })
        }

    @Test
    fun `a scrub whose change landed sends no ScrubEnd`() {
        val sent = mutableListOf<NavigationAction>()
        val controller = InteractiveTransitionController { sent += it }
        controller.beginScrub(contentBack())
        controller.markLanded()
        controller.reset()
        assertEquals(0, sent.count { it == NavigationAction.ScrubEnd })
    }

    @Test
    fun `a replicated scrub never dispatches`() {
        val sent = mutableListOf<NavigationAction>()
        val controller = InteractiveTransitionController { sent += it }
        controller.beginScrub(contentBack(), InteractiveTransitionController.Source.Replicated)
        controller.scrubTo(0.6f)
        controller.reset()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the committed target is the revealed entry only while a commit settles`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val controller = InteractiveTransitionController()
            val kind = contentBack()
            controller.beginScrub(kind)
            assertEquals(null, controller.committedTarget)
            withContext(TestFrameClock()) {
                controller.settle(commit = true)
                assertEquals(kind.revealed, controller.committedTarget)
                controller.settle(commit = false)
            }
            assertEquals(null, controller.committedTarget)
        }

    @Test
    fun `scrub state keeps its wire form`() {
        val state = ScrubState(ScrubType.Back, "top", "revealed", 0.5f)
        val wire = """{"kind":"back-scrub","topKey":"top","revealedKey":"revealed","progress":0.5}"""
        assertEquals(wire, Json.encodeToString(ScrubState.serializer(), state))
        assertEquals(state, Json.decodeFromString(ScrubState.serializer(), wire))
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
