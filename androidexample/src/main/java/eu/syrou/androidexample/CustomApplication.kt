package eu.syrou.androidexample

import android.app.Application
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import eu.syrou.androidexample.domain.logic.NotificationHelper
import eu.syrou.androidexample.domain.network.news.PeriodicNewsFetches
import eu.syrou.androidexample.domain.network.news.PeriodicNewsFetchesFactory
import eu.syrou.androidexample.reaktiv.auth.CounterModule
import eu.syrou.androidexample.reaktiv.news.NewsModule
import eu.syrou.androidexample.reaktiv.settings.SettingsModule
import eu.syrou.androidexample.reaktiv.twitchstreams.TwitchStreamsModule
import eu.syrou.androidexample.reaktiv.videos.VideosModule
import eu.syrou.androidexample.ui.scaffold.HomeNavigationScaffold
import eu.syrou.androidexample.ui.screen.SplashScreen
import eu.syrou.androidexample.ui.screen.SplashScreen2
import eu.syrou.androidexample.ui.screen.StreamsListScreen
import eu.syrou.androidexample.ui.screen.TwitchAuthWebViewScreen
import eu.syrou.androidexample.ui.screen.UserManagementScreens
import eu.syrou.androidexample.ui.screen.VideosListScreen
import eu.syrou.androidexample.ui.screen.home.leaderboard.LeaderboardDetailScreen
import eu.syrou.androidexample.ui.screen.home.leaderboard.LeaderboardListScreen
import eu.syrou.androidexample.ui.screen.home.news.NewsListScreen
import eu.syrou.androidexample.ui.screen.home.news.NewsScreen
import eu.syrou.androidexample.ui.screen.home.workspace.WorkspaceScreen
import eu.syrou.androidexample.ui.screen.home.workspace.project.ProjectFilesScreen
import eu.syrou.androidexample.ui.screen.home.workspace.project.ProjectOverviewScreen
import eu.syrou.androidexample.ui.screen.home.workspace.project.ProjectSettingsScreen
import eu.syrou.androidexample.ui.screen.home.workspace.project.ProjectTabLayout
import eu.syrou.androidexample.ui.screen.home.workspace.project.ProjectTasksScreen
import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.GraphEnterBehavior
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeUnit

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

    val store = createStore {
        persistenceManager(
            PlatformPersistenceStrategy(this@CustomApplication)
        )
        module(
            NewsModule
        )
        module(SettingsModule)
        module(VideosModule)
        module(TwitchStreamsModule)
        module(
            createNavigationModule {
                rootGraph {
                    startScreen(SplashScreen)
                    screens(
                        SplashScreen2,
                        //SettingsScreen,
                        TwitchAuthWebViewScreen,
                        VideosListScreen,
                        StreamsListScreen,
                    )

                    graph("home") {
                        //startScreen(NewsScreen)
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
                            screens(LeaderboardDetailScreen)
                        }
                    }

                    screenGroup(UserManagementScreens)
                }
            }
        )
        module(
            CounterModule
        )
        middlewares(loggingMiddleware)
        coroutineContext(Dispatchers.Default)
    }

    override fun onCreate() {
        customApp = this
        super.onCreate()

        //fetchNewsPeriodicly()
    }

    private fun fetchNewsPeriodicly() {
        val notificationHelper = NotificationHelper(this)
        val config = Configuration.Builder()
            .setWorkerFactory(PeriodicNewsFetchesFactory(store, notificationHelper))
            .build()
        WorkManager.initialize(this, config)

        // Set up periodic work request
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