package eu.syrou.androidexample

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import eu.syrou.example.ExampleApp
import eu.syrou.example.ui.theme.ReaktivTheme
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.navigation.extension.navigation
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            val route = when (uri.scheme) {
                "poedex" -> uri.encodedPath?.removePrefix("/navigation/") ?: ""
                else -> listOfNotNull(uri.encodedPath, uri.encodedQuery?.let { "?$it" }).joinToString("")
            }
            lifecycleScope.launch {
                customApp.store.navigation { navigateDeepLink(route) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            ReaktivTheme {
                StoreProvider(store = customApp.store) {
                    ExampleApp(customApp.platform)
                }
            }
        }
    }
}
