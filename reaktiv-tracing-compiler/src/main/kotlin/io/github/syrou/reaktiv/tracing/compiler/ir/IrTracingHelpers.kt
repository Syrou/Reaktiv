package io.github.syrou.reaktiv.tracing.compiler.ir

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI

internal inline fun MessageCollector.info(message: () -> String) {
    report(CompilerMessageSeverity.INFO, message())
}

internal inline fun MessageCollector.warn(message: () -> String) {
    report(CompilerMessageSeverity.WARNING, message())
}

internal inline fun MessageCollector.error(message: () -> String) {
    report(CompilerMessageSeverity.ERROR, message())
}

internal fun IrFunction.regularParameters() = parameters.filter { it.kind == IrParameterKind.Regular }

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrBuilderWithScope.irCallNamed(
    function: IrSimpleFunctionSymbol,
    receiver: IrExpression?,
    arguments: Map<String, IrExpression>
): IrCall = irCall(function).apply {
    function.owner.parameters.forEachIndexed { index, parameter ->
        when (parameter.kind) {
            IrParameterKind.DispatchReceiver -> this.arguments[index] = receiver
            IrParameterKind.Regular -> arguments[parameter.name.asString()]?.let { this.arguments[index] = it }
            else -> Unit
        }
    }
}
