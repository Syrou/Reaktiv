package io.github.syrou.reaktiv.navigation.link

import io.github.syrou.reaktiv.core.util.reaktivJson
import io.github.syrou.reaktiv.navigation.util.RouteTemplate
import io.github.syrou.reaktiv.navigation.util.normalizePath
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@Serializable
public data class AppLinksConfig(
    val host: String,
    val basePath: String? = null,
    val appleAppIds: List<String> = emptyList(),
    val androidPackage: String? = null,
    val androidCertFingerprints: List<String> = emptyList(),
    val androidDynamicPaths: Boolean = true,
    val paths: Set<String>? = null,
    val deepLinkScheme: String? = null,
    val deepLinkHost: String? = null
)

@Serializable
public data class AppLinkPath(
    val path: String,
    val pattern: String,
    val target: String,
    val screen: String? = null,
    val params: List<String> = emptyList()
)

@Serializable
public data class AppLinkFiles(
    val candidates: List<AppLinkPath>,
    val paths: List<AppLinkPath>,
    val appleAppSiteAssociation: String? = null,
    val assetLinks: String? = null,
    val androidManifestIntentFilter: String?,
    val androidDeepLinkIntentFilter: String? = null
)

private val appLinksJson = reaktivJson(prettyPrint = true)

private val webScheme = Regex("^(https?|\\{[^}]+})://")

public fun NavigationLinkMap.appLinkPaths(basePath: String = defaultAppLinkBase()): List<AppLinkPath> {
    val base = "/" + normalizePath(basePath).let { if (it.isEmpty()) "" else "$it/" }
    val fromRoutes = routes
        .filter { it.access == LinkAccess.Linkable }
        .map { AppLinkPath(wildcard(it.path), base + wildcard(it.path), it.path, it.screen, it.params) }
    val fromGraphs = graphs
        .filter { it.access == LinkAccess.Linkable && it.path.isNotEmpty() }
        .map { AppLinkPath(wildcard(it.path), base + wildcard(it.path), it.path, null, RouteTemplate.parse(it.path).paramNames) }
    val fromAliases = aliases.mapNotNull { alias ->
        val path = aliasPath(alias.pattern) ?: return@mapNotNull null
        val screen = routes.firstOrNull { it.path == alias.target }?.screen
        AppLinkPath(wildcard(path), base + wildcard(path), alias.target, screen, alias.params)
    }
    return (fromRoutes + fromGraphs + fromAliases).distinctBy { it.pattern }
}

public fun NavigationLinkMap.appLinkFiles(config: AppLinksConfig): AppLinkFiles {
    val candidates = appLinkPaths(config.basePath ?: defaultAppLinkBase())
    val paths = config.paths?.let { chosen -> candidates.filter { it.path in chosen } } ?: candidates
    return AppLinkFiles(
        candidates = candidates,
        paths = paths,
        appleAppSiteAssociation = config.appleAppIds.takeIf { it.isNotEmpty() }?.let { appleAppSiteAssociation(it, paths) },
        assetLinks = config.androidPackage?.takeIf { it.isNotBlank() }?.let { assetLinks(config, paths) },
        androidManifestIntentFilter = androidIntentFilter(
            verified = true,
            scheme = "https",
            host = config.host,
            paths = paths.map { it.pattern }
        ),
        androidDeepLinkIntentFilter = config.deepLinkScheme?.let(::normalizeScheme)?.let { scheme ->
            androidIntentFilter(
                verified = false,
                scheme = scheme,
                host = config.deepLinkHost?.trim()?.takeIf { it.isNotEmpty() } ?: config.host,
                paths = paths.map { "/" + it.path }
            )
        }
    )
}

private fun normalizeScheme(scheme: String): String? =
    scheme.trim().removeSuffix("://").lowercase().takeIf { it.isNotEmpty() }

private fun NavigationLinkMap.defaultAppLinkBase(): String = webPrefix?.takeIf { it.startsWith("/") } ?: "/"

private fun wildcard(path: String): String = path.replace(Regex("\\{[^}]+}"), "*")

private fun aliasPath(pattern: String): String? {
    val scheme = webScheme.find(pattern)
    return when {
        scheme != null -> pattern.substring(scheme.range.last + 1).substringAfter('/', "").ifEmpty { null }
        "://" in pattern -> null
        else -> pattern.trimStart('/').ifEmpty { null }
    }
}

private fun components(paths: List<AppLinkPath>, withComments: Boolean): JsonArray = buildJsonArray {
    paths.forEach { path ->
        add(buildJsonObject {
            put("/", path.pattern)
            if (withComments) put("comment", path.screen ?: path.target)
        })
    }
}

private fun appleAppSiteAssociation(appIds: List<String>, paths: List<AppLinkPath>): String {
    val file = buildJsonObject {
        putJsonObject("applinks") {
            putJsonArray("details") {
                add(buildJsonObject {
                    put("appIDs", JsonArray(appIds.map(::JsonPrimitive)))
                    put("components", components(paths, withComments = true))
                })
            }
        }
    }
    return appLinksJson.encodeToString(JsonElement.serializer(), file)
}

private fun assetLinks(config: AppLinksConfig, paths: List<AppLinkPath>): String {
    val statement = buildJsonObject {
        putJsonArray("relation") { add(JsonPrimitive(HANDLE_ALL_URLS)) }
        putJsonObject("target") {
            put("namespace", "android_app")
            put("package_name", config.androidPackage)
            put("sha256_cert_fingerprints", JsonArray(config.androidCertFingerprints.map(::JsonPrimitive)))
        }
        if (config.androidDynamicPaths) {
            putJsonObject("relation_extensions") {
                putJsonObject(HANDLE_ALL_URLS) {
                    put("dynamic_app_link_components", components(paths, withComments = false))
                }
            }
        }
    }
    return appLinksJson.encodeToString(JsonElement.serializer(), JsonArray(listOf(statement)))
}

private fun androidIntentFilter(verified: Boolean, scheme: String, host: String, paths: List<String>): String? {
    if (paths.isEmpty()) return null
    return buildString {
        appendLine(if (verified) "<intent-filter android:autoVerify=\"true\">" else "<intent-filter>")
        appendLine("    <action android:name=\"android.intent.action.VIEW\" />")
        appendLine("    <category android:name=\"android.intent.category.DEFAULT\" />")
        appendLine("    <category android:name=\"android.intent.category.BROWSABLE\" />")
        appendLine("    <data android:scheme=\"${xml(scheme)}\" />")
        appendLine("    <data android:host=\"${xml(host)}\" />")
        paths.forEach { path ->
            if ('*' in path) {
                val pattern = path.replace(".", "\\\\.").replace("*", ".*")
                appendLine("    <data android:pathPattern=\"${xml(pattern)}\" />")
            } else {
                appendLine("    <data android:path=\"${xml(path)}\" />")
            }
        }
        append("</intent-filter>")
    }
}

private fun xml(value: String): String =
    value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

private const val HANDLE_ALL_URLS = "delegate_permission/common.handle_all_urls"
