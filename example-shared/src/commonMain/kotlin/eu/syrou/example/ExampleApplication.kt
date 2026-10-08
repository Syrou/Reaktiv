package eu.syrou.example

import eu.syrou.example.domain.network.ExampleHttp
import eu.syrou.example.reaktiv.TestNavigationModule.TestNavigationModule
import eu.syrou.example.reaktiv.auth.AuthLogic
import eu.syrou.example.reaktiv.auth.AuthModule
import eu.syrou.example.reaktiv.crashtest.CrashTestModule
import eu.syrou.example.reaktiv.crashtest.MockCrashlytics
import eu.syrou.example.reaktiv.lifecycledemo.LifecycleDemoModule
import eu.syrou.example.reaktiv.mail.MailModule
import eu.syrou.example.reaktiv.middleware.createTestNavigationMiddleware
import eu.syrou.example.reaktiv.news.NewsModule
import eu.syrou.example.reaktiv.settings.SettingsModule
import eu.syrou.example.reaktiv.subscription.SubscriptionModule
import eu.syrou.example.reaktiv.twitchstreams.TwitchStreamsModule
import eu.syrou.example.reaktiv.videos.VideosModule
import eu.syrou.example.ui.scaffold.HomeNavigationScaffold
import eu.syrou.example.ui.screen.AuthLoadingScreen
import eu.syrou.example.ui.screen.CrashScreen
import eu.syrou.example.ui.screen.DeepLinkAliasTestScreen
import eu.syrou.example.ui.screen.InvitationModal
import eu.syrou.example.ui.screen.LifecycleDemoScreen
import eu.syrou.example.ui.screen.LoginScreen
import eu.syrou.example.ui.screen.NotFoundScreen
import eu.syrou.example.ui.screen.PullToRefreshDemoScreen
import eu.syrou.example.ui.screen.SettingsScreen
import eu.syrou.example.ui.screen.StreamsListScreen
import eu.syrou.example.ui.screen.SystemAlertModal
import eu.syrou.example.ui.screen.UserManagementScreens
import eu.syrou.example.ui.screen.VideosListScreen
import eu.syrou.example.ui.screen.deeplink.DeeplinkDemoScreen
import eu.syrou.example.ui.screen.deeplink.DeeplinkDetailScreen
import eu.syrou.example.ui.screen.home.NotificationModal
import eu.syrou.example.ui.screen.home.leaderboard.LeaderboardDetailScreen
import eu.syrou.example.ui.screen.home.leaderboard.LeaderboardListScreen
import eu.syrou.example.ui.screen.home.leaderboard.PlayerProfileScreen
import eu.syrou.example.ui.screen.home.leaderboard.StatsDetailScreen
import eu.syrou.example.ui.screen.home.mail.MailGraph
import eu.syrou.example.ui.screen.home.mail.MailInboxScreen
import eu.syrou.example.ui.screen.home.mail.MailMessageScreen
import eu.syrou.example.ui.screen.home.mail.MailReplyModal
import eu.syrou.example.ui.screen.home.mail.MailThreadScreen
import eu.syrou.example.ui.screen.home.news.NewsListScreen
import eu.syrou.example.ui.screen.home.news.NewsScreen
import eu.syrou.example.ui.screen.home.workspace.WorkspaceScreen
import eu.syrou.example.ui.screen.home.workspace.project.ProjectFilesScreen
import eu.syrou.example.ui.screen.home.workspace.project.ProjectGalleryScreen
import eu.syrou.example.ui.screen.home.workspace.project.ProjectOverviewScreen
import eu.syrou.example.ui.screen.home.workspace.project.ProjectSettingsScreen
import eu.syrou.example.ui.screen.home.workspace.project.ProjectTabLayout
import eu.syrou.example.ui.screen.home.workspace.project.ProjectTasksScreen
import eu.syrou.example.ui.screen.layouthandoff.HandoffAlphaGraph
import eu.syrou.example.ui.screen.layouthandoff.HandoffAlphaLayout
import eu.syrou.example.ui.screen.layouthandoff.HandoffAlphaScreen
import eu.syrou.example.ui.screen.layouthandoff.HandoffBetaGraph
import eu.syrou.example.ui.screen.layouthandoff.HandoffBetaLayout
import eu.syrou.example.ui.screen.layouthandoff.HandoffBetaScreen
import eu.syrou.example.ui.screen.subscription.SubscriptionConfettiScreen
import eu.syrou.example.ui.screen.subscription.SubscriptionGraph
import eu.syrou.example.ui.screen.subscription.SubscriptionLayout
import eu.syrou.example.ui.screen.subscription.SubscriptionPaymentScreen
import eu.syrou.example.ui.screen.subscription.SubscriptionPlanScreen
import eu.syrou.example.ui.screen.wizard.WizardAddonsGraph
import eu.syrou.example.ui.screen.wizard.WizardAddonsScreen
import eu.syrou.example.ui.screen.wizard.WizardConfirmScreen
import eu.syrou.example.ui.screen.wizard.WizardDeliveryScreen
import eu.syrou.example.ui.screen.wizard.WizardDetailsScreen
import eu.syrou.example.ui.screen.wizard.WizardGraph
import eu.syrou.example.ui.screen.wizard.WizardLayout
import eu.syrou.example.ui.screen.wizard.WizardPaymentScreen
import io.github.syrou.reaktiv.core.CrashRecovery
import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.model.NavigationGuard
import io.github.syrou.reaktiv.navigation.param.Params
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

class ExampleApplication(private val platform: ExamplePlatform) {

    private val http = ExampleHttp(platform.configureHttpClient)

    private val loggingMiddleware: Middleware = { action, _, _, updatedState ->
        updatedState(action)
        ReaktivDebug.general("Action: $action")
    }

