package io.github.syrou.reaktiv.tracing.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

/**
 * IR generation extension for the Reaktiv tracing plugin.
 *
 * This extension is invoked during compilation and applies the [LogicMethodTransformer]
 * to transform ModuleLogic subclasses with automatic tracing instrumentation.
 *
 * @param tracePrivateMethods Whether to also trace private methods
 * @param githubRepoUrl GitHub repository URL for source linking (may be null)
 * @param githubBranch Git branch for source linking (defaults to "main")
 * @param projectDir Project root directory for computing relative file paths
 * @param messageCollector Compiler message collector for logging
 */
class ReaktivTracingIrGenerationExtension(
    private val tracePrivateMethods: Boolean,
    private val githubRepoUrl: String?,
    private val githubBranch: String,
    private val projectDir: String?,
    private val messageCollector: MessageCollector
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val transformer = LogicMethodTransformer(
            pluginContext = pluginContext,
            tracePrivateMethods = tracePrivateMethods,
            githubRepoUrl = githubRepoUrl,
            githubBranch = githubBranch,
            projectDir = projectDir,
            messageCollector = messageCollector
        )

        moduleFragment.transform(transformer, null)
    }
}
