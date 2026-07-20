package io.github.syrou.reaktiv.devtools.client

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.js.Js

internal actual fun devToolsHttpClientEngine(): HttpClientEngineFactory<*> = Js
