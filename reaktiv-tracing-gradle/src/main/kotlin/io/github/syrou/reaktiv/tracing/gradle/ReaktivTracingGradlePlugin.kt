package io.github.syrou.reaktiv.tracing.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * Gradle plugin for applying the Reaktiv logic tracing compiler plugin.
 *
 * This plugin automatically configures the Kotlin compiler to instrument
 * ModuleLogic subclasses with tracing calls for DevTools integration.
 *
 * Usage:
 * ```kotlin
 * plugins {
 *     id("io.github.syrou.reaktiv.tracing") version "x.y.z"
 * }
 *
 * reaktivTracing {
 *     enabled.set(true)
 *     tracePrivateMethods.set(false)
 * }
 * ```
 *
 * Dependencies added automatically:
 * - reaktiv-tracing-annotations (runtime)
 * - reaktiv-tracing-compiler (compiler plugin)
 */
class ReaktivTracingGradlePlugin : KotlinCompilerPluginSupportPlugin {

    companion object {
        const val EXTENSION_NAME = "reaktivTracing"
        const val PLUGIN_ID = "io.github.syrou.reaktiv.tracing"
        const val COMPILER_PLUGIN_ID = "io.github.syrou.reaktiv.tracing"
        const val GROUP_ID = "io.github.syrou"
        const val COMPILER_ARTIFACT_ID = "reaktiv-tracing-compiler"
        const val ANNOTATIONS_ARTIFACT_ID = "reaktiv-tracing-annotations"
    }

    private lateinit var extension: ReaktivTracingExtension

    override fun apply(target: Project) {
        extension = target.extensions.create(
            EXTENSION_NAME,
            ReaktivTracingExtension::class.java
        )

        extension.enabled.convention(true)
        extension.tracePrivateMethods.convention(false)
        extension.buildTypes.convention(emptySet())

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
            }
        }
    }

    private fun addDependencies(project: Project) {
        val version = getPluginVersion()

        project.configurations.all { config ->
            if (config.name == "implementation" || config.name == "commonMainImplementation") {
                project.dependencies.add(
                    config.name,
                    "$GROUP_ID:$ANNOTATIONS_ARTIFACT_ID:$version"
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
