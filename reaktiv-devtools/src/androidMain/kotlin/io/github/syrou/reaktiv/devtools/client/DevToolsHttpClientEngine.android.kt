package io.github.syrou.reaktiv.devtools.client

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun devToolsHttpClientEngine(): HttpClientEngineFactory<*> = OkHttp
