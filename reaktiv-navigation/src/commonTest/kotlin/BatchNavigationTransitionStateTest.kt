@file:OptIn(ExperimentalCoroutinesApi::class)

import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.alias.TitleResource
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.extension.startGuidedFlow
import io.github.syrou.reaktiv.navigation.model.NavigationTransitionState
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class BatchNavigationTransitionStateTest {

    @BeforeTest
    fun setup() {
        ReaktivDebug.enable()
    }

    // Test screens with specific transition durations
    object TestHomeScreen : Screen {
        override val route = "test-home"
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom
        override val requiresAuth = false
        override val titleResource: TitleResource = { "Home" }

        @Composable
        override fun Content(params: Params) {
        }
    }

    object TestProfileScreen : Screen {
        override val route = "test-profile"
        override val enterTransition = NavTransition.SlideInRight
        override val exitTransition = NavTransition.SlideOutRight
        override val requiresAuth = false
        override val titleResource: TitleResource = { "Profile" }

        @Composable
        override fun Content(params: Params) {
        }
    }

    object TestSettingsScreen : Screen {
        override val route = "test-settings"
        override val enterTransition = NavTransition.SlideInLeft
        override val exitTransition = NavTransition.SlideOutLeft
        override val requiresAuth = false
        override val titleResource: TitleResource = { "Settings" }

        @Composable
        override fun Content(params: Params) {
        }
    }

    private fun createTestNavigationModule() = createNavigationModule {
        rootGraph {
            startScreen(TestHomeScreen)
            screens(TestHomeScreen, TestProfileScreen, TestSettingsScreen)
        }

        guidedFlow("onboarding") {
            step<TestHomeScreen>()
            step<TestProfileScreen>()
            step<TestSettingsScreen>()
            onComplete { storeAccessor ->
                navigateTo(TestHomeScreen.route)
                clearBackStack()
            }
        }
    }

    @Test
    fun `batch guided flow modification with navigation should properly reset transition state`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createTestNavigationModule())
                coroutineContext(testDispatcher)
            }

            // Start guided flow
            store.startGuidedFlow("onboarding")
            advanceUntilIdle()

            val initialState = store.selectState<NavigationState>().first()
            assertEquals(NavigationTransitionState.IDLE, initialState.transitionState)
            assertEquals(TestHomeScreen.route, initialState.currentEntry.navigatable.route)

            // Perform batch operation: modify guided flow AND navigate
            store.navigation {
                guidedFlow("onboarding") {
                    // Add some modifications to make this a complex batch operation
                    updateStepParams(0, Params.of("modified" to "true"))
                    removeSteps(listOf(2)) // Remove settings screen from flow
                    nextStep() // This should navigate to TestProfileScreen
                }
            }

            // Immediately check that transition state is set to ANIMATING
            advanceTimeBy(10)
            val duringTransitionState = store.selectState<NavigationState>().first()
            assertEquals(
                NavigationTransitionState.ANIMATING,
                duringTransitionState.transitionState,
                "Transition state should be ANIMATING immediately after batch navigation"
            )
            assertEquals(
                TestProfileScreen.route,
                duringTransitionState.currentEntry.navigatable.route,
                "Should have navigated to TestProfileScreen"
            )

            // Wait for the animation to complete
            // All transitions use the default duration
            advanceTimeBy(NavTransition.DEFAULT_ANIMATION_DURATION.toLong())
            advanceUntilIdle()

            // Verify transition state properly resets to IDLE
            val finalState = store.selectState<NavigationState>().first()
            assertEquals(
                NavigationTransitionState.IDLE,
                finalState.transitionState,
                "Transition state should reset to IDLE after animation duration"
            )
            assertEquals(
                TestProfileScreen.route,
                finalState.currentEntry.navigatable.route,
                "Should still be on TestProfileScreen"
            )

            // Verify guided flow state is correct
            val guidedFlowState = finalState.activeGuidedFlowState
            assertEquals(1, guidedFlowState?.currentStepIndex)
            assertEquals("onboarding", guidedFlowState?.flowRoute)
        }

    @Test
    fun `transition state reset timing should be based on correct previous entry in batch operations`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createTestNavigationModule())
                coroutineContext(testDispatcher)
            }

            // Start guided flow at home screen
            store.startGuidedFlow("onboarding")
            advanceUntilIdle()

            // Do a series of batch operations to test timing
            store.navigation {
                guidedFlow("onboarding") {
                    nextStep() // Home -> Profile
                }
            }
            advanceTimeBy(10)

            val afterFirstNav = store.selectState<NavigationState>().first()
            assertEquals(NavigationTransitionState.ANIMATING, afterFirstNav.transitionState)

            // Wait for first animation to complete
            advanceTimeBy(NavTransition.DEFAULT_ANIMATION_DURATION.toLong())
            advanceUntilIdle()

            val afterFirstReset = store.selectState<NavigationState>().first()
            assertEquals(NavigationTransitionState.IDLE, afterFirstReset.transitionState)

            // Now do another batch operation
            store.navigation {
                guidedFlow("onboarding") {
                    updateStepParams(1, Params.of("test" to "value"))
                    nextStep() // Profile -> Settings
                }
            }
            advanceTimeBy(10)

            val afterSecondNav = store.selectState<NavigationState>().first()
            assertEquals(NavigationTransitionState.ANIMATING, afterSecondNav.transitionState)
            assertEquals(TestSettingsScreen.route, afterSecondNav.currentEntry.navigatable.route)

            // Wait for second animation
            advanceTimeBy(NavTransition.DEFAULT_ANIMATION_DURATION.toLong())
            advanceUntilIdle()

            val afterSecondReset = store.selectState<NavigationState>().first()
            assertEquals(
                NavigationTransitionState.IDLE,
                afterSecondReset.transitionState,
                "Second transition should also properly reset to IDLE"
            )
        }

    @Test
    fun `multiple rapid batch operations should not interfere with transition state resets`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(createTestNavigationModule())
                coroutineContext(testDispatcher)
            }

            store.startGuidedFlow("onboarding")
            advanceUntilIdle()

            // Execute multiple batch operations rapidly
            store.navigation {
                guidedFlow("onboarding") {
                    updateStepParams(0, Params.of("batch1" to "true"))
                    nextStep() // Home -> Profile
                }
            }
            advanceUntilIdle()
            // Don't wait for animation to complete, immediately do another operation
            store.navigation {
                guidedFlow("onboarding") {
                    updateStepParams(1, Params.of("batch2" to "true"))
                    nextStep() // Profile -> Settings
                }
            }

            advanceTimeBy(10)

            // Should be on final screen with ANIMATING state
            val rapidState = store.selectState<NavigationState>().first()
            assertEquals(
                NavigationTransitionState.ANIMATING,
                rapidState.transitionState,
                "Should be in ANIMATING state after rapid operations"
            )
            assertEquals(TestSettingsScreen.route, rapidState.currentEntry.navigatable.route)

            // Wait for animations to complete and verify final reset
            advanceUntilIdle()

            val finalState = store.selectState<NavigationState>().first()
            assertEquals(
                NavigationTransitionState.IDLE,
                finalState.transitionState,
                "Should properly reset to IDLE even after rapid batch operations"
            )
        }
}