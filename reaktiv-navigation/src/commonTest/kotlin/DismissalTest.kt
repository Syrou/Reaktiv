import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.definition.DismissAction
import io.github.syrou.reaktiv.navigation.definition.DismissSource
import io.github.syrou.reaktiv.navigation.definition.Dismissal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class DismissalTest {

    private val handler: suspend StoreAccessor.() -> Unit = { }

    @Test
    fun legacyDefaultsMapToBackPopTapIgnoreSwipePop() {
        val dismissal = Dismissal.fromLegacy(handler = null, swipeToDismiss = true)
        assertEquals(DismissAction.Pop, dismissal.back)
        assertEquals(DismissAction.Ignore, dismissal.tapOutside)
        assertEquals(DismissAction.Pop, dismissal.swipe)
    }

    @Test
    fun legacySwipeOptOutMapsToSwipeIgnore() {
        val dismissal = Dismissal.fromLegacy(handler = null, swipeToDismiss = false)
        assertEquals(DismissAction.Pop, dismissal.back)
        assertEquals(DismissAction.Ignore, dismissal.swipe)
    }

    @Test
    fun legacyHandlerRunsOnEverySourceThatIsNotOptedOut() {
        val dismissal = Dismissal.fromLegacy(handler = handler, swipeToDismiss = true)
        for (source in DismissSource.entries) {
            val action = assertIs<DismissAction.Run>(dismissal[source])
            assertSame(handler, action.handler)
        }
    }

    @Test
    fun legacyHandlerWithSwipeOptOutKeepsSwipeIgnored() {
        val dismissal = Dismissal.fromLegacy(handler = handler, swipeToDismiss = false)
        assertIs<DismissAction.Run>(dismissal.back)
        assertIs<DismissAction.Run>(dismissal.tapOutside)
        assertEquals(DismissAction.Ignore, dismissal.swipe)
    }

    @Test
    fun presetsAreWhatTheyClaim() {
        assertEquals(Dismissal(), Dismissal.Default)
        for (source in DismissSource.entries) {
            assertEquals(DismissAction.Ignore, Dismissal.Blocking[source])
            assertEquals(DismissAction.Pop, Dismissal.Dismissable[source])
        }
    }
}
