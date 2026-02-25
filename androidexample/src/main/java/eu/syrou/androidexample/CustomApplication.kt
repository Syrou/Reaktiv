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
import eu.syrou.androidexample.ui.screen.DevToolsScreen
import eu.syrou.androidexample.ui.screen.LoginScreen
import eu.syrou.androidexample.ui.screen.NotFoundScreen
import eu.syrou.androidexample.ui.screen.SettingsScreen
import eu.syrou.androidexample.ui.screen.StreamsListScreen
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
import io.github.syrou.reaktiv.devtools.DevToolsModule
import io.github.syrou.reaktiv.devtools.middleware.DevToolsConfig
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.introspection.CrashModule
import io.github.syrou.reaktiv.introspection.IntrospectionConfig
import io.github.syrou.reaktiv.introspection.IntrospectionModule
import io.github.syrou.reaktiv.introspection.PlatformContext
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.model.NavigationGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        rootGraph {
            entry(
                route = { store ->
                    store.selectLogic<AuthLogic>().initializeSession()
                    val isAuthenticated = store.selectState<AuthModule.AuthState>()
                        .mapNotNull { it.isAuthenticated }
                        .first()
                    if (isAuthenticated) NewsScreen else LoginScreen
                },
                loadingScreen = AuthLoadingScreen
            )
            screens(
                LoginScreen,
                AuthLoadingScreen,
                SettingsScreen,
                TwitchAuthWebViewScreen,
                VideosListScreen,
                StreamsListScreen,
                DevToolsScreen,
            )
            modals(NotificationModal)

            intercept(
                guard = requireAuth,
                loadingScreen = AuthLoadingScreen,
            ) {
                graph("home") {
                    startGraph("news")
                    layout { content ->
                        HomeNavigationScaffold(content)
                    }

                    graph("news") {
                        entry(NewsScreen)
                        screens(NewsListScreen)
                    }

                    graph("workspace") {
                        entry(WorkspaceScreen)

                        graph("projects") {
                            entry(ProjectOverviewScreen)
                            screens(
                                ProjectOverviewScreen,
                                ProjectTasksScreen,
                                ProjectFilesScreen,
                                ProjectSettingsScreen
                            )
                            layout { content ->
                                ProjectTabLayout(content)
                            }
                        }
                    }

                    graph("leaderboard") {
                        entry(LeaderboardListScreen)
                        screens(LeaderboardDetailScreen, PlayerProfileScreen, StatsDetailScreen)
                    }
                }
            }

            screenGroup(UserManagementScreens)
        }

        screenRetentionDuration(0.toDuration(DurationUnit.SECONDS))
    }

    private val introspectionConfig = IntrospectionConfig(
        clientName = "${Build.MANUFACTURER} ${Build.MODEL}",
        platform = "Android ${Build.VERSION.RELEASE}"
    )

    val sessionCapture = SessionCapture()

    private val introspectionModule = IntrospectionModule(
        config = introspectionConfig,
        sessionCapture = sessionCapture,
        platformContext = PlatformContext(this)
    )

    private val devToolsModule = DevToolsModule(
        config = DevToolsConfig(
            introspectionConfig = introspectionConfig,
            serverUrl = "ws://100.125.101.2:8080/ws",
            enabled = true,
            defaultRole = ClientRole.PUBLISHER
        ),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        sessionCapture = sessionCapture
    )

    val store = createStore {
        /*persistenceManager(
            PlatformPersistenceStrategy(this@CustomApplication)
        )*/
        module(AuthModule)
        module(
            NewsModule
        )
        module(SettingsModule)
        module(VideosModule)
        module(TestNavigationModule)
        module(TwitchStreamsModule)
        module(CrashTestModule)
        module(introspectionModule)
        module(navigationModule)
        module(devToolsModule)
        module(CrashModule(PlatformContext(this@CustomApplication), sessionCapture))
        middlewares(
            loggingMiddleware,
            createTestNavigationMiddleware()
        )
        coroutineContext(Dispatchers.Default)
    }

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

        println("KASTRULL - WORK REQUEST SHOULD HAVE BEEN PUT IN QUEUE")
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "fetch_periodic_news",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }
}