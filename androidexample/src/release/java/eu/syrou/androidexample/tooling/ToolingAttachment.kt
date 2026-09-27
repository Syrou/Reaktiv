package eu.syrou.androidexample.tooling

import android.content.Context
import eu.syrou.androidexample.ui.screen.TwitchAuthWebViewScreen
import eu.syrou.example.ExamplePlatform

fun examplePlatform(context: Context): ExamplePlatform = ExamplePlatform(twitchLogin = TwitchAuthWebViewScreen)
