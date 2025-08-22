package eu.syrou.androidexample.ui.screen

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import eu.syrou.androidexample.reaktiv.settings.SettingsModule
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object TwitchAuthWebViewScreen : Screen {
    override val route: String = "twitch_auth_webview"
    override val enterTransition: NavTransition = NavTransition.Fade
    override val exitTransition: NavTransition = NavTransition.Fade
    override val requiresAuth: Boolean = false

    class TwitchAuthWebViewClient : WebViewClient() {
        var onAccessTokenReceived: ((String) -> Unit)? = null

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            url?.let { urlString ->
                if (urlString.startsWith("https://localhost/#")) {
                    val accessToken = extractAccessToken(urlString)
                    accessToken?.let { token ->
                        onAccessTokenReceived?.invoke(token)
                    }
                    // Intercept the URL
                    return true
                }
            }
            // Load the URL as usual
            return false
        }

        private fun extractAccessToken(url: String): String? {
            val pattern = "access_token=([^&]*)".toRegex()
            val matchResult = pattern.find(url)
            return matchResult?.groupValues?.get(1)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    override fun Content(
        params: Params
    ) {
        val store = rememberStore()
        val twitchAuthWebViewClient by remember {
            mutableStateOf(TwitchAuthWebViewClient().apply {
                onAccessTokenReceived = { accessToken ->
                    store.dispatch.invoke(SettingsModule.SettingsAction.SetTwitchAccessToken(accessToken))
                    store.launch {
                        store.navigation {
                            popUpTo(SettingsScreen.route, inclusive = true)
                        }
                    }
                }
            })
        }
        BackHandler(true) {
            store.launch {
                store.navigateBack()
            }
        }
        Column(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        webViewClient = twitchAuthWebViewClient
                        settings.loadWithOverviewMode = true
                        settings.loadsImagesAutomatically = true
                        settings.allowFileAccess = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        settings.useWideViewPort = true
                        settings.setSupportZoom(true)
                    }
                },
                update = { webView ->
                    webView.loadUrl("https://id.twitch.tv/oauth2/authorize?response_type=token&client_id=w1relgmm50jmppc69fqrhh2j6e86om&redirect_uri=https://localhost&scope")
                }
            )
        }
    }
}