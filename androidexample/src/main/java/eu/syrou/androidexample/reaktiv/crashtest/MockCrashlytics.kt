package eu.syrou.androidexample.reaktiv.crashtest

object MockCrashlytics {
    private val recordedExceptions = mutableListOf<Throwable>()

    fun recordException(throwable: Throwable) {
        recordedExceptions.add(throwable)
        println("MockCrashlytics: Recorded non-fatal exception: ${throwable::class.simpleName} - ${throwable.message}")
    }

    fun getRecordedExceptions(): List<Throwable> = recordedExceptions.toList()
}