    private val requireAuth: NavigationGuard = { store ->
        val authLogic = store.selectLogic<AuthLogic>()
        store.selectState<AuthModule.AuthState>()
            .mapNotNull { it.isAuthenticated }
            .first()
        delay(2000)
        if (authLogic.checkSession()) GuardResult.Allow
        else GuardResult.PendAndRedirectTo(
            navigatable = LoginScreen,
            displayHint = "Sign in to continue"
        )
    }

    private val navigationModule = createNavigationModule {
        notFoundScreen(NotFoundScreen)
        crashScreen(
            screen = CrashScreen,
            onCrash = { exception, _ ->
                MockCrashlytics.recordException(exception)
                CrashRecovery.NAVIGATE_TO_CRASH_SCREEN
            }
        )
        loadingModal(AuthLoadingScreen)
        rootGraph {
            start(
                route = { store ->
                    store.selectLogic<AuthLogic>().initializeSession()
                    val isAuthenticated = store.selectState<AuthModule.AuthState>()
                        .mapNotNull { it.isAuthenticated }
                        .first()
                    if (isAuthenticated) NewsScreen else LoginScreen
                }
            )
            screens(
                LoginScreen,
                SettingsScreen,
                VideosListScreen,
                StreamsListScreen,
                DeepLinkAliasTestScreen,
                PullToRefreshDemoScreen,
                SubscriptionConfettiScreen,
                LifecycleDemoScreen,
                *listOfNotNull(platform.twitchLogin, platform.devTools).toTypedArray(),
            )
            modals(NotificationModal, SystemAlertModal)

            graph("deeplink-demo") {
                start(route = { _ -> DeeplinkDemoScreen })
                screens(DeeplinkDemoScreen, DeeplinkDetailScreen)
            }

            graph(HandoffAlphaGraph) {
                start(HandoffAlphaScreen)
                screens(HandoffAlphaScreen)
                layout { content -> HandoffAlphaLayout(content) }
            }
            graph(HandoffBetaGraph) {
                start(HandoffBetaScreen)
                screens(HandoffBetaScreen)
                layout { content -> HandoffBetaLayout(content) }
            }

            graph(SubscriptionGraph) {
                start(SubscriptionPlanScreen)
                screens(SubscriptionPlanScreen, SubscriptionPaymentScreen)
                layout { content ->
                    SubscriptionLayout(content)
                }
            }

            graph(WizardGraph) {
                start(WizardDetailsScreen)
                screens(WizardDetailsScreen, WizardPaymentScreen, WizardConfirmScreen)
                layout { content ->
                    WizardLayout(content)
                }

                graph(WizardAddonsGraph) {
                    start(WizardAddonsScreen)
                    screens(WizardAddonsScreen, WizardDeliveryScreen)
                }
            }

            intercept(
                guard = requireAuth,
            ) {
                graph("home") {
                    start("news")
                    modals(InvitationModal)
                    layout { content ->
                        HomeNavigationScaffold(content)
                    }

                    graph("news") {
                        start(NewsScreen)
                        screens(NewsListScreen)
                    }

                    graph("workspace") {
                        start(WorkspaceScreen)

                        graph("projects") {
                            start(ProjectOverviewScreen)
                            screens(
                                ProjectOverviewScreen,
                                ProjectTasksScreen,
                                ProjectFilesScreen,
                                ProjectSettingsScreen,
                                ProjectGalleryScreen
                            )
                            layout { content ->
                                ProjectTabLayout(content)
                            }
                        }
                    }

                    graph("leaderboard") {
                        start(LeaderboardListScreen)
                        screens(LeaderboardDetailScreen, PlayerProfileScreen, StatsDetailScreen)
                    }

                    graph(MailGraph) {
                        start(MailInboxScreen)
                        screens(MailInboxScreen, MailMessageScreen, MailThreadScreen)
                        modals(MailReplyModal)
                    }
                }
                screenGroup(UserManagementScreens)
            }
        }

        deepLinkAliases {
            alias(
                pattern = "invitations/{type}/confirm/{token}",
                targetRoute = "home/invitation/{type}"
            ) { params ->
                Params.of(
                    "type" to (params["type"] as? String ?: ""),
                    "token" to (params["token"] as? String ?: "")
                )
            }
            alias(
                pattern = "invitation/{type}",
                targetRoute = "home/invitation/{type}"
            ) { params ->
                Params.of("type" to (params["type"] as? String ?: ""))
            }
            alias(
                pattern = "invite-test/{token}",
                targetRoute = "deep-link-test/{token}"
            ) { params ->
                Params.of("token" to (params["token"] as? String ?: ""))
            }
            alias(
                pattern = "projects/{project-id}",
                targetRoute = "home/workspace/projects/overview"
            ) { params ->
                Params.of("projectId" to (params["project-id"] as? String ?: "0"))
            }
            alias(
                pattern = "deeplink-demo/detail",
                targetRoute = "deeplink-demo/demo-detail"
            ) { _ -> Params.empty() }
            alias(
                pattern = "deeplink-demo",
                targetRoute = "deeplink-demo/demo-home"
            ) { _ -> Params.empty() }
        }
    }

    val store = createStore {
        module(AuthModule)
        module(NewsModule(http))
        module(SettingsModule)
        module(LifecycleDemoModule)
        module(VideosModule(http))
        module(TestNavigationModule)
        module(TwitchStreamsModule(http))
        module(CrashTestModule)
        module(SubscriptionModule)
        module(MailModule)
        platform.extraModules.forEach { module(it) }
        module(navigationModule)
        middlewares(
            loggingMiddleware,
            createTestNavigationMiddleware()
        )
        coroutineContext(Dispatchers.Default)
    }
}
