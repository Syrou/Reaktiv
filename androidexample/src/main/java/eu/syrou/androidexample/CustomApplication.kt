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
import eu.syrou.androidexample.reaktiv.crashtest.CrashTestModule
import eu.syrou.androidexample.reaktiv.middleware.createTestNavigationMiddleware
import eu.syrou.androidexample.reaktiv.news.NewsModule
import eu.syrou.androidexample.reaktiv.settings.SettingsModule
import eu.syrou.androidexample.reaktiv.twitchstreams.TwitchStreamsModule
import eu.syrou.androidexample.reaktiv.videos.VideosModule
import eu.syrou.androidexample.ui.scaffold.HomeNavigationScaffold
import eu.syrou.androidexample.ui.screen.SettingsScreen
import eu.syrou.androidexample.ui.screen.SplashScreen
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
import eu.syrou.androidexample.ui.screen.DevToolsScreen
import eu.syrou.androidexample.reaktiv.crashtest.MockCrashlytics
import eu.syrou.androidexample.ui.screen.CrashScreen
import eu.syrou.androidexample.ui.screen.NotFoundScreen
import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.introspection.CrashModule
import io.github.syrou.reaktiv.introspection.IntrospectionConfig
import io.github.syrou.reaktiv.introspection.IntrospectionModule
import io.github.syrou.reaktiv.introspection.PlatformContext
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import io.github.syrou.reaktiv.devtools.DevToolsModule
import io.github.syrou.reaktiv.devtools.middleware.DevToolsConfig
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import io.github.syrou.reaktiv.core.CrashRecovery
import io.github.syrou.reaktiv.navigation.createNavigationModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

            startScreen(SplashScreen)
            screens(
                SettingsScreen,
                TwitchAuthWebViewScreen,
                VideosListScreen,
                StreamsListScreen,
                DevToolsScreen,
            )
            modals(NotificationModal)
            graph("home") {
                startGraph("news")
                layout { content ->
                    HomeNavigationScaffold(content)
                }

                graph("news") {
                    startScreen(NewsScreen)
                    screens(NewsListScreen)
                }

                graph("workspace") {
                    startScreen(WorkspaceScreen)

                    graph("projects") {
                        startScreen(ProjectOverviewScreen)
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
                    startScreen(LeaderboardListScreen)
                    screens(LeaderboardDetailScreen, PlayerProfileScreen, StatsDetailScreen)
                }
            }

            screenGroup(UserManagementScreens)
        }

        guidedFlow("user-management") {
            step<UserManagementScreens.ViewUser>()
            step("user/67/edit?query=EDIT")
            step<UserManagementScreens.DeleteUser>()
            onComplete {
                clearBackStack()
                navigateTo("home")
            }
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
            defaultRole = ClientRole.LISTENER
        ),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        sessionCapture = sessionCapture
    )

    val store = createStore {
        /*persistenceManager(
            PlatformPersistenceStrategy(this@CustomApplication)
        )*/
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