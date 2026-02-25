package io.github.syrou.reaktiv.navigation.dsl

import io.github.syrou.reaktiv.navigation.param.Params

/**
 * Builder for registering deep link alias mappings.
 *
 * Used within [GraphBasedBuilder.deepLinkAliases] to map external URL patterns to
 * canonical internal routes. Alias resolution happens in [NavigationLogic.navigateDeepLink]
 * before the normal route resolver.
 *
 * Example:
 * ```kotlin
 * deepLinkAliases {
 *     alias(
 *         pattern = "artist/invite",
 *         targetRoute = "workspace/invite/{token}"
 *     ) { params ->
 *         Params.of("token" to (params["token"] as? String ?: ""))
 *     }
 * }
 * ```
 */
class DeepLinkAliasBuilder {
    internal val aliases = mutableListOf<DeepLinkAlias>()

    /**
     * Register a deep link alias mapping.
     *
     * @param pattern The external path pattern to match (without query string)
     * @param targetRoute The canonical internal route to redirect to
     * @param paramsMapping Optional transform applied to the extracted params before navigating
     */
    fun alias(
        pattern: String,
        targetRoute: String,
        paramsMapping: (Params) -> Params = { it }
    ) {
        aliases.add(DeepLinkAlias(pattern, targetRoute, paramsMapping))
    }
}

/**
 * An individual deep link alias mapping a path pattern to a canonical internal route.
 *
 * @param pattern The external path pattern matched against incoming deep link paths
 * @param targetRoute The canonical internal route to navigate to
 * @param paramsMapping Optional transform applied to route params before passing to navigation
 */
data class DeepLinkAlias(
    val pattern: String,
    val targetRoute: String,
    val paramsMapping: (Params) -> Params = { it }
)
