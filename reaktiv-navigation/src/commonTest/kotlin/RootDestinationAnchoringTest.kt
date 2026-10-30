import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Graph
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.NavigationPath
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class RootDestinationAnchoringTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private fun modal(route: String) = object : Modal {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private val loadingScreen = object : LoadingModal {
        override val route = "loading"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text("loading") }
    }

    private object HomeGraph : Graph {
        override val route = "home"
    }

    private object PeerHostGraph : Graph {
        override val route = "home"

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override val startAnchorsChildren = false
    }

    private val signUp = screen("sign-up")
    private val dashboard = screen("home-dashboard")
    private val notificationManager = screen("notification-manager")
    private val guardedSection = screen("guarded-section")
    private val newRelease = modal("home-new-release")
    private val invitation = modal("invitation/{type}")
    private val catalogueScreen = screen("home-catalogue-screen")
    private val releaseInfo = screen("home-release-info")
    private val releaseShare = modal("release-share")
    private val insights = screen("insights")
    private val insightsReason = screen("insights-reason")
    private val insightsArtist = screen("insights-artist")
    private val insightsTrack = screen("insights-track")
    private val walletOverview = screen("wallet-overview")
    private val userWallet = screen("user-wallet")
    private val subscription = screen("subscription-overview")
    private val accountOverview = screen("account-overview")

    private class Counters {
        var rootStart = 0
        var homeStart = 0
    }

    private fun module(
        counters: Counters = Counters(),
        declareRootDestinations: Boolean = true,
        insightsResolvesToReason: Boolean = false,
        hostAnchorsChildren: Boolean = true,
        sectionRedirect: String? = null
    ): NavigationModule = createNavigationModule {
        loadingModal(loadingScreen)
        rootGraph {
            start(route = { _ ->
                counters.rootStart++
                NavigationPath("home")
            })
            graph("onboarding") { screens(signUp) }
            graph(if (hostAnchorsChildren) HomeGraph else PeerHostGraph) {
                start(route = { _ ->
                    counters.homeStart++
                    dashboard
                })
                screens(dashboard, notificationManager)
                modals(newRelease, invitation)
                val catalogue = graph("home-catalogue") {
                    start(catalogueScreen)
                    screens(catalogueScreen, releaseInfo)
                    modals(releaseShare)
                }
                val insightsGraph = graph("home-insights-graph") {
                    start(route = { _ ->
                        if (insightsResolvesToReason) insightsReason else insights
                    })
                    screens(insights, insightsReason, insightsArtist, insightsTrack)
                }
                val wallet = graph("wallet") {
                    start(walletOverview)
                    screens(walletOverview, userWallet)
                }
                val subscriptionGraph = graph("subscription") {
                    start(subscription)
                    screens(subscription)
                }
                graph("account") {
                    start(accountOverview)
                    screens(accountOverview)
                }
                if (sectionRedirect != null) {
                    intercept(guard = { _ -> GuardResult.RedirectTo(sectionRedirect) }) {
                        screens(guardedSection)
                    }
                }
                if (declareRootDestinations) {
                    rootDestinations(dashboard, catalogue, insightsGraph, wallet)
                    rootDestinations(NavigationPath(subscriptionGraph.route))
                }
            }
        }
        deepLinkAliases {
            alias("studio/wallet", "home/wallet/wallet-overview")
            alias("releases/{id}", "home/home-catalogue/home-release-info") { params ->
                Params.of("release-id" to params.getString("id").orEmpty())
            }
        }
    }

    private fun store(module: NavigationModule, dispatcher: TestDispatcher): Store =
        createStore {
            module(module)
            coroutineContext(dispatcher)
        }

    private suspend fun Store.paths(): List<String> =
        selectState<NavigationState>().first().backStack.map { it.path }

    private fun coldLink(
        module: NavigationModule = module(),
        link: String,
        assertion: suspend (Store) -> Unit
    ) = runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
        val store = store(module, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        store.navigation { navigateDeepLink(link) }
        advanceUntilIdle()
        assertion(store)
    }

    private fun warmLink(
        module: NavigationModule = module(),
        link: String,
        assertion: suspend (Store) -> Unit
    ) = runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
        val store = store(module, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        store.navigation { navigateTo("onboarding/sign-up") }
        advanceUntilIdle()
        assertEquals(listOf("home/home-dashboard", "onboarding/sign-up"), store.paths())
        store.navigation { navigateDeepLink(link) }
        advanceUntilIdle()
        assertion(store)
    }

    private fun bothStarts(link: String, expected: List<String>) {
        coldLink(link = link) { assertEquals(expected, it.paths(), "cold start") }
        warmLink(link = link) { assertEquals(expected, it.paths(), "running app") }
    }

    @Test
    fun hostLandsOnItsStartDestinationAlone() =
        bothStarts("home", listOf("home/home-dashboard"))

    @Test
    fun rootDestinationGraphLandsAlone() =
        bothStarts("home/home-insights-graph", listOf("home/home-insights-graph/insights"))

    @Test
    fun rootDestinationGraphWithADynamicStartLandsOnWhateverItPicksAlone() {
        val reason = listOf("home/home-insights-graph/insights-reason")
        coldLink(module(insightsResolvesToReason = true), "home/home-insights-graph") {
            assertEquals(reason, it.paths(), "cold start")
        }
        warmLink(module(insightsResolvesToReason = true), "home/home-insights-graph") {
            assertEquals(reason, it.paths(), "running app")
        }
    }

    @Test
    fun screenInsideARootDestinationKeepsOnlyItsOwnStartBeneath() =
        bothStarts(
            "home/home-catalogue/home-release-info?release-id=1",
            listOf("home/home-catalogue/home-catalogue-screen", "home/home-catalogue/home-release-info")
        )

    @Test
    fun aliasIntoARootDestinationLandsLikeTheFullPath() =
        bothStarts(
            "releases/1",
            listOf("home/home-catalogue/home-catalogue-screen", "home/home-catalogue/home-release-info")
        )

    @Test
    fun aliasToARootDestinationStartLandsAlone() =
        bothStarts("studio/wallet", listOf("home/wallet/wallet-overview"))

    @Test
    fun rootDestinationStartLandsAlone() =
        bothStarts("home/wallet/wallet-overview", listOf("home/wallet/wallet-overview"))

    @Test
    fun screenInsideARootDestinationGraphKeepsItsOwnStartBeneath() =
        bothStarts(
            "home/wallet/user-wallet",
            listOf("home/wallet/wallet-overview", "home/wallet/user-wallet")
        )

    @Test
    fun rootDestinationDeclaredByPathLandsAlone() =
        bothStarts("home/subscription", listOf("home/subscription/subscription-overview"))

    @Test
    fun modalOnTheHostKeepsTheHostStartBeneath() =
        bothStarts("home/home-new-release", listOf("home/home-dashboard", "home/home-new-release"))

    @Test
    fun parameterizedModalOnTheHostKeepsTheHostStartBeneath() =
        bothStarts("home/invitation/team", listOf("home/home-dashboard", "home/invitation/{type}"))

    @Test
    fun modalInsideARootDestinationKeepsItsStartBeneath() =
        bothStarts(
            "home/home-catalogue/release-share",
            listOf("home/home-catalogue/home-catalogue-screen", "home/home-catalogue/release-share")
        )

    @Test
    fun sectionGraphOnTheHostKeepsTheHostStartBeneath() =
        bothStarts(
            "home/account/account-overview",
            listOf("home/home-dashboard", "home/account/account-overview")
        )

    @Test
    fun sectionScreenOnTheHostKeepsTheHostStartBeneath() =
        bothStarts(
            "home/notification-manager",
            listOf("home/home-dashboard", "home/notification-manager")
        )

    @Test
    fun targetOutsideTheHostStillAnchorsTheAppsLanding() =
        coldLink(link = "onboarding/sign-up") {
            assertEquals(listOf("home/home-dashboard", "onboarding/sign-up"), it.paths())
        }

    @Test
    fun aSoleModalFromALinkIsAlwaysDismissible() = runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
        val store = store(module(), StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        store.navigation { navigateDeepLink("home/home-new-release") }
        advanceUntilIdle()
        assertEquals(listOf("home/home-dashboard", "home/home-new-release"), store.paths())
        store.navigation { navigateBack() }
        advanceUntilIdle()
        assertEquals(listOf("home/home-dashboard"), store.paths())
    }

    @Test
    fun clearingTheStackAndOpeningAModalStillAnchorsTheHostStart() =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            val store = store(module(), StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            store.navigation {
                clearBackStack()
                navigateTo(newRelease)
            }
            advanceUntilIdle()
            assertEquals(listOf("home/home-dashboard", "home/home-new-release"), store.paths())
            store.navigation { navigateBack() }
            advanceUntilIdle()
            assertEquals(listOf("home/home-dashboard"), store.paths())
        }

    @Test
    fun switchingRootDestinationInTheAppDropsTheOneItLeaves() =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            val store = store(module(), StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            store.navigation { navigateTo("home/home-catalogue") }
            advanceUntilIdle()
            store.navigation { navigateTo(releaseInfo, "release-id" to "1") }
            advanceUntilIdle()
            assertEquals(
                listOf(
                    "home/home-catalogue/home-catalogue-screen",
                    "home/home-catalogue/home-release-info"
                ),
                store.paths()
            )
            store.navigation { navigateTo("home/home-insights-graph") }
            advanceUntilIdle()
            assertEquals(listOf("home/home-insights-graph/insights"), store.paths())
        }

    @Test
    fun retargetingTheRootDestinationYouAreInPopsBackToIt() =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            val store = store(module(), StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            store.navigation { navigateTo("home/home-insights-graph") }
            advanceUntilIdle()
            store.navigation { navigateTo(insightsArtist) }
            advanceUntilIdle()
            assertEquals(
                listOf("home/home-insights-graph/insights", "home/home-insights-graph/insights-artist"),
                store.paths()
            )
            store.navigation { navigateTo("home/home-insights-graph") }
            advanceUntilIdle()
            assertEquals(listOf("home/home-insights-graph/insights"), store.paths())
        }

    @Test
    fun retargetingTheRootDestinationStartPopsBackToIt() =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            val store = store(module(), StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            store.navigation { navigateTo("home/wallet") }
            advanceUntilIdle()
            store.navigation { navigateTo(userWallet) }
            advanceUntilIdle()
            assertEquals(
                listOf("home/wallet/wallet-overview", "home/wallet/user-wallet"),
                store.paths()
            )
            store.navigation { navigateTo(walletOverview) }
            advanceUntilIdle()
            assertEquals(listOf("home/wallet/wallet-overview"), store.paths())
        }

    @Test
    fun aScreenInsideARootDestinationIsStillPushedWhenItIsAlreadyInTheStack() =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            val store = store(module(), StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            store.navigation { navigateTo("home/home-insights-graph") }
            advanceUntilIdle()
            store.navigation { navigateTo(insightsArtist) }
            advanceUntilIdle()
            store.navigation { navigateTo(insightsTrack) }
            advanceUntilIdle()
            store.navigation { navigateTo(insightsArtist) }
            advanceUntilIdle()
            assertEquals(
                listOf(
                    "home/home-insights-graph/insights",
                    "home/home-insights-graph/insights-artist",
                    "home/home-insights-graph/insights-track",
                    "home/home-insights-graph/insights-artist"
                ),
                store.paths()
            )
        }

    @Test
    fun switchingToARootDestinationKeepsWhatIsAlreadyInsideIt() =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            val store = store(module(), StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            store.navigation { navigateTo("home/wallet") }
            advanceUntilIdle()
            store.navigation { navigateTo("home/account/account-overview") }
            advanceUntilIdle()
            assertEquals(
                listOf("home/wallet/wallet-overview", "home/account/account-overview"),
                store.paths()
            )
            store.navigation { navigateTo(userWallet) }
            advanceUntilIdle()
            assertEquals(
                listOf("home/wallet/wallet-overview", "home/wallet/user-wallet"),
                store.paths()
            )
        }

    @Test
    fun aSectionOnTheHostIsPushedOnTopOfTheRootDestinationItWasOpenedFrom() =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            val store = store(module(), StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            store.navigation { navigateTo("home/wallet") }
            advanceUntilIdle()
            store.navigation { navigateTo("home/account/account-overview") }
            advanceUntilIdle()
            assertEquals(
                listOf("home/wallet/wallet-overview", "home/account/account-overview"),
                store.paths()
            )
        }

    @Test
    fun aModalOnTheHostIsPushedWhereItWasOpened() =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            val store = store(module(), StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            store.navigation { navigateTo("home/home-insights-graph") }
            advanceUntilIdle()
            store.navigation { navigateTo(insightsArtist) }
            advanceUntilIdle()
            store.navigation { navigateTo(newRelease) }
            advanceUntilIdle()
            assertEquals(
                listOf(
                    "home/home-insights-graph/insights",
                    "home/home-insights-graph/insights-artist",
                    "home/home-new-release"
                ),
                store.paths()
            )
        }

    @Test
    fun aModalInsideARootDestinationIsPushedOnTheOneItBelongsTo() =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            val store = store(module(), StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            store.navigation { navigateTo("home/home-catalogue") }
            advanceUntilIdle()
            store.navigation { navigateTo(releaseShare) }
            advanceUntilIdle()
            assertEquals(
                listOf("home/home-catalogue/home-catalogue-screen", "home/home-catalogue/release-share"),
                store.paths()
            )
        }

    @Test
    fun aLinkToARootDestinationEvaluatesNoStartThatWouldBeDropped() =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            val counters = Counters()
            val store = store(module(counters), StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            store.navigation {
                clearBackStack()
                navigateTo("onboarding/sign-up")
            }
            advanceUntilIdle()
            assertEquals(listOf("onboarding/sign-up"), store.paths())
            val rootStarts = counters.rootStart
            val homeStarts = counters.homeStart
            store.navigation { navigateDeepLink("home/wallet/user-wallet") }
            advanceUntilIdle()
            assertEquals(
                listOf("home/wallet/wallet-overview", "home/wallet/user-wallet"),
                store.paths()
            )
            assertEquals(rootStarts, counters.rootStart, "root start evaluated for a root destination")
            assertEquals(homeStarts, counters.homeStart, "host start evaluated for a root destination")
        }

    @Test
    fun aLinkOutsideARootDestinationStillEvaluatesTheHostStartItAnchors() =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            val counters = Counters()
            val store = store(module(counters), StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            store.navigation {
                clearBackStack()
                navigateTo("onboarding/sign-up")
            }
            advanceUntilIdle()
            val homeStarts = counters.homeStart
            store.navigation { navigateDeepLink("home/notification-manager") }
            advanceUntilIdle()
            assertEquals(
                listOf("home/home-dashboard", "home/notification-manager"),
                store.paths()
            )
            assertTrue(
                counters.homeStart > homeStarts,
                "the host start is the anchor and has to be evaluated"
            )
        }

    @Test
    fun aGuardRedirectIntoARootDestinationLandsThereAlone() =
        coldLink(module(sectionRedirect = "home/wallet/user-wallet"), "home/guarded-section") {
            assertEquals(
                listOf("home/wallet/wallet-overview", "home/wallet/user-wallet"),
                it.paths()
            )
        }

    @Test
    fun currentRootDestinationNamesWhatTheStackSitsOn() =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            val store = store(module(), StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            assertEquals(
                "home/home-dashboard",
                store.selectState<NavigationState>().first().currentRootDestination
            )
            store.navigation { navigateDeepLink("home/wallet/user-wallet") }
            advanceUntilIdle()
            assertEquals("home/wallet", store.selectState<NavigationState>().first().currentRootDestination)
            store.navigation { navigateTo("home/account/account-overview") }
            advanceUntilIdle()
            assertEquals(
                "home/wallet",
                store.selectState<NavigationState>().first().currentRootDestination,
                "a section over a root destination keeps it marked"
            )
            store.navigation {
                clearBackStack()
                navigateTo("onboarding/sign-up")
            }
            advanceUntilIdle()
            assertNull(store.selectState<NavigationState>().first().currentRootDestination)
        }

    @Test
    fun aHostWithoutRootDestinationsAnchorsItsStartAsBefore() =
        coldLink(module(declareRootDestinations = false), "home/wallet/user-wallet") {
            assertEquals(
                listOf("home/home-dashboard", "home/wallet/wallet-overview", "home/wallet/user-wallet"),
                it.paths()
            )
        }

    @Test
    fun aHostWithoutRootDestinationsStillHonoursStartAnchorsChildren() =
        coldLink(
            module(declareRootDestinations = false, hostAnchorsChildren = false),
            "home/wallet/user-wallet"
        ) {
            assertEquals(
                listOf("home/wallet/wallet-overview", "home/wallet/user-wallet"),
                it.paths()
            )
        }

    @Test
    fun declaredRootDestinationsWinOverStartAnchorsChildren() =
        coldLink(module(hostAnchorsChildren = false), "home/notification-manager") {
            assertEquals(
                listOf("home/home-dashboard", "home/notification-manager"),
                it.paths()
            )
        }
}
