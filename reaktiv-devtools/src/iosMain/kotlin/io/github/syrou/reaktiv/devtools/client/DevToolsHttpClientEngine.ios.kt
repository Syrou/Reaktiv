package io.github.syrou.reaktiv.devtools.client

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

internal actual fun devToolsHttpClientEngine(): HttpClientEngineFactory<*> = Darwin
