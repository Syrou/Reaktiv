package io.github.syrou.reaktiv.tracing.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

/**
 * Configuration keys for the Reaktiv tracing compiler plugin.
 */
object ReaktivTracingConfigurationKeys {
    val ENABLED: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("enabled")

    val TRACE_PRIVATE_METHODS: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("tracePrivateMethods")

    val GITHUB_REPO_URL: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("githubRepoUrl")

    val GITHUB_BRANCH: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("githubBranch")

    val PROJECT_DIR: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create("projectDir")
}

/**
 * Command line processor for the Reaktiv tracing compiler plugin.
 *
 * Processes command line options and stores them in the compiler configuration
 * for use by the IR generation extension.
 */
@OptIn(ExperimentalCompilerApi::class)
class ReaktivTracingCommandLineProcessor : CommandLineProcessor {

    companion object {
        const val PLUGIN_ID = "io.github.syrou.reaktiv.tracing"

        val OPTION_ENABLED = CliOption(
            optionName = "enabled",
            valueDescription = "<true|false>",
            description = "Enable or disable logic tracing",
            required = false,
            allowMultipleOccurrences = false
        )

        val OPTION_TRACE_PRIVATE = CliOption(
            optionName = "tracePrivateMethods",
            valueDescription = "<true|false>",
            description = "Whether to trace private methods (default: false)",
            required = false,
            allowMultipleOccurrences = false
        )

        val OPTION_GITHUB_REPO_URL = CliOption(
            optionName = "githubRepoUrl",
            valueDescription = "<url>",
            description = "GitHub repository URL for source linking",
            required = false,
            allowMultipleOccurrences = false
        )

        val OPTION_GITHUB_BRANCH = CliOption(
            optionName = "githubBranch",
            valueDescription = "<branch>",
            description = "Git branch for source linking",
            required = false,
            allowMultipleOccurrences = false
        )

        val OPTION_PROJECT_DIR = CliOption(
            optionName = "projectDir",
            valueDescription = "<path>",
            description = "Project root directory for computing relative file paths",
            required = false,
            allowMultipleOccurrences = false
        )
    }

    override val pluginId: String = PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        OPTION_ENABLED,
        OPTION_TRACE_PRIVATE,
        OPTION_GITHUB_REPO_URL,
        OPTION_GITHUB_BRANCH,
        OPTION_PROJECT_DIR
    )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration
    ) {
        when (option.optionName) {
            OPTION_ENABLED.optionName -> {
                configuration.put(ReaktivTracingConfigurationKeys.ENABLED, value.toBoolean())
            }
            OPTION_TRACE_PRIVATE.optionName -> {
                configuration.put(ReaktivTracingConfigurationKeys.TRACE_PRIVATE_METHODS, value.toBoolean())
            }
            OPTION_GITHUB_REPO_URL.optionName -> {
                configuration.put(ReaktivTracingConfigurationKeys.GITHUB_REPO_URL, value)
            }
            OPTION_GITHUB_BRANCH.optionName -> {
                configuration.put(ReaktivTracingConfigurationKeys.GITHUB_BRANCH, value)
            }
            OPTION_PROJECT_DIR.optionName -> {
                configuration.put(ReaktivTracingConfigurationKeys.PROJECT_DIR, value)
            }
        }
    }
}
