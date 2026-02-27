import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.util.determineAnimationDecision
import io.github.syrou.reaktiv.navigation.util.determineContentAnimationDecision
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Tests for [determineAnimationDecision] and [determineContentAnimationDecision].
 *
 * Both are pure functions — no Compose needed.
 *
 * Also verifies the zIndex selection formula:
 *
 *   shouldExitBeOnTop = enterTransition == None && exitTransition != None
 *
 * which drives whether the entering or exiting screen renders on top during a
 * transition. When true the exiting screen appears in front (e.g., a pop
 * animation where the outgoing screen slides away over the destination).
 *
 * Layer ordering (lowest → highest z-index):
 *   CONTENT (2–3)  <  GLOBAL_OVERLAY / modals (2000+)  <  SYSTEM (9001+)
 * Modals within GLOBAL_OVERLAY are sorted ascending by [Navigatable.elevation].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnimationDecisionTest {

    // --- Screen / modal helpers ---

    private fun screen(
        route: String,
        enterTransition: NavTransition = NavTransition.None,
        exitTransition: NavTransition = NavTransition.None
    ) = object : Screen {
        override val route = route
        override val enterTransition = enterTransition
        override val exitTransition = exitTransition
        override val requiresAuth = false

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private fun modal(
        route: String,
        customElevation: Float = 1000f
    ) = object : Modal {
        override val route = route
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom
        override val requiresAuth = false
        override val elevation = customElevation

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private fun entry(route: String, stackPosition: Int) =
        NavigationEntry(path = route, params = Params.empty(), stackPosition = stackPosition)

    // Boots a store with standard screens/modals and calls block with the initialised NavigationModule.
    // TestScope receiver gives access to advanceUntilIdle() and testScheduler.
    private suspend fun TestScope.navModule(
        block: suspend (NavigationModule) -> Unit
    ) {
        val homeScreen        = screen("home")
        val profileScreen     = screen("profile", NavTransition.SlideInRight, NavTransition.SlideOutLeft)
        val settingsScreen    = screen("settings", NavTransition.Fade)
        val notificationModal = modal("notification", customElevation = 1000f)
        val alertModal        = modal("alert",        customElevation = 2000f)

        val store = createStore {
            module(createNavigationModule {
                rootGraph {
                    startScreen(homeScreen)
                    screens(homeScreen, profileScreen, settingsScreen)
                    modals(notificationModal, alertModal)
                }
            })
            coroutineContext(StandardTestDispatcher(testScheduler))
        }
        advanceUntilIdle()
        val nm = store.getModule<NavigationModule>()
        assertNotNull(nm)
        block(nm!!)
    }

    // --- determineAnimationDecision ---

    @Test
    fun `forward navigation isForward true and uses current screen enter transition`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            navModule { nm ->
                val homeEntry    = entry("home",    stackPosition = 1)
                val profileEntry = entry("profile", stackPosition = 2)

                val decision = determineAnimationDecision(homeEntry, profileEntry, nm)

                assertTrue(decision.isForward)
                assertEquals(NavTransition.SlideInRight, decision.enterTransition)
                assertTrue(decision.shouldAnimateEnter)
            }
        }

    @Test
    fun `forward navigation uses previous screen exit transition`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            navModule { nm ->
                val homeEntry    = entry("home",    stackPosition = 1)
                val profileEntry = entry("profile", stackPosition = 2)

                val decision = determineAnimationDecision(homeEntry, profileEntry, nm)

                // home has exitTransition=None so exit should not animate
                assertFalse(decision.shouldAnimateExit)
                assertEquals(NavTransition.None, decision.exitTransition)
            }
        }

    @Test
    fun `back navigation isForward false`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            navModule { nm ->
                val homeEntry    = entry("home",    stackPosition = 1)
                val profileEntry = entry("profile", stackPosition = 2)

                // previous=profile (was current), current=home (returning to)
                val decision = determineAnimationDecision(profileEntry, homeEntry, nm)

                assertFalse(decision.isForward)
            }
        }

    @Test
    fun `back navigation uses previous screen exit transition`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            navModule { nm ->
                val homeEntry    = entry("home",    stackPosition = 1)
                val profileEntry = entry("profile", stackPosition = 2)

                val decision = determineAnimationDecision(profileEntry, homeEntry, nm)

                // profile.exitTransition = SlideOutLeft
                assertEquals(NavTransition.SlideOutLeft, decision.exitTransition)
                assertTrue(decision.shouldAnimateExit)
            }
        }

    @Test
    fun `no animation when both transitions are None`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            navModule { nm ->
                // Two home entries at different positions — home has None for both transitions
                val e1 = entry("home", stackPosition = 1)
                val e2 = entry("home", stackPosition = 2)

                val decision = determineAnimationDecision(e1, e2, nm)

                assertFalse(decision.shouldAnimateEnter)
                assertFalse(decision.shouldAnimateExit)
            }
        }

    @Test
    fun `same entry produces no animation`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            navModule { nm ->
                val homeEntry = entry("home", stackPosition = 1)

                val decision = determineAnimationDecision(homeEntry, homeEntry, nm)

                assertFalse(decision.shouldAnimateEnter)
                assertFalse(decision.shouldAnimateExit)
            }
        }

    @Test
    fun `both entries at stackPosition 0 produce no animation`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            navModule { nm ->
                val e1 = entry("home",    stackPosition = 0)
                val e2 = entry("profile", stackPosition = 0)

                val decision = determineAnimationDecision(e1, e2, nm)

                assertFalse(decision.shouldAnimateEnter)
                assertFalse(decision.shouldAnimateExit)
            }
        }

    // --- determineContentAnimationDecision ---

    @Test
    fun `content decision skips animation when current entry is modal layer`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            navModule { nm ->
                val homeEntry         = entry("home",         stackPosition = 1)
                val notificationEntry = entry("notification", stackPosition = 2)

                // notification is Modal (GLOBAL_OVERLAY) → content layer should not animate
                val decision = determineContentAnimationDecision(homeEntry, notificationEntry, nm)

                assertFalse(decision.shouldAnimateEnter)
                assertFalse(decision.shouldAnimateExit)
            }
        }

    @Test
    fun `content decision skips animation when previous entry is modal layer`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            navModule { nm ->
                val notificationEntry = entry("notification", stackPosition = 2)
                val homeEntry         = entry("home",         stackPosition = 1)

                val decision = determineContentAnimationDecision(notificationEntry, homeEntry, nm)

                assertFalse(decision.shouldAnimateEnter)
                assertFalse(decision.shouldAnimateExit)
            }
        }

    @Test
    fun `content decision permits animation when both entries are screens`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            navModule { nm ->
                val homeEntry    = entry("home",    stackPosition = 1)
                val profileEntry = entry("profile", stackPosition = 2)

                val decision = determineContentAnimationDecision(homeEntry, profileEntry, nm)

                // profile has SlideInRight → animation should proceed
                assertTrue(decision.shouldAnimateEnter)
                assertTrue(decision.isForward)
            }
        }

    // --- zIndex selection formula ---

    @Test
    fun `forward navigation with active enter transition entering screen should be on top`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            navModule { nm ->
                val homeEntry    = entry("home",    stackPosition = 1)
                val profileEntry = entry("profile", stackPosition = 2)

                val decision = determineAnimationDecision(homeEntry, profileEntry, nm)

                // profile enters with SlideInRight (not None) → shouldExitBeOnTop = false
                // → entering screen (current) is rendered at CONTENT_FRONT above exiting
                val shouldExitBeOnTop = decision.enterTransition is NavTransition.None &&
                        decision.exitTransition !is NavTransition.None
                assertFalse(shouldExitBeOnTop, "Entering screen must be on top when enter transition is active")
            }
        }

    @Test
    fun `exit-only transition exiting screen should be on top`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            // For forward navigation (home → dest):
            //   enterTransition = dest.enterTransition              = None
            //   exitTransition  = dest.popExitTransition ?: home.exitTransition = SlideOutLeft
            // → shouldExitBeOnTop = (enter==None && exit!=None) = true
            // → home (exiting) renders in front so it can slide out over the destination
            val homeScreen = screen("home", exitTransition = NavTransition.SlideOutLeft)
            val destScreen = screen("dest", enterTransition = NavTransition.None)

            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        startScreen(homeScreen)
                        screens(homeScreen, destScreen)
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            val nm = store.getModule<NavigationModule>()!!

            val homeEntry = entry("home", stackPosition = 1)
            val destEntry = entry("dest", stackPosition = 2)

            val decision = determineAnimationDecision(homeEntry, destEntry, nm)
            val shouldExitBeOnTop = decision.enterTransition is NavTransition.None &&
                    decision.exitTransition !is NavTransition.None

            assertTrue(
                shouldExitBeOnTop,
                "Exit-only: exiting screen must render on top when entering screen has no enter transition"
            )
            assertEquals(NavTransition.None,         decision.enterTransition)
            assertEquals(NavTransition.SlideOutLeft, decision.exitTransition)
        }

    // --- Layer and elevation ordering ---

    @Test
    fun `modal renderLayer is GLOBAL_OVERLAY`() {
        assertEquals(RenderLayer.GLOBAL_OVERLAY, modal("m").renderLayer)
    }

    @Test
    fun `screen renderLayer is CONTENT`() {
        assertEquals(RenderLayer.CONTENT, screen("s").renderLayer)
    }

    @Test
    fun `higher elevation modal sorts after lower elevation modal`() {
        val low  = modal("notification", customElevation = 1000f)
        val high = modal("alert",        customElevation = 2000f)

        val sorted = listOf(high, low).sortedBy { it.elevation }
        assertEquals("notification", sorted[0].route)
        assertEquals("alert",        sorted[1].route)
    }

    @Test
    fun `modal default elevation is above screen default elevation`() {
        assertTrue(modal("m").elevation > screen("s").elevation)
    }

    @Test
    fun `settings screen with fade enter should animate enter but not exit`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            navModule { nm ->
                val homeEntry     = entry("home",     stackPosition = 1)
                val settingsEntry = entry("settings", stackPosition = 2)

                val decision = determineAnimationDecision(homeEntry, settingsEntry, nm)

                // settings has Fade enter, home has None exit
                assertTrue(decision.shouldAnimateEnter, "Fade enter should animate")
                assertFalse(decision.shouldAnimateExit, "home has None exit — should not animate exit")
            }
        }

    // --- Integration: verify decision from a real navigated store ---

    @Test
    fun `forward and backward decisions from real store entries have correct directions`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val homeScreen    = screen("home")
            val profileScreen = screen("profile",
                enterTransition = NavTransition.SlideInRight,
                exitTransition  = NavTransition.SlideOutLeft
            )

            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        startScreen(homeScreen)
                        screens(homeScreen, profileScreen)
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.navigation { navigateTo("profile") }
            advanceUntilIdle()

            val nm = store.getModule<NavigationModule>()!!

            val homeEntry    = entry("home",    stackPosition = 1)
            val profileEntry = entry("profile", stackPosition = 2)

            val fwdDecision = determineAnimationDecision(homeEntry, profileEntry, nm)
            assertTrue(fwdDecision.isForward)
            assertEquals(NavTransition.SlideInRight, fwdDecision.enterTransition)
            assertTrue(fwdDecision.shouldAnimateEnter)

            val bwdDecision = determineAnimationDecision(profileEntry, homeEntry, nm)
            assertFalse(bwdDecision.isForward)
            assertEquals(NavTransition.SlideOutLeft, bwdDecision.exitTransition)
            assertTrue(bwdDecision.shouldAnimateExit)
        }
}
