package eu.syrou.androidexample.domain.network.news

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import eu.syrou.androidexample.domain.logic.NotificationHelper
import io.github.syrou.reaktiv.core.Store

class PeriodicNewsFetchesFactory(
    private val store: Store,
    private val notificationHelper: NotificationHelper
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (workerClassName) {
            PeriodicNewsFetches::class.java.name ->
                PeriodicNewsFetches(appContext, workerParameters, store, notificationHelper)
            else ->
                null
        }
    }
}