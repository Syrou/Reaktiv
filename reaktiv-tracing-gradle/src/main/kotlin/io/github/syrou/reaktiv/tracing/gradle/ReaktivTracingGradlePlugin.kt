package io.github.syrou.reaktiv.tracing.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * Gradle plugin for applying the Reaktiv logic tracing compiler plugin.
 *
 * Applying this plugin declares that a module holds ModuleLogic subclasses worth instrumenting. It
 * does not by itself generate anything: tracing is off until the build being run matches a declared
 * activation criterion, so a module can carry the plugin permanently and still ship clean release
 * artifacts.
 *
 * Usage:
 * ```kotlin
 * plugins {
 *     id("io.github.syrou.reaktiv.tracing") version "x.y.z"
 * }
 *
 * reaktivTracing {
 *     enableForTasksMatching("staging")
 *     conflictsWithTasksMatching("production")
 *     enableForXcodeConfigurations("Debug")
 * }
 * ```
 *
 * Activation resolves to true when any of the following holds:
 * - Xcode's `CONFIGURATION` environment variable matches `xcodeConfigurations`
 * - a requested task name contains one of `activatingTaskPatterns`
 * - `buildTypes` is non-empty, which then also filters which compilations are instrumented
 *
 * Setting `enabled` explicitly bypasses all of that and wins outright, which is the escape hatch
 * when the declarative criteria do not fit a build or misjudge it:
 * ```kotlin
 * reaktivTracing {
 *     enabled.set(myOwnCondition)
 * }
 * ```
 * An explicit value also skips conflict resolution entirely, since the build has taken
 * responsibility for the decision.
 *
 * When one invocation requests both a tracing build and a conflicting one, which is what
 * `./gradlew assembleProduction assembleStaging` does, `onConflict` decides the outcome. It
 * defaults to disabling tracing for that invocation so the non-tracing artifact stays clean.
 *
 * When activation is false the compiler plugin is not applied to any compilation and no tracing
 * dependency is added, so neither generated code nor the tracing runtime can reach the artifact.
 * When it is true the plugin adds reaktiv-tracing-annotations and reaktiv-tracing-runtime to the
 * module's implementation configuration and applies reaktiv-tracing-compiler to its compilations.
 *
 * Applying the plugin with no criteria and no explicit `enabled` resolves to false and logs a
 * warning naming the project, since a codegen plugin that silently does nothing is hard to diagnose.
 *
 * @see ReaktivTracingExtension for the configuration DSL
 */
class ReaktivTracingGradlePlugin : KotlinCompilerPluginSupportPlugin {

    companion object {
        const val EXTENSION_NAME = "reaktivTracing"
        const val PLUGIN_ID = "io.github.syrou.reaktiv.tracing"
        const val COMPILER_PLUGIN_ID = "io.github.syrou.reaktiv.tracing"
        const val GROUP_ID = "io.github.syrou"
        const val COMPILER_ARTIFACT_ID = "reaktiv-tracing-compiler"
        const val ANNOTATIONS_ARTIFACT_ID = "reaktiv-tracing-annotations"
        const val RUNTIME_ARTIFACT_ID = "reaktiv-tracing-runtime"
        const val XCODE_CONFIGURATION_ENV = "CONFIGURATION"
    }

    private lateinit var extension: ReaktivTracingExtension

    private var conflictReported = false

    override fun apply(target: Project) {
        extension = target.extensions.create(
            EXTENSION_NAME,
            ReaktivTracingExtension::class.java
        )

        extension.tracePrivateMethods.convention(false)
        extension.buildTypes.convention(emptySet())
        extension.activatingTaskPatterns.convention(emptySet())
        extension.conflictingTaskPatterns.convention(emptySet())
        extension.xcodeConfigurations.convention(emptySet())
        extension.onConflict.convention(TracingConflictPolicy.DISABLE)
        extension.enabled.convention(activationProvider(target))

        // Auto-detect git info with conventions
        extension.githubRepoUrl.convention(
            target.provider { detectGitHubUrl(target) ?: "" }
        )
        extension.githubBranch.convention(
            target.provider { detectGitBranch(target) ?: "main" }
        )

        target.afterEvaluate {
            if (extension.enabled.get()) {
                addDependencies(target)
            } else {
                warnWhenNoActivationCriteria(target)
            }
        }
    }

