package io.github.syrou.reaktiv.devtools.client

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO

internal actual fun devToolsHttpClientEngine(): HttpClientEngineFactory<*> = CIO
