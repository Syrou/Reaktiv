import io.github.syrou.reaktiv.navigation.ui.LayoutTreeBranch
import io.github.syrou.reaktiv.navigation.ui.LayoutTreeLeaf
import io.github.syrou.reaktiv.navigation.ui.LayoutTreeNode
import io.github.syrou.reaktiv.navigation.ui.LayoutTreeShield
import io.github.syrou.reaktiv.navigation.ui.LayoutTreeSlot
import io.github.syrou.reaktiv.navigation.ui.buildLayoutTree
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.NavigationZIndex
import io.github.syrou.reaktiv.navigation.ui.decideTransitionPlacement
import io.github.syrou.reaktiv.navigation.util.AnimationDecision
import io.github.syrou.reaktiv.navigation.util.IndicatorAnchor
import io.github.syrou.reaktiv.navigation.ui.outermostUnsharedLayout
import io.github.syrou.reaktiv.navigation.ui.sharedLayoutDepth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutTreeTest {

    private fun slot(
        key: String,
        vararg layoutRoutes: String,
        zIndex: Float = 3f,
        indicatorAnchor: IndicatorAnchor? = null,
        shielded: Boolean = false
    ) = LayoutTreeSlot(
        key = key,
        layoutRoutes = layoutRoutes.toList(),
        zIndex = zIndex,
        indicatorAnchor = indicatorAnchor,
        shielded = shielded
    )

    private fun pathTo(nodes: List<LayoutTreeNode>, slotKey: String): List<String>? {
        nodes.forEach { node ->
            when (node) {
                is LayoutTreeLeaf -> if (node.slotKey == slotKey) return listOf(node.key)
                is LayoutTreeBranch -> pathTo(node.children, slotKey)?.let { return listOf(node.key) + it }
                is LayoutTreeShield -> Unit
            }
        }
        return null
    }

    private fun branches(nodes: List<LayoutTreeNode>): List<LayoutTreeBranch> =
        nodes.filterIsInstance<LayoutTreeBranch>().flatMap { listOf(it) + branches(it.children) }

    private fun leaves(nodes: List<LayoutTreeNode>): List<LayoutTreeLeaf> =
        nodes.flatMap { node ->
            when (node) {
                is LayoutTreeLeaf -> listOf(node)
                is LayoutTreeBranch -> leaves(node.children)
                is LayoutTreeShield -> emptyList()
            }
        }

    @Test
    fun `a screen keeps the same path whether or not another screen shares its layout`() {
        val alone = buildLayoutTree(listOf(slot("entering", "wallet")))
        val duringTransition = buildLayoutTree(
            listOf(
                slot("exiting", "wallet", zIndex = 2f),
                slot("entering", "wallet")
            )
        )

        assertEquals(
            pathTo(alone, "entering"),
            pathTo(duringTransition, "entering"),
            "a transition settling must not move the screen it just brought in, or the screen is " +
                "torn down and rebuilt with its remembered state and its DisposableEffect"
        )
    }

    @Test
    fun `a screen keeps the same path when a gesture starts revealing the one beneath it`() {
        val atRest = buildLayoutTree(listOf(slot("top", "wallet")))
        val scrubbing = buildLayoutTree(
            listOf(
                slot("revealed", zIndex = 2f, shielded = true),
                slot("top", "wallet")
            )
        )

        assertEquals(pathTo(atRest, "top"), pathTo(scrubbing, "top"))
    }

    @Test
    fun `a layout two screens have in common is composed once around both of them`() {
        val tree = buildLayoutTree(
            listOf(
                slot("exiting", "wallet", zIndex = 2f),
                slot("entering", "wallet")
            )
        )

        val wallet = branches(tree).single { it.route == "wallet" }
        assertEquals(
            setOf("exiting", "entering"),
            leaves(listOf(wallet)).map { it.slotKey }.toSet()
        )
    }

    @Test
    fun `chrome shared between two screens stays put while the screens animate inside it`() {
        val tree = buildLayoutTree(
            listOf(
                slot("exiting", "wallet", zIndex = 2f),
                slot("entering", "wallet")
            )
        )

        val wallet = branches(tree).single { it.route == "wallet" }
        assertNull(wallet.ownerSlotKey, "shared chrome must not be dragged along by either screen")
        assertTrue(leaves(tree).all { it.ownsTransition })
    }

    @Test
    fun `a graph arriving with its own layout animates together with it`() {
        val tree = buildLayoutTree(
            listOf(
                slot("exiting", zIndex = 2f),
                slot("entering", "checkout-sheet")
            )
        )

        val sheet = branches(tree).single { it.route == "checkout-sheet" }
        assertEquals(
            "entering",
            sheet.ownerSlotKey,
            "the sheet chrome arrived with the screen, so it must slide up with it rather than " +
                "sit outside the transition while the screen slides in underneath it"
        )
        assertFalse(leaves(tree).single { it.slotKey == "entering" }.ownsTransition)
        assertTrue(leaves(tree).single { it.slotKey == "exiting" }.ownsTransition)
    }

    @Test
    fun `nested chrome is shared as far down as the two screens agree`() {
        val tree = buildLayoutTree(
            listOf(
                slot("exiting", "wallet", zIndex = 2f),
                slot("entering", "wallet", "royalty-analytics")
            )
        )

        val wallet = branches(tree).single { it.route == "wallet" }
        val analytics = branches(tree).single { it.route == "royalty-analytics" }
        assertNull(wallet.ownerSlotKey)
        assertEquals("entering", analytics.ownerSlotKey)
        assertEquals(listOf("layout:wallet", "slot:exiting"), pathTo(tree, "exiting"))
        assertEquals(
            listOf("layout:wallet", "layout:royalty-analytics", "slot:entering"),
            pathTo(tree, "entering")
        )
    }

    @Test
    fun `an exiting screen keeps the chrome the arriving one does not have`() {
        val tree = buildLayoutTree(
            listOf(
                slot("exiting", "first", zIndex = 2f),
                slot("entering", "second")
            )
        )

        assertEquals(listOf("layout:first", "slot:exiting"), pathTo(tree, "exiting"))
        assertEquals(listOf("layout:second", "slot:entering"), pathTo(tree, "entering"))
    }

    @Test
    fun `only the outermost layout enclosing one screen carries its transition`() {
        val tree = buildLayoutTree(listOf(slot("entering", "outer", "inner")))

        assertEquals("entering", branches(tree).single { it.route == "outer" }.ownerSlotKey)
        assertNull(branches(tree).single { it.route == "inner" }.ownerSlotKey)
        assertFalse(leaves(tree).single().ownsTransition)
    }

    @Test
    fun `a screen with no graph layout animates in its own slot`() {
        val tree = buildLayoutTree(listOf(slot("entering")))

        assertTrue(leaves(tree).single().ownsTransition)
        assertEquals(listOf("slot:entering"), pathTo(tree, "entering"))
    }

    @Test
    fun `a layout is ordered by the frontmost screen it holds`() {
        val tree = buildLayoutTree(
            listOf(
                slot("exiting", "wallet", zIndex = 100f),
                slot("entering", "home", zIndex = 3f)
            )
        )

        assertEquals(100f, branches(tree).single { it.route == "wallet" }.zIndex)
        assertEquals(3f, branches(tree).single { it.route == "home" }.zIndex)
    }

    @Test
    fun `the input shield is a sibling of the screen a gesture is revealing`() {
        val tree = buildLayoutTree(
            listOf(
                slot("revealed", "wallet", zIndex = 2f, shielded = true),
                slot("top", "wallet")
            )
        )

        val wallet = branches(tree).single { it.route == "wallet" }
        val shieldIndex = wallet.children.indexOfFirst { it is LayoutTreeShield }
        val revealedIndex = wallet.children.indexOfFirst { it is LayoutTreeLeaf && it.slotKey == "revealed" }
        assertTrue(shieldIndex > revealedIndex, "children: ${wallet.children.map { it.key }}")
        assertTrue(tree.none { it is LayoutTreeShield }, "the shield must not cover the shared chrome")
    }

    @Test
    fun `a sheet graph holds the affordance for every step taken inside it`() {
        val anchor = IndicatorAnchor.Layout("checkout")
        val tree = buildLayoutTree(
            listOf(
                slot("exiting", "checkout", zIndex = 2f, indicatorAnchor = anchor),
                slot("entering", "checkout", indicatorAnchor = anchor)
            )
        )

        val checkout = branches(tree).single { it.route == "checkout" }
        assertEquals(
            "entering",
            checkout.indicatorSlotKey,
            "the affordance belongs to the sheet, above its chrome, and to the step in front of it"
        )
        assertTrue(
            leaves(tree).none { it.ownsIndicator },
            "a second strip inside the chrome would push the step being left down as it animates"
        )
    }

    @Test
    fun `a screen dragged on its own carries the strip under chrome that stays`() {
        val tree = buildLayoutTree(
            listOf(
                slot("beneath", "chrome-home", zIndex = 2f),
                slot("entering", "chrome-home", indicatorAnchor = IndicatorAnchor.OwnContent)
            )
        )

        assertNull(
            branches(tree).single { it.route == "chrome-home" }.indicatorSlotKey,
            "this chrome is not going anywhere, so it holds no affordance and is not offset"
        )
        assertEquals(
            listOf("entering"),
            leaves(tree).filter { it.ownsIndicator }.map { it.slotKey }
        )
    }

    @Test
    fun `only the layout the surface anchors at holds the affordance`() {
        val tree = buildLayoutTree(
            listOf(slot("entering", "outer", "inner", indicatorAnchor = IndicatorAnchor.Layout("inner")))
        )

        assertNull(
            branches(tree).single { it.route == "outer" }.indicatorSlotKey,
            "chrome outside the surface stays behind, so the affordance is not hoisted above it"
        )
        assertEquals("entering", branches(tree).single { it.route == "inner" }.indicatorSlotKey)
        assertFalse(leaves(tree).single().ownsIndicator)
    }

    @Test
    fun `a screen standing under no layout carries the strip itself`() {
        val tree = buildLayoutTree(listOf(slot("entering", indicatorAnchor = IndicatorAnchor.OwnContent)))

        assertTrue(leaves(tree).single().ownsIndicator)
    }

    @Test
    fun `a surface that cannot be dragged away reserves nothing anywhere`() {
        val tree = buildLayoutTree(listOf(slot("entering", "chrome-home")))

        assertNull(branches(tree).single().indicatorSlotKey)
        assertFalse(leaves(tree).single().ownsIndicator)
    }

    @Test
    fun `a surface begins at the outermost layout the screens beside it do not stand under`() {
        assertEquals(
            "overlay",
            outermostUnsharedLayout(listOf("overlay"), listOf(listOf("home"))),
            "the overlay chrome arrived with the screen, so the screen animates together with it"
        )
        assertNull(
            outermostUnsharedLayout(listOf("wallet", "royalty"), listOf(listOf("wallet", "royalty")))
        )
        assertEquals(
            "royalty",
            outermostUnsharedLayout(listOf("wallet", "royalty"), listOf(listOf("wallet")))
        )
        assertEquals(
            "wallet",
            outermostUnsharedLayout(listOf("wallet"), emptyList()),
            "with nothing to measure against, none of the chrome is held up by anything else"
        )
    }

    @Test
    fun `chrome is shared as far down as the two screens agree`() {
        val screen = listOf("wallet", "royalty")

        assertEquals(
            2,
            sharedLayoutDepth(screen, listOf(listOf("wallet", "royalty"), listOf("wallet"))),
            "the deepest agreement wins, so chrome another screen is standing under is shared"
        )
        assertEquals(0, sharedLayoutDepth(screen, listOf(emptyList())))
        assertEquals(0, sharedLayoutDepth(screen, emptyList()))
        assertEquals(
            outermostUnsharedLayout(screen, listOf(listOf("wallet"))),
            screen.getOrNull(sharedLayoutDepth(screen, listOf(listOf("wallet")))),
            "a surface begins exactly where its agreement with the screens beside it ends"
        )
    }

    private fun decision(
        enter: NavTransition = NavTransition.SlideInRight,
        exit: NavTransition = NavTransition.SlideOutLeft,
        animateExit: Boolean = true
    ) = AnimationDecision(
        shouldAnimateEnter = true,
        shouldAnimateExit = animateExit,
        isForward = true,
        enterTransition = enter,
        exitTransition = exit
    )

    @Test
    fun `changed layouts with an animating exit lift the exiting screen clear`() {
        val placement = decideTransitionPlacement(
            currentLayoutRoutes = listOf("wallet"),
            previousLayoutRoutes = listOf("home"),
            decision = decision()
        )

        assertEquals(NavigationZIndex.CONTENT_LIFTED_EXIT, placement.previousZIndex)
        assertEquals(NavigationZIndex.CONTENT_FRONT, placement.currentZIndex)
        assertTrue(
            placement.currentPlaysTransition,
            "the screens stand under different chrome, so the arriving one has its own entrance"
        )
    }

    @Test
    fun `changed layouts without an animating exit do not lift`() {
        val placement = decideTransitionPlacement(
            currentLayoutRoutes = listOf("wallet"),
            previousLayoutRoutes = listOf("home"),
            decision = decision(animateExit = false)
        )

        assertEquals(NavigationZIndex.CONTENT_BACK, placement.previousZIndex)
        assertEquals(NavigationZIndex.CONTENT_FRONT, placement.currentZIndex)
    }

    @Test
    fun `moving inside one graph never lifts the exiting screen`() {
        val placement = decideTransitionPlacement(
            currentLayoutRoutes = listOf("wallet"),
            previousLayoutRoutes = listOf("wallet"),
            decision = decision()
        )

        assertEquals(NavigationZIndex.CONTENT_BACK, placement.previousZIndex)
        assertEquals(NavigationZIndex.CONTENT_FRONT, placement.currentZIndex)
        assertTrue(placement.currentPlaysTransition)
    }

    @Test
    fun `a screen revealed under chrome it shares plays no entrance of its own`() {
        val placement = decideTransitionPlacement(
            currentLayoutRoutes = listOf("wallet", "royalty-analytics"),
            previousLayoutRoutes = listOf("wallet"),
            decision = decision()
        )

        assertEquals(NavigationZIndex.CONTENT_LIFTED_EXIT, placement.previousZIndex)
        assertFalse(
            placement.currentPlaysTransition,
            "the leaving screen is lifted above it inside chrome they share, so the arriving one " +
                "is revealed underneath rather than playing an entrance nobody can see"
        )
    }

    @Test
    fun `a screen that does not animate in lets the leaving one draw over it`() {
        val placement = decideTransitionPlacement(
            currentLayoutRoutes = listOf("wallet"),
            previousLayoutRoutes = listOf("wallet"),
            decision = decision(enter = NavTransition.None, exit = NavTransition.SlideOutLeft)
        )

        assertEquals(NavigationZIndex.CONTENT_BACK, placement.currentZIndex)
        assertEquals(
            NavigationZIndex.CONTENT_FRONT,
            placement.previousZIndex,
            "nothing moves the arriving screen into view, so the leaving one has to move off it"
        )
    }

    @Test
    fun `nothing is lifted when there is no exiting screen`() {
        val placement = decideTransitionPlacement(
            currentLayoutRoutes = listOf("wallet"),
            previousLayoutRoutes = null,
            decision = null
        )

        assertEquals(NavigationZIndex.CONTENT_FRONT, placement.currentZIndex)
        assertEquals(NavigationZIndex.CONTENT_BACK, placement.previousZIndex)
        assertTrue(placement.currentPlaysTransition)
    }
}
