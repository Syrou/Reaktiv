import io.github.syrou.reaktiv.navigation.definition.Graph
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphPresentationTest {

    private object SheetGraph : Graph {
        override val route = "wizard"
        override val enterTransition = NavTransition.SlideUpBottom
        override val popExitTransition = NavTransition.SlideOutBottom
    }

    private object SideGraph : Graph {
        override val route = "settings"
        override val enterTransition = NavTransition.SlideInRight
    }

    private object StructuralGraph : Graph {
        override val route = "home"
    }

    private object MandatoryGraph : Graph {
        override val route = "onboarding"
        override val enterTransition = NavTransition.SlideUpBottom
        override val swipeToDismiss = false
    }

    @Test
    fun `a vertical presentation arms the drag`() {
        assertTrue(SheetGraph.swipeToDismiss)
        assertTrue(SheetGraph.showsDismissIndicator)
    }

    @Test
    fun `a horizontal presentation does not`() {
        assertFalse(SideGraph.swipeToDismiss)
        assertFalse(SideGraph.showsDismissIndicator)
    }

    @Test
    fun `a graph that declares nothing has no presentation and no drag`() {
        assertEquals(null, StructuralGraph.enterTransition)
        assertEquals(null, StructuralGraph.exitTransition)
        assertFalse(StructuralGraph.swipeToDismiss)
        assertFalse(
            StructuralGraph.showsDismissIndicator,
            "a structural graph must not promise a handle it cannot honour"
        )
    }

    @Test
    fun `a vertical flow can opt out of being dismissable`() {
        assertFalse(MandatoryGraph.swipeToDismiss)
        assertFalse(MandatoryGraph.showsDismissIndicator)
    }

    @Test
    fun `null is distinct from None so no opinion defers to the screen`() {
        assertEquals(null, StructuralGraph.enterTransition)
        assertEquals(NavTransition.SlideUpBottom, SheetGraph.enterTransition)
        assertEquals(null, SheetGraph.exitTransition)
        assertEquals(NavTransition.SlideOutBottom, SheetGraph.popExitTransition)
    }
}