    private fun activationProvider(project: Project): Provider<Boolean> =
        project.providers.environmentVariable(XCODE_CONFIGURATION_ENV)
            .map { configuration ->
                extension.xcodeConfigurations.get().any { it.equals(configuration, ignoreCase = true) } ||
                    ambientActivation(project)
            }
            .orElse(project.provider { ambientActivation(project) })

    private fun ambientActivation(project: Project): Boolean {
        val activatedByTasks = requestedTasksActivate(project)
        return activatedByTasks || extension.buildTypes.get().isNotEmpty()
    }

    private fun requestedTasksActivate(project: Project): Boolean {
        val patterns = extension.activatingTaskPatterns.get()
        if (patterns.isEmpty()) return false

        val requested = project.gradle.startParameter.taskNames
        val activating = patterns.any { pattern ->
            requested.any { it.contains(pattern, ignoreCase = true) }
        }
        if (!activating) return false

        val conflicts = extension.conflictingTaskPatterns.get().filter { pattern ->
            requested.any { it.contains(pattern, ignoreCase = true) }
        }
        if (conflicts.isEmpty()) return true

        return when (extension.onConflict.get()) {
            TracingConflictPolicy.INSTRUMENT_ALL -> {
                reportConflictOnce {
                    project.logger.warn(
                        "reaktivTracing: $requested requests both a tracing build and $conflicts. " +
                            "onConflict is INSTRUMENT_ALL, so the non-tracing artifact will also " +
                            "carry instrumentation. Do not ship it."
                    )
                }
                true
            }

            TracingConflictPolicy.FAIL -> throw GradleException(
                "Reaktiv tracing: the requested tasks $requested activate tracing and also match " +
                    "$conflicts. A module with a single compilation per target cannot produce an " +
                    "instrumented and a clean artifact in the same invocation. Run these as separate " +
                    "invocations, or set onConflict to DISABLE or INSTRUMENT_ALL."
            )

            else -> {
                reportConflictOnce {
                    project.logger.lifecycle(
                        "reaktivTracing: $requested requests both a tracing build and $conflicts, " +
                            "which a single compilation cannot satisfy at once. Tracing is disabled " +
                            "for this invocation so the non-tracing artifact stays clean. Build the " +
                            "tracing variant on its own to get traces."
                    )
                }
                false
            }
        }
    }

    /**
     * Activation is resolved once per compilation, so the conflict is reported only the first time
     * to keep a multi-target module from repeating the same line for every target it builds.
     */
    private inline fun reportConflictOnce(report: () -> Unit) {
        if (conflictReported) return
        conflictReported = true
        report()
    }

    private fun warnWhenNoActivationCriteria(project: Project) {
        if (extension.activatingTaskPatterns.get().isNotEmpty()) return
        if (extension.xcodeConfigurations.get().isNotEmpty()) return
        if (extension.buildTypes.get().isNotEmpty()) return
        project.logger.warn(
            "reaktivTracing is applied to ${project.path} but declares no activation criteria and " +
                "resolves to disabled, so no tracing code will be generated. If that is " +
                "unexpected, declare enableForTasksMatching(...), enableForXcodeConfigurations(...) " +
                "or buildTypes, or set enabled directly to take the decision over."
        )
    }

    private fun addDependencies(project: Project) {
        val version = getPluginVersion()

        project.configurations.all { config ->
            if (config.name == "implementation" || config.name == "commonMainImplementation") {
                project.dependencies.add(
                    config.name,
                    "$GROUP_ID:$ANNOTATIONS_ARTIFACT_ID:$version"
                )
                project.dependencies.add(
                    config.name,
                    "$GROUP_ID:$RUNTIME_ARTIFACT_ID:$version"
                )
            }
        }
    }

