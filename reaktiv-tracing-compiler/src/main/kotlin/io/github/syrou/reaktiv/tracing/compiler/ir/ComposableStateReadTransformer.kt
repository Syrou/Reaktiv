@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package io.github.syrou.reaktiv.tracing.compiler.ir

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.FqName

internal class ComposableStateReadTransformer(
    private val pluginContext: IrPluginContext,
    private val symbols: RuntimeSymbols,
    private val messageCollector: MessageCollector
) : IrElementTransformerVoidWithContext() {

    private val composableFqName = FqName("androidx.compose.runtime.Composable")

    private val trackedCallables = setOf(
        "io.github.syrou.reaktiv.compose.selectState",
        "io.github.syrou.reaktiv.compose.composeState"
    )

    var instrumentedCount: Int = 0
        private set

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid()

        val calleeFqName = expression.symbol.owner.fqNameWhenAvailable?.asString()
            ?: return expression
        if (calleeFqName !in trackedCallables) return expression

        val composableName = enclosingComposableName() ?: return expression
        val stateFqName = expression.typeArguments.getOrNull(0)?.classFqName?.asString()
            ?: return expression
        val scopeSymbol = currentScope?.scope?.scopeOwnerSymbol ?: return expression
        val tracker = symbols.stateRead ?: return expression

        instrumentedCount += 1
        messageCollector.info { "ReaktivTracing: Instrumenting state read of $stateFqName in $composableName" }

        val builder = DeclarationIrBuilder(pluginContext, scopeSymbol)
        return builder.irBlock(resultType = expression.type) {
            +irCallNamed(
                tracker.function,
                irGetObject(tracker.owner),
                mapOf("stateClass" to irString(stateFqName), "composable" to irString(composableName))
            )
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
