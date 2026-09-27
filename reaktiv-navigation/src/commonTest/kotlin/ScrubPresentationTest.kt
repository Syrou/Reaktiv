import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.ScrubState
import io.github.syrou.reaktiv.navigation.ScrubType
import io.github.syrou.reaktiv.navigation.TraversePresentation
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class ScrubPresentationTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.Custom(durationMillis = 300)
        override val exitTransition = NavTransition.Custom(durationMillis = 300)

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private val sheet = object : Modal {
        override val route = "sheet"
        override val enterTransition = NavTransition.Custom(durationMillis = 300)
        override val exitTransition = NavTransition.Custom(durationMillis = 300)

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private val home = screen("home")
    private val detail = screen("detail")
    private val other = screen("other")
    private val step = screen("step")

    private val loading = object : LoadingModal {
        override val route = "loading"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private fun TestScope.store(): Store = createStore {
        module(createNavigationModule {
            rootGraph {
                start(home)
                screens(home, detail, other)
                modals(sheet)
                graph("flow") {
                    start(step)
                    screens(step)
                }
            }
        })
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    private suspend fun Store.nav(): NavigationState = selectState<NavigationState>().first()

    private suspend fun Store.scrubToHome(type: ScrubType = ScrubType.Back) {
        val top = nav().currentEntry
        val revealed = nav().backStack.first()
        dispatch(NavigationAction.ScrubUpdate(ScrubState(type, top.stableKey, revealed.stableKey, 0.9f)))
    }

    @Test
    fun aBackThatLandsWhatTheScrubRevealedIsAlreadyPresented() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()
            store.navigation { navigateTo("detail") }
            advanceUntilIdle()

            store.scrubToHome()
            advanceUntilIdle()
            store.dispatch(NavigationAction.Back())
            advanceUntilIdle()

            val state = store.nav()
            assertEquals("home", state.currentEntry.route)
            assertEquals(TraversePresentation.AlreadyPresented, assertIs<NavigationAction.Back>(state.lastNavigationAction).presentation)
            assertNull(state.activeScrub)
        }

    @Test
    fun aBackWithoutAScrubAnimates() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()
            store.navigation { navigateTo("detail") }
            advanceUntilIdle()

            store.dispatch(NavigationAction.Back())
            advanceUntilIdle()

            assertEquals(TraversePresentation.Animate, assertIs<NavigationAction.Back>(store.nav().lastNavigationAction).presentation)
        }

    @Test
    fun aBackAfterTheScrubWasCancelledAnimates() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()
            store.navigation { navigateTo("detail") }
            advanceUntilIdle()

            store.scrubToHome()
            store.dispatch(NavigationAction.ScrubEnd)
            store.dispatch(NavigationAction.Back())
            advanceUntilIdle()

            assertEquals(TraversePresentation.Animate, assertIs<NavigationAction.Back>(store.nav().lastNavigationAction).presentation)
        }

    @Test
    fun aNavigationElsewhereDuringAScrubEndsItWithoutMarkingTheNextBack() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()
            store.navigation { navigateTo("detail") }
            advanceUntilIdle()

            store.scrubToHome()
            advanceUntilIdle()
            store.navigation { navigateTo("other") }
            advanceUntilIdle()
            assertNull(store.nav().activeScrub)

            store.dispatch(NavigationAction.Back())
            advanceUntilIdle()

            assertEquals(TraversePresentation.Animate, assertIs<NavigationAction.Back>(store.nav().lastNavigationAction).presentation)
        }

    @Test
    fun aModalDismissedByItsScrubIsAlreadyPresented() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()
            store.navigation { navigateTo("sheet") }
            advanceUntilIdle()

            val modal = store.nav().currentEntry
            store.dispatch(NavigationAction.ScrubUpdate(ScrubState(ScrubType.ModalDismiss, modal.stableKey, null, 0.9f)))
            store.dispatch(NavigationAction.Back())
            advanceUntilIdle()

            assertEquals("home", store.nav().currentEntry.route)
            assertEquals(TraversePresentation.AlreadyPresented, assertIs<NavigationAction.Back>(store.nav().lastNavigationAction).presentation)
        }

    @Test
    fun aNavigationThatLandedUnderTheLoadingOverlayDoesNotWaitForAnAnimation() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(createNavigationModule {
                    loadingModal(loading)
                    rootGraph {
                        start(home)
                        screens(home)
                        intercept(
                            guard = {
                                delay(500)
                                GuardResult.Allow
                            },
                            loadingThreshold = 100.milliseconds
                        ) {
                            graph("vault") {
                                start(detail)
                                screens(detail)
                            }
                        }
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            val job = launch { store.navigation { navigateTo("vault") } }
            testScheduler.advanceTimeBy(501)
            testScheduler.runCurrent()

            assertEquals("detail", store.nav().currentEntry.route)
            assertTrue(job.isCompleted)
        }

    @Test
    fun leavingAGraphWithASwipeDoesNotWaitForTheAnimationTheSwipePlayed() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            advanceUntilIdle()
            store.navigation { navigateTo("flow") }
            advanceUntilIdle()

            store.scrubToHome()
            val swipe = launch { store.navigation { popUpTo("home", inclusive = false) } }
            testScheduler.runCurrent()

            assertEquals("home", store.nav().currentEntry.route)
            assertEquals(TraversePresentation.AlreadyPresented, assertIs<NavigationAction.PopUpTo>(store.nav().lastNavigationAction).presentation)
            assertTrue(swipe.isCompleted)
        }
}
