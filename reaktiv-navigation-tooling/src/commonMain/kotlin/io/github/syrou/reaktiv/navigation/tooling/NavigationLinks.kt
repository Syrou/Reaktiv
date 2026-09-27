package io.github.syrou.reaktiv.navigation.tooling

import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.introspection.tooling.ServiceState
import io.github.syrou.reaktiv.introspection.tooling.ServiceStatus
import io.github.syrou.reaktiv.introspection.tooling.ToolingService
import io.github.syrou.reaktiv.introspection.tooling.ToolingServiceContext
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.extension.openLink
import io.github.syrou.reaktiv.navigation.link.AppLinkFiles
import io.github.syrou.reaktiv.navigation.link.AppLinksConfig
import io.github.syrou.reaktiv.navigation.link.appLinkFiles
import io.github.syrou.reaktiv.navigation.link.LinkOutcome
import io.github.syrou.reaktiv.navigation.link.NavigationLinkMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
public data class OpenLinkPayload(val link: String, val params: Map<String, String> = emptyMap())

public class NavigationLinks(private val allowOpening: Boolean = true) : ToolingService {

    override val name: String = SERVICE_NAME

    private var storeAccessor: StoreAccessor? = null

    override suspend fun start(context: ToolingServiceContext) {
        storeAccessor = context.storeAccessor
        val module = context.storeAccessor.getModule<NavigationModule>()
        if (module == null) {
            context.setStatus(ServiceStatus(ServiceState.DEGRADED, "this store has no navigation module"))
            return
        }
        val map = module.linkMap()
        context.publishExtension(EXTENSION_KEY, json.encodeToJsonElement(NavigationLinkMap.serializer(), map))
        context.setStatus(ServiceStatus(ServiceState.RUNNING, "${map.routes.size} routes"))
    }

    override suspend fun stop() {
        storeAccessor = null
    }

    override suspend fun onRequest(request: String, payload: JsonElement): JsonElement? = when (request) {
        OPEN_REQUEST -> open(payload)
        APP_LINKS_REQUEST -> appLinks(payload)
        else -> null
    }

    private fun appLinks(payload: JsonElement): JsonElement? {
        val module = storeAccessor?.getModule<NavigationModule>() ?: return null
        val config = json.decodeFromJsonElement(AppLinksConfig.serializer(), payload)
        return json.encodeToJsonElement(AppLinkFiles.serializer(), module.linkMap().appLinkFiles(config))
    }

    private suspend fun open(payload: JsonElement): JsonElement {
        val outcome = when (val store = storeAccessor) {
            null -> LinkOutcome.Ignored("Navigation links has not started")
            else -> if (allowOpening) {
                val open = json.decodeFromJsonElement(OpenLinkPayload.serializer(), payload)
                store.openLink(open.link, open.params)
            } else {
                LinkOutcome.Ignored("Opening links is turned off on this device")
            }
        }
        return json.encodeToJsonElement(LinkOutcome.serializer(), outcome)
    }

    public companion object {
        public const val SERVICE_NAME: String = "navigation-links"
        public const val EXTENSION_KEY: String = "navigation.links"
        public const val OPEN_REQUEST: String = "open"
        public const val APP_LINKS_REQUEST: String = "app-links"

        private val json = reaktivJson(encodeDefaults = true)
    }
}
