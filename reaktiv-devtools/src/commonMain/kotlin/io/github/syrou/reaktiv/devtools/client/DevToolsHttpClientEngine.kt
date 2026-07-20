package io.github.syrou.reaktiv.devtools.client

import io.ktor.client.engine.HttpClientEngineFactory

internal expect fun devToolsHttpClientEngine(): HttpClientEngineFactory<*>
