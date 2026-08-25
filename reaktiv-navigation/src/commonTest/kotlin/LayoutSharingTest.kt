import io.github.syrou.reaktiv.navigation.ui.decideLayoutSharing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayoutSharingTest {

    @Test
    fun `timed back inside one graph keeps the shared layout static`() {
        val decision = decideLayoutSharing(
            currentLayoutRoutes = listOf("wallet"),
            previousLayoutRoutes = listOf("wallet"),
            revealedLayoutRoutes = null,
            restingBackLayoutRoutes = listOf("home"),
            shouldAnimateExit = true
        )

        assertTrue("wallet" in decision.sharedRoutes, "shared: ${decision.sharedRoutes}")
        assertFalse(decision.liftExiting)
    }

    @Test
    fun `forward navigation inside one graph keeps the shared layout static`() {
        val decision = decideLayoutSharing(
            currentLayoutRoutes = listOf("wallet"),
            previousLayoutRoutes = listOf("wallet"),
            revealedLayoutRoutes = null,
            restingBackLayoutRoutes = listOf("wallet"),
            shouldAnimateExit = false
        )

        assertEquals(setOf("wallet"), decision.sharedRoutes)
        assertFalse(decision.liftExiting)
    }

    @Test
    fun `resting pre staging for a back gesture applies when nothing is animating`() {
        val decision = decideLayoutSharing(
            currentLayoutRoutes = listOf("wallet"),
            previousLayoutRoutes = null,
            revealedLayoutRoutes = null,
            restingBackLayoutRoutes = listOf("home"),
            shouldAnimateExit = false
        )

        assertTrue(decision.sharedRoutes.isEmpty(), "shared: ${decision.sharedRoutes}")
    }

    @Test
    fun `active scrub intersects the revealed layouts and ignores resting pre staging`() {
        val decision = decideLayoutSharing(
            currentLayoutRoutes = listOf("home", "wallet"),
            previousLayoutRoutes = null,
            revealedLayoutRoutes = listOf("home"),
            restingBackLayoutRoutes = listOf("account"),
            shouldAnimateExit = false
        )

        assertEquals(setOf("home"), decision.sharedRoutes)
    }

    @Test
    fun `changed layouts with an animating exit lift the exiting screen out`() {
        val decision = decideLayoutSharing(
            currentLayoutRoutes = listOf("wallet"),
            previousLayoutRoutes = listOf("home"),
            revealedLayoutRoutes = null,
            restingBackLayoutRoutes = null,
            shouldAnimateExit = true
        )

        assertTrue(decision.liftExiting)
        assertTrue(decision.sharedRoutes.isEmpty(), "shared: ${decision.sharedRoutes}")
    }

    @Test
    fun `changed layouts without an animating exit do not lift`() {
        val decision = decideLayoutSharing(
            currentLayoutRoutes = listOf("wallet"),
            previousLayoutRoutes = listOf("home"),
            revealedLayoutRoutes = null,
            restingBackLayoutRoutes = null,
            shouldAnimateExit = false
        )

        assertFalse(decision.liftExiting)
        assertTrue(
            decision.sharedRoutes.isEmpty(),
            "the exiting screen never had 'wallet', so it cannot be shared chrome"
        )
    }

    @Test
    fun `a graph layout travels with a screen that animates up over a static screen`() {
        val decision = decideLayoutSharing(
            currentLayoutRoutes = listOf("checkout-sheet"),
            previousLayoutRoutes = listOf("home"),
            revealedLayoutRoutes = null,
            restingBackLayoutRoutes = null,
            shouldAnimateExit = false
        )

        assertTrue(
            "checkout-sheet" !in decision.sharedRoutes,
            "the arriving graph's layout must animate with its screen, not sit outside the " +
                "transition, or the sheet slides up underneath its own chrome"
        )
    }

    @Test
    fun `entering a graph layout from a screen with no layout at all`() {
        val decision = decideLayoutSharing(
            currentLayoutRoutes = listOf("checkout-sheet"),
            previousLayoutRoutes = emptyList(),
            revealedLayoutRoutes = null,
            restingBackLayoutRoutes = null,
            shouldAnimateExit = false
        )

        assertTrue(decision.sharedRoutes.isEmpty())
    }

    @Test
    fun `nested shared chrome survives a timed transition into a deeper graph`() {
        val decision = decideLayoutSharing(
            currentLayoutRoutes = listOf("wallet", "royalty-analytics"),
            previousLayoutRoutes = listOf("wallet"),
            revealedLayoutRoutes = null,
            restingBackLayoutRoutes = listOf("home"),
            shouldAnimateExit = true
        )

        assertTrue(decision.liftExiting)
        assertEquals(setOf("wallet"), decision.sharedRoutes)
    }

    @Test
    fun `an exiting screen keeps its unshared chrome even when the exit does not animate`() {
        val decision = decideLayoutSharing(
            currentLayoutRoutes = listOf("second"),
            previousLayoutRoutes = listOf("first"),
            revealedLayoutRoutes = null,
            restingBackLayoutRoutes = null,
            shouldAnimateExit = false
        )
        assertEquals(
            setOf("first"),
            decision.exitingUniqueRoutes,
            "the outgoing screen is still on screen, so stripping its header would change its " +
                "height and shift its content for the length of the transition"
        )
    }

    @Test
    fun `an exiting screen keeps its unshared chrome when the exit does animate`() {
        val decision = decideLayoutSharing(
            currentLayoutRoutes = listOf("second"),
            previousLayoutRoutes = listOf("first"),
            revealedLayoutRoutes = null,
            restingBackLayoutRoutes = null,
            shouldAnimateExit = true
        )
        assertEquals(setOf("first"), decision.exitingUniqueRoutes)
    }

    @Test
    fun `chrome shared with the arriving screen is not repeated around the exiting one`() {
        val decision = decideLayoutSharing(
            currentLayoutRoutes = listOf("main"),
            previousLayoutRoutes = listOf("main"),
            revealedLayoutRoutes = null,
            restingBackLayoutRoutes = null,
            shouldAnimateExit = true
        )
        assertEquals(
            emptySet(),
            decision.exitingUniqueRoutes,
            "a shared layout renders once outside both slots, so the exiting slot must not add it"
        )
    }
}