    private var cachedVersion: String? = null

    private fun getPluginVersion(): String {
        cachedVersion?.let { return it }

        val version = javaClass.`package`.implementationVersion
            ?: System.getProperty("reaktiv.tracing.version")
            ?: getVersionFromGitTag()
            ?: "0.0.1-SNAPSHOT"

        cachedVersion = version
        return version
    }

    private fun getVersionFromGitTag(): String? {
        return try {
            val process = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
                .redirectErrorStream(true)
                .start()
            val result = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            if (exitCode == 0 && result.isNotEmpty()) result else null
        } catch (e: Exception) {
            null
        }
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
        if (!extension.enabled.get()) {
            return false
        }

        val allowedBuildTypes = extension.buildTypes.get()
        if (allowedBuildTypes.isEmpty()) {
            // No filter specified, apply to all build types
            return true
        }

        // Check if compilation name contains any of the allowed build types
        // Compilation names are like "debug", "release", "staging", "debugUnitTest", etc.
        val compilationName = kotlinCompilation.name.lowercase()
        return allowedBuildTypes.any { buildType ->
            compilationName.contains(buildType.lowercase())
        }
    }

    override fun getCompilerPluginId(): String = COMPILER_PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact {
        return SubpluginArtifact(
            groupId = GROUP_ID,
            artifactId = COMPILER_ARTIFACT_ID,
            version = getPluginVersion()
        )
    }

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>
    ): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project

        return project.provider {
            val options = mutableListOf(
                SubpluginOption(
                    key = "enabled",
                    value = extension.enabled.get().toString()
                ),
                SubpluginOption(
                    key = "tracePrivateMethods",
                    value = extension.tracePrivateMethods.get().toString()
                )
            )

            val githubUrl = extension.githubRepoUrl.get()
            if (githubUrl.isNotEmpty()) {
                options.add(SubpluginOption(key = "githubRepoUrl", value = githubUrl))
                options.add(SubpluginOption(key = "githubBranch", value = extension.githubBranch.get()))
                options.add(SubpluginOption(key = "projectDir", value = project.rootProject.projectDir.absolutePath))
            }

            options
        }
    }

    private fun detectGitHubUrl(project: Project): String? {
        return try {
            val process = ProcessBuilder("git", "remote", "get-url", "origin")
                .directory(project.projectDir)
                .redirectErrorStream(true)
                .start()
            val result = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            if (exitCode == 0 && result.isNotEmpty()) {
                convertToGitHubUrl(result)
            } else null
        } catch (e: Exception) {
            project.logger.debug("Failed to detect git remote URL: ${e.message}")
            null
        }
    }

    private fun convertToGitHubUrl(remoteUrl: String): String? {
        // Handle SSH format: git@github.com:owner/repo.git
        val sshPattern = Regex("""git@github\.com:(.+?)(?:\.git)?$""")
        sshPattern.find(remoteUrl)?.let {
            return "https://github.com/${it.groupValues[1]}"
        }

        // Handle HTTPS format: https://github.com/owner/repo.git
        val httpsPattern = Regex("""https://github\.com/(.+?)(?:\.git)?$""")
        httpsPattern.find(remoteUrl)?.let {
            return "https://github.com/${it.groupValues[1]}"
        }

        // If it's already a valid GitHub URL without .git, return as-is
        if (remoteUrl.startsWith("https://github.com/")) {
            return remoteUrl.removeSuffix(".git")
        }

        return null
    }

    private fun detectGitBranch(project: Project): String? {
        return try {
            val process = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                .directory(project.projectDir)
                .redirectErrorStream(true)
                .start()
            val result = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            if (exitCode == 0 && result.isNotEmpty() && result != "HEAD") result else null
        } catch (e: Exception) {
            project.logger.debug("Failed to detect git branch: ${e.message}")
            null
        }
    }
}
