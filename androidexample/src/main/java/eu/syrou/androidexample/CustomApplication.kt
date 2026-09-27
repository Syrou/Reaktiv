package eu.syrou.androidexample

import android.app.Application
import eu.syrou.androidexample.tooling.examplePlatform
import eu.syrou.example.ExampleApplication
import eu.syrou.example.ExamplePlatform
import io.github.syrou.reaktiv.core.util.ReaktivDebug

lateinit var customApp: CustomApplication

class CustomApplication : Application() {

    val platform: ExamplePlatform by lazy { examplePlatform(this) }

    val store by lazy { ExampleApplication(platform).store }

    init {
        ReaktivDebug.enable()
    }

    override fun onCreate() {
        customApp = this
        super.onCreate()
    }
}
