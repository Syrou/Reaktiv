@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package io.github.syrou.reaktiv.tracing.compiler.ir

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.fileEntry
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class DispatchOriginTransformer(
    private val pluginContext: IrPluginContext,
    private val messageCollector: MessageCollector
) : IrElementTransformerVoidWithContext() {

    private val dispatchOwners = setOf(
        "io.github.syrou.reaktiv.core.StoreAccessor",
        "io.github.syrou.reaktiv.core.Store"
    )

    private fun receiverIsDispatch(receiver: IrExpression?): Boolean = when (receiver) {
        is IrCall -> receiver.symbol.owner.name.asString() == "<get-dispatch>"
        is org.jetbrains.kotlin.ir.expressions.IrGetValue ->
            receiver.symbol.owner.name.asString() == "dispatch"
        is org.jetbrains.kotlin.ir.expressions.IrGetField ->
            receiver.symbol.owner.name.asString() == "dispatch"
        else -> false
    }

    var instrumentedCount: Int = 0
        private set

    private val trackerClass: IrClassSymbol? by lazy {
        pluginContext.finderForBuiltins().findClass(
            ClassId(FqName("io.github.syrou.reaktiv.core.tracing"), Name.identifier("DispatchOriginTracker"))
        )
    }

    private val recordFun: IrSimpleFunctionSymbol? by lazy {
        trackerClass?.owner?.functions?.find { it.name.asString() == "record" }?.symbol
    }

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid()

        val callee = expression.symbol.owner
        val argumentOffset: Int = when (callee.name.asString()) {
            "dispatchAndAwait" -> {
                val ownerFqName = (callee.parent as? IrClass)?.fqNameWhenAvailable?.asString()
                    ?: return expression
                if (ownerFqName !in dispatchOwners) return expression
                if (callee.dispatchReceiverParameter != null) 1 else 0
            }
            "invoke" -> {
                val regularParams = callee.parameters.count {
                    it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular
                }
                if (regularParams != 1) return expression
                if (!receiverIsDispatch(expression.dispatchReceiver)) return expression
                1
            }
            else -> return expression
        }

        val tracker = trackerClass ?: return expression
        val record = recordFun ?: return expression
        val scopeSymbol = currentScope?.scope?.scopeOwnerSymbol ?: return expression

        val actionExpression = expression.arguments.getOrNull(argumentOffset) ?: return expression

        val enclosing = allScopes.mapNotNull { it.irElement as? IrFunction }.lastOrNull { !it.name.isSpecial }
        val fileEntry = enclosing?.fileEntry
        val fileName = fileEntry?.name?.substringAfterLast('/')?.substringAfterLast('\\')
        val line = if (fileEntry != null && expression.startOffset >= 0) {
            fileEntry.getLineNumber(expression.startOffset) + 1
        } else {
            null
        }
        val functionName = enclosing?.fqNameWhenAvailable?.asString() ?: enclosing?.name?.asString()
        val origin = buildString {
            append(functionName ?: "unknown")
            if (fileName != null) {
                append(" (")
                append(fileName)
                if (line != null) {
                    append(':')
                    append(line)
                }
                append(')')
            }
        }

        instrumentedCount += 1
        messageCollector.info { "ReaktivTracing: Recording dispatch origin at $origin" }

        val builder = DeclarationIrBuilder(pluginContext, scopeSymbol)
        return builder.irBlock(resultType = expression.type) {
            val actionVar = irTemporary(actionExpression, nameHint = "dispatch_origin_action")
            +irCall(record).apply {
                dispatchReceiver = irGetObject(tracker)
                setValueArgs(record, irGet(actionVar), irString(origin))
            }
            expression.arguments[argumentOffset] = irGet(actionVar)
            +expression
        }
    }
}
