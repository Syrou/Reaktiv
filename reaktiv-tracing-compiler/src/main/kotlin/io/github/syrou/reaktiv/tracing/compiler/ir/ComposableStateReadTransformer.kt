@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package io.github.syrou.reaktiv.tracing.compiler.ir

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class ComposableStateReadTransformer(
    private val pluginContext: IrPluginContext,
    private val messageCollector: MessageCollector
) : IrElementTransformerVoidWithContext() {

    private val composableFqName = FqName("androidx.compose.runtime.Composable")

    private val trackedCallables = setOf(
        "io.github.syrou.reaktiv.compose.selectState",
        "io.github.syrou.reaktiv.compose.composeState"
    )

    var instrumentedCount: Int = 0
        private set

    private val trackerClass: IrClassSymbol? by lazy {
        pluginContext.finderForBuiltins().findClass(
            ClassId(FqName("io.github.syrou.reaktiv.core.tracing"), Name.identifier("StateReadTracker"))
        )
    }

    private val notifyStateReadFun: IrSimpleFunctionSymbol? by lazy {
        trackerClass?.owner?.functions?.find { it.name.asString() == "notifyStateRead" }?.symbol
    }

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid()

        val calleeFqName = expression.symbol.owner.fqNameWhenAvailable?.asString()
            ?: return expression
        if (calleeFqName !in trackedCallables) return expression

        val composableName = enclosingComposableName() ?: return expression
        val stateFqName = expression.typeArguments.getOrNull(0)?.classFqName?.asString()
            ?: return expression

        val tracker = trackerClass ?: run {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "ReaktivTracing: StateReadTracker class not found, skipping state read instrumentation"
            )
            return expression
        }
        val notifyFun = notifyStateReadFun ?: return expression
        val scopeSymbol = currentScope?.scope?.scopeOwnerSymbol ?: return expression

        instrumentedCount += 1
        messageCollector.report(
            CompilerMessageSeverity.INFO,
            "ReaktivTracing: Instrumenting state read of $stateFqName in $composableName"
        )

        val builder = DeclarationIrBuilder(pluginContext, scopeSymbol)
        return builder.irBlock(resultType = expression.type) {
            +irCall(notifyFun).apply {
                dispatchReceiver = irGetObject(tracker)
                val offset = if (notifyFun.owner.dispatchReceiverParameter != null) 1 else 0
                arguments[offset + 0] = irString(stateFqName)
                arguments[offset + 1] = irString(composableName)
            }
            +expression
        }
    }

    private fun enclosingComposableName(): String? {
        val functions = allScopes.mapNotNull { it.irElement as? IrFunction }
        if (functions.none { it.hasAnnotation(composableFqName) }) return null
        val named = functions.lastOrNull { !it.name.isSpecial } ?: return null
        return named.fqNameWhenAvailable?.asString() ?: named.name.asString()
    }
}
