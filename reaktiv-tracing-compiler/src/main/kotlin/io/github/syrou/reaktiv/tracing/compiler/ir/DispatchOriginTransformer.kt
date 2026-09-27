@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package io.github.syrou.reaktiv.tracing.compiler.ir

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.fileEntry
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.FqName

internal class DispatchOriginTransformer(
    private val pluginContext: IrPluginContext,
    private val symbols: RuntimeSymbols,
    private val messageCollector: MessageCollector
) : IrElementTransformerVoidWithContext() {

    private val dispatchOwners = setOf(
        FqName("io.github.syrou.reaktiv.core.StoreAccessor"),
        FqName("io.github.syrou.reaktiv.core.Store")
    )

    private val function1 = FqName("kotlin.Function1")

    var instrumentedCount: Int = 0
        private set

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid()

        val actionIndex = dispatchedActionIndex(expression) ?: return expression
        val actionExpression = expression.arguments[actionIndex] ?: return expression
        val scopeSymbol = currentScope?.scope?.scopeOwnerSymbol ?: return expression
        val tracker = symbols.dispatchOrigin ?: return expression

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
            +irCallNamed(
                tracker.function,
                irGetObject(tracker.owner),
                mapOf("action" to irGet(actionVar), "origin" to irString(origin))
            )
            expression.arguments[actionIndex] = irGet(actionVar)
            +expression
        }
    }

    private fun dispatchedActionIndex(call: IrCall): Int? {
        val callee = call.symbol.owner
        val dispatches = when (callee.name.asString()) {
            "dispatchAndAwait" -> (callee.parent as? IrClass)?.fqNameWhenAvailable in dispatchOwners
            "invoke" -> call.dispatchReceiver?.type?.isDispatch() == true
            else -> false
        }
        if (!dispatches) return null
        return callee.parameters.indexOfFirst { it.kind == IrParameterKind.Regular }.takeIf { it >= 0 }
    }

    private fun IrType.isDispatch(): Boolean {
        val function = this as? IrSimpleType ?: return false
        if (function.classFqName != function1) return false
        val moduleAction = symbols.moduleAction ?: return false
        return function.arguments.firstOrNull()?.typeOrNull?.classOrNull == moduleAction
    }
}
