@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package io.github.syrou.reaktiv.tracing.compiler.ir

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol

internal inline fun MessageCollector.info(message: () -> String) {
    report(CompilerMessageSeverity.INFO, message())
}

internal inline fun MessageCollector.warn(message: () -> String) {
    report(CompilerMessageSeverity.WARNING, message())
}

internal fun IrCall.setValueArgs(callee: IrSimpleFunctionSymbol, vararg args: IrExpression?) {
    val offset = if (callee.owner.dispatchReceiverParameter != null) 1 else 0
    args.forEachIndexed { index, arg ->
        arguments[offset + index] = arg
    }
}

internal fun IrFunction.regularParameters() = parameters.filter { it.kind == IrParameterKind.Regular }
