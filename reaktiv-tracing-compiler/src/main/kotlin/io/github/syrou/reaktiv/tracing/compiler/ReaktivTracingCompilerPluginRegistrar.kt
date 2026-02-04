package io.github.syrou.reaktiv.tracing.compiler

import io.github.syrou.reaktiv.tracing.compiler.ir.ReaktivTracingIrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Compiler plugin registrar for the Reaktiv tracing plugin.
 *
 * This class registers the IR generation extension that transforms ModuleLogic
 * subclasses to include automatic tracing of method calls.
 */
@OptIn(ExperimentalCompilerApi::class)
class ReaktivTracingCompilerPluginRegistrar : CompilerPluginRegistrar() {

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val messageCollector = configuration.get(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)

        val enabled = configuration.get(ReaktivTracingConfigurationKeys.ENABLED, true)

        if (!enabled) {
            return
        }

        val tracePrivateMethods = configuration.get(
            ReaktivTracingConfigurationKeys.TRACE_PRIVATE_METHODS,
            false
        )

        val githubRepoUrl = configuration.get(ReaktivTracingConfigurationKeys.GITHUB_REPO_URL)
        val githubBranch = configuration.get(ReaktivTracingConfigurationKeys.GITHUB_BRANCH, "main")
        val projectDir = configuration.get(ReaktivTracingConfigurationKeys.PROJECT_DIR)

        IrGenerationExtension.registerExtension(
            ReaktivTracingIrGenerationExtension(
                tracePrivateMethods = tracePrivateMethods,
                githubRepoUrl = githubRepoUrl,
                githubBranch = githubBranch,
                projectDir = projectDir,
                messageCollector = messageCollector
            )
        )
    }
}
