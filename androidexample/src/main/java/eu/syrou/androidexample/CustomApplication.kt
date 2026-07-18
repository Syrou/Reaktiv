package eu.syrou.androidexample

import android.app.Application
import android.os.Build
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import eu.syrou.androidexample.domain.logic.NotificationHelper
import eu.syrou.androidexample.domain.network.news.PeriodicNewsFetches
import eu.syrou.androidexample.domain.network.news.PeriodicNewsFetchesFactory
import eu.syrou.androidexample.reaktiv.TestNavigationModule.TestNavigationModule
import eu.syrou.androidexample.reaktiv.auth.AuthLogic
import eu.syrou.androidexample.reaktiv.auth.AuthModule
import eu.syrou.androidexample.reaktiv.crashtest.CrashTestModule
import eu.syrou.androidexample.reaktiv.crashtest.MockCrashlytics
import eu.syrou.androidexample.reaktiv.middleware.createTestNavigationMiddleware
import eu.syrou.androidexample.reaktiv.news.NewsModule
import eu.syrou.androidexample.reaktiv.settings.SettingsModule
import eu.syrou.androidexample.reaktiv.twitchstreams.TwitchStreamsModule
import eu.syrou.androidexample.reaktiv.videos.VideosModule
import eu.syrou.androidexample.ui.scaffold.HomeNavigationScaffold
import eu.syrou.androidexample.ui.screen.AuthLoadingScreen
import eu.syrou.androidexample.ui.screen.CrashScreen
import eu.syrou.androidexample.ui.screen.DeepLinkAliasTestScreen
import eu.syrou.androidexample.ui.screen.deeplink.DeeplinkDemoScreen
import eu.syrou.androidexample.ui.screen.deeplink.DeeplinkDetailScreen
import eu.syrou.androidexample.ui.screen.InvitationModal
import eu.syrou.androidexample.tooling.toolingModule
import eu.syrou.androidexample.tooling.toolingScreens
import eu.syrou.androidexample.ui.screen.LoginScreen
import eu.syrou.androidexample.ui.screen.NotFoundScreen
import eu.syrou.androidexample.reaktiv.lifecycledemo.LifecycleDemoModule
import eu.syrou.androidexample.ui.screen.LifecycleDemoScreen
import eu.syrou.androidexample.ui.screen.PullToRefreshDemoScreen
import eu.syrou.androidexample.ui.screen.SettingsScreen
import eu.syrou.androidexample.ui.screen.StreamsListScreen
import eu.syrou.androidexample.ui.screen.SystemAlertModal
import eu.syrou.androidexample.ui.screen.TwitchAuthWebViewScreen
import eu.syrou.androidexample.ui.screen.UserManagementScreens
import eu.syrou.androidexample.ui.screen.VideosListScreen
import eu.syrou.androidexample.ui.screen.home.NotificationModal
import eu.syrou.androidexample.ui.screen.home.leaderboard.LeaderboardDetailScreen
import eu.syrou.androidexample.ui.screen.home.leaderboard.LeaderboardListScreen
import eu.syrou.androidexample.ui.screen.home.leaderboard.PlayerProfileScreen
import eu.syrou.androidexample.ui.screen.home.leaderboard.StatsDetailScreen
import eu.syrou.androidexample.ui.screen.home.news.NewsListScreen
import eu.syrou.androidexample.ui.screen.home.news.NewsScreen
import eu.syrou.androidexample.ui.screen.home.workspace.WorkspaceScreen
import eu.syrou.androidexample.ui.screen.home.workspace.project.ProjectFilesScreen
import eu.syrou.androidexample.ui.screen.home.workspace.project.ProjectGalleryScreen
import eu.syrou.androidexample.ui.screen.home.workspace.project.ProjectOverviewScreen
import eu.syrou.androidexample.ui.screen.home.workspace.project.ProjectSettingsScreen
import eu.syrou.androidexample.ui.screen.home.workspace.project.ProjectTabLayout
import eu.syrou.androidexample.ui.screen.home.workspace.project.ProjectTasksScreen
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
import java.util.concurrent.TimeUnit
import kotlin.time.DurationUnit
import kotlin.time.toDuration

lateinit var customApp: CustomApplication

class CustomApplication : Application() {

    val loggingMiddleware: Middleware = { action, getAllStates, dispatch, updatedState ->
        println("---------- Action Dispatched ----------")
        println("Reaktiv - Action: $action")
        println("Reaktiv - State before: ${getAllStates.invoke()}")
        val newState = updatedState(action)
        println("Reaktiv - State after: $newState")
        //store.saveState(getAllStates())
        println("---------- End of Action -------------\n")
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
                TwitchAuthWebViewScreen,
                VideosListScreen,
                StreamsListScreen,
                DeepLinkAliasTestScreen,
                PullToRefreshDemoScreen,
                LifecycleDemoScreen,
                *toolingScreens().toTypedArray(),
            )
            modals(NotificationModal, SystemAlertModal)

            graph("deeplink-demo") {
                start(route = { _ -> DeeplinkDemoScreen })
                screens(DeeplinkDemoScreen, DeeplinkDetailScreen)
            }

            intercept(
                guard = requireAuth,
            ) {
                modals(InvitationModal)
                graph("home") {
                    start("news")
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
                }
                screenGroup(UserManagementScreens)
            }
        }

        deepLinkAliases {
            alias(
                pattern = "{scheme}://example.com/invitations/team/confirm/{token}",
                targetRoute = "deep-link-test/{token}"
            ) { params ->
                Params.of("token" to (params["token"] as? String ?: ""))
            }
            alias(
                pattern = "{scheme}://example.com/invitation/{type}",
                targetRoute = "invitation/{type}"
            ) { params ->
                Params.of("type" to (params["type"] as? String ?: ""))
            }
            alias(
                pattern = "{scheme}://example.com/deeplink-demo/detail",
                targetRoute = "deeplink-demo/demo-detail"
            ) { _ -> Params.empty() }
            alias(
                pattern = "{scheme}://example.com/deeplink-demo",
                targetRoute = "deeplink-demo/demo-home"
            ) { _ -> Params.empty() }
        }

        screenRetentionDuration(0.toDuration(DurationUnit.SECONDS))
    }

    val store by lazy { createStore {
        /*persistenceManager(
            PlatformPersistenceStrategy(this@CustomApplication)
        )*/
        module(AuthModule)
        module(
            NewsModule
        )
        module(SettingsModule)
        module(LifecycleDemoModule)
        module(VideosModule)
        module(TestNavigationModule)
        module(TwitchStreamsModule)
        module(CrashTestModule)
        toolingModule(this@CustomApplication)?.let { module(it) }
        module(navigationModule)
        middlewares(
            loggingMiddleware,
            createTestNavigationMiddleware()
        )
        coroutineContext(Dispatchers.Default)
    } }

    init {
        ReaktivDebug.enable()
    }

    override fun onCreate() {
        customApp = this
        super.onCreate()
    }

    private fun fetchNewsPeriodicly() {
        val notificationHelper = NotificationHelper(this)
        val config = Configuration.Builder()
            .setWorkerFactory(PeriodicNewsFetchesFactory(store, notificationHelper))
            .build()
        WorkManager.initialize(this, config)
        val workRequest = PeriodicWorkRequestBuilder<PeriodicNewsFetches>(
            15, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "fetch_periodic_news",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }
}