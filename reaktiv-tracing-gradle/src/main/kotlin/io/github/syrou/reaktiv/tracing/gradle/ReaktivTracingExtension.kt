package io.github.syrou.reaktiv.tracing.gradle

import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/**
 * Extension for configuring the Reaktiv tracing compiler plugin.
 *
 * Usage in build.gradle.kts:
 * ```kotlin
 * reaktivTracing {
 *     enabled.set(true)
 *     tracePrivateMethods.set(false)
 *     // Only trace specific build types (empty = all build types)
 *     buildTypes.set(setOf("staging", "debug"))
 *     // GitHub info is auto-detected from git, but can be overridden:
 *     // githubRepoUrl.set("https://github.com/owner/repo")
 *     // githubBranch.set("main")
 * }
 * ```
 */
interface ReaktivTracingExtension {
    /**
     * Enable or disable logic tracing.
     * Default: true
     */
    val enabled: Property<Boolean>

    /**
     * Whether to trace private methods in addition to public methods.
     * Default: false
     */
    val tracePrivateMethods: Property<Boolean>

    /**
     * Build types to apply tracing to.
     * If empty, tracing is applied to all build types.
     * Example: setOf("staging") or setOf("debug", "staging")
     * Default: empty (all build types)
     */
    val buildTypes: SetProperty<String>

    /**
     * GitHub repository URL for source linking in DevTools.
     * Auto-detected from git remote if not set.
     * Example: "https://github.com/owner/repo"
     */
    val githubRepoUrl: Property<String>

    /**
     * Git branch for source linking in DevTools.
     * Auto-detected from current branch if not set.
     * Example: "main" or "develop"
     */
    val githubBranch: Property<String>
}
