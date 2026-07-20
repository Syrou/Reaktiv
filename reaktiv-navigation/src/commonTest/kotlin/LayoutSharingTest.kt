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
        assertEquals(setOf("wallet"), decision.sharedRoutes)
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
}
