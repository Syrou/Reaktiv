import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.transition.computeBackGesturePlan
import io.github.syrou.reaktiv.navigation.transition.computeDismissGesturePlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackGestureTransformPlanTest {

    private val width = 1000f
    private val height = 2000f

    private fun screen(
        route: String,
        enter: NavTransition = NavTransition.None,
        exit: NavTransition = NavTransition.None,
        popEnter: NavTransition? = null,
        popExit: NavTransition? = null
    ) = object : Screen {
        override val route = route
        override val enterTransition = enter
        override val exitTransition = exit
        override val popEnterTransition = popEnter
        override val popExitTransition = popExit

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    @Test
    fun `ios pair reverses the push for both screens`() {
        val top = screen("detail", enter = NavTransition.IOSSlideIn, exit = NavTransition.IOSSlideOut)
        val revealed = screen("home", enter = NavTransition.IOSSlideIn, exit = NavTransition.IOSSlideOut)

        val plan = computeBackGesturePlan(top, revealed, width, height)

        assertTrue(plan.top.reversedProgress)
        assertEquals(300f, plan.top.resolved.translationX(0.7f), absoluteTolerance = 0.001f)
        assertTrue(plan.revealed.reversedProgress)
        assertEquals(-300f, plan.revealed.resolved.translationX(1f), absoluteTolerance = 0.001f)
        assertEquals(0.95f, plan.revealed.resolved.scaleX(1f), absoluteTolerance = 0.001f)
        assertEquals(0f, plan.revealed.resolved.translationX(0f), absoluteTolerance = 0.001f)
        assertEquals(1f, plan.revealed.resolved.scaleX(0f), absoluteTolerance = 0.001f)
    }

    @Test
    fun `explicit pop transitions win over reversing the push`() {
        val top = screen(
            "detail",
            enter = NavTransition.IOSSlideIn,
            exit = NavTransition.IOSSlideOut,
            popEnter = NavTransition.Fade,
            popExit = NavTransition.SlideOutRight
        )
        val revealed = screen("home", enter = NavTransition.IOSSlideIn, exit = NavTransition.IOSSlideOut)

        val plan = computeBackGesturePlan(top, revealed, width, height)

        assertFalse(plan.top.reversedProgress)
        assertEquals(-500f, plan.top.resolved.translationX(0.5f), absoluteTolerance = 0.001f)
        assertFalse(plan.revealed.reversedProgress)
        assertEquals(0.5f, plan.revealed.resolved.alpha(0.5f), absoluteTolerance = 0.001f)
    }

    @Test
    fun `transitionless screens fall back to the ios pair`() {
        val top = screen("detail")
        val revealed = screen("home")

        val plan = computeBackGesturePlan(top, revealed, width, height)

        assertTrue(plan.top.reversedProgress)
        assertEquals(500f, plan.top.resolved.translationX(0.5f), absoluteTolerance = 0.001f)
        assertTrue(plan.revealed.reversedProgress)
        assertEquals(-150f, plan.revealed.resolved.translationX(0.5f), absoluteTolerance = 0.001f)
    }

    @Test
    fun `dismiss plan scrubs exit transition and recedes revealed back to identity`() {
        val top = screen("sheet", enter = NavTransition.SlideUpBottom, exit = NavTransition.SlideOutBottom)
        val revealed = screen("underlying")

        val plan = computeDismissGesturePlan(top, revealed, width, height)

        assertFalse(plan.top.reversedProgress)
        assertEquals(1000f, plan.top.resolved.translationY(0.5f), absoluteTolerance = 0.001f)
        assertTrue(plan.revealed.reversedProgress)
        assertEquals(0.94f, plan.revealed.resolved.scaleX(1f), absoluteTolerance = 0.001f)
        assertEquals(0.94f, plan.revealed.resolved.scaleY(1f), absoluteTolerance = 0.001f)
        assertEquals(1f, plan.revealed.resolved.scaleX(0f), absoluteTolerance = 0.001f)
        assertEquals(0f, plan.revealed.resolved.translationY(0.9f), absoluteTolerance = 0.001f)
        assertEquals(1f, plan.revealed.resolved.alpha(0.9f), absoluteTolerance = 0.001f)
    }

    @Test
    fun `dismiss plan falls back to slide out bottom`() {
        val top = screen("sheet")

        val plan = computeDismissGesturePlan(top, null, width, height)

        assertEquals(1000f, plan.top.resolved.translationY(0.5f), absoluteTolerance = 0.001f)
        assertEquals(2000f, plan.top.resolved.translationY(1f), absoluteTolerance = 0.001f)
    }

    @Test
    fun `dismiss plan reverses the revealed screens own exit when it has one`() {
        val top = screen("sheet", enter = NavTransition.SlideUpBottom)
        val revealed = screen("underlying", exit = NavTransition.ScaleOut())

        val plan = computeDismissGesturePlan(top, revealed, width, height)

        assertTrue(plan.revealed.reversedProgress)
        assertEquals(0.8f, plan.revealed.resolved.scaleX(1f), absoluteTolerance = 0.001f)
        assertEquals(1f, plan.revealed.resolved.scaleX(0f), absoluteTolerance = 0.001f)
    }

    @Test
    fun `dismiss plan honours explicit pop enter for the revealed screen`() {
        val top = screen("sheet", enter = NavTransition.SlideUpBottom, popEnter = NavTransition.Fade)
        val revealed = screen("underlying")

        val plan = computeDismissGesturePlan(top, revealed, width, height)

        assertFalse(plan.revealed.reversedProgress)
        assertEquals(0.5f, plan.revealed.resolved.alpha(0.5f), absoluteTolerance = 0.001f)
    }

    @Test
    fun `swipe to dismiss defaults follow the presentation axis`() {
        assertTrue(screen("sheet", enter = NavTransition.SlideUpBottom).swipeToDismiss)
        assertTrue(screen("stack", enter = NavTransition.StackPush).swipeToDismiss)
        assertFalse(screen("pushed", enter = NavTransition.IOSSlideIn).swipeToDismiss)
        assertFalse(screen("faded", enter = NavTransition.Fade).swipeToDismiss)
        assertFalse(screen("plain").swipeToDismiss)
    }
}
