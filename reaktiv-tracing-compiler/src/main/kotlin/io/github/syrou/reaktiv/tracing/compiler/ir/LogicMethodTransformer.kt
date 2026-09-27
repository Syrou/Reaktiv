@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package io.github.syrou.reaktiv.tracing.compiler.ir

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irNotEquals
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.impl.IrCatchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrThrowImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTryImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.fileEntry
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isSubclassOf
import org.jetbrains.kotlin.ir.util.isSuspend
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.FqName

/**
 * IR transformer that instruments ModuleLogic methods with tracing calls.
 *
 * For each eligible method (public suspend methods in ModuleLogic subclasses),
 * this transformer wraps the method body to:
 * 1. Call LogicTracer.notifyMethodStart() at the beginning
 * 2. Record start time
 * 3. Execute original body in try-catch
 * 4. Call LogicTracer.notifyMethodCompleted() on success
 * 5. Call LogicTracer.notifyMethodFailed() on exception
 *
 * @param pluginContext The IR plugin context
 * @param tracePrivateMethods Whether to trace private methods as well
 * @param githubRepoUrl GitHub repository URL for source linking (may be null)
 * @param githubBranch Git branch for source linking
 * @param projectDir Project root directory for computing relative file paths
 * @param messageCollector Compiler message collector for logging
 */
internal class LogicMethodTransformer(
    private val pluginContext: IrPluginContext,
    private val symbols: RuntimeSymbols,
    private val tracePrivateMethods: Boolean,
    private val githubRepoUrl: String?,
    private val githubBranch: String,
    private val projectDir: String?,
    private val messageCollector: MessageCollector
) : IrElementTransformerVoidWithContext() {

    private val noTraceFqName = FqName("io.github.syrou.reaktiv.tracing.annotations.NoTrace")
    private val traceFqName = FqName("io.github.syrou.reaktiv.tracing.annotations.Trace")
    private val sensitiveFqName = FqName("io.github.syrou.reaktiv.tracing.annotations.Sensitive")
    private val piiFqName = FqName("io.github.syrou.reaktiv.tracing.annotations.PII")

    private val irBuiltIns get() = pluginContext.irBuiltIns

    override fun visitFunctionNew(declaration: IrFunction): IrStatement {
        if (!shouldTrace(declaration)) {
            return super.visitFunctionNew(declaration)
        }
        val tracer = symbols.tracer ?: return super.visitFunctionNew(declaration)
        val originalBody = declaration.body ?: return super.visitFunctionNew(declaration)

        val parentClass = declaration.parent as? IrClass
        val className = parentClass?.fqNameWhenAvailable?.asString()
            ?: parentClass?.name?.asString()
            ?: declaration.fileEntry.name.substringAfterLast('/').substringAfterLast('\\')
        val methodName = declaration.name.asString()

        messageCollector.info { "ReaktivTracing: Transforming method $className.$methodName" }

        declaration.body = transformBody(declaration, originalBody, tracer, className, methodName)

        return super.visitFunctionNew(declaration)
    }

    private fun transformBody(
        function: IrFunction,
        originalBody: IrBody,
        tracer: RuntimeSymbols.Tracer,
        className: String,
        methodName: String
    ): IrBody {
        val builder = DeclarationIrBuilder(pluginContext, function.symbol)

        return builder.irBlockBody {
            val startTimeVar = irTemporary(
                value = irCall(tracer.currentTimeMillis),
                nameHint = "tracing_startTime"
            )

            val paramsVar = irTemporary(
                value = irIfTracerActive(
                    tracer = tracer,
                    type = irBuiltIns.mapClass.typeWith(irBuiltIns.stringType, irBuiltIns.stringType),
                    thenPart = buildParamsMap(function),
                    elsePart = buildEmptyParamsMap()
                ),
                nameHint = "tracing_params"
            )

            val absoluteFilePath = function.fileEntry.name
            val lineNumber = if (function.startOffset >= 0) {
                function.fileEntry.getLineNumber(function.startOffset) + 1
            } else null
            val relativeFilePath = computeRelativeFilePath(absoluteFilePath)
            val githubSourceUrl = buildGitHubSourceUrl(relativeFilePath, lineNumber)

            val callIdVar = irTemporary(
                value = irCallNamed(
                    tracer.start,
                    irGetObject(tracer.owner),
                    buildMap {
                        put("logicClass", irString(className))
                        put("methodName", irString(methodName))
                        put("params", irGet(paramsVar))
                        put("sourceFile", relativeFilePath?.let { irString(it) } ?: irNull())
                        put("lineNumber", lineNumber?.let { irInt(it) } ?: irNull())
                        put("githubSourceUrl", githubSourceUrl?.let { irString(it) } ?: irNull())
                        buildRedactionsMap(function)?.let { put("redactions", it) }
                    }
                ),
                nameHint = "tracing_callId"
            )

            val returnType = function.returnType
            val isUnitReturn = returnType.isUnit()

            val exceptionVar = scope.createTemporaryVariable(
                irExpression = irNull(irBuiltIns.throwableType),
                nameHint = "tracing_exception",
                isMutable = false,
                origin = IrDeclarationOrigin.CATCH_PARAMETER
            ).symbol.owner

            val catchBlock = irBlock {
                +irCallNamed(
                    tracer.failed,
                    irGetObject(tracer.owner),
                    mapOf(
                        "callId" to irGet(callIdVar),
                        "exception" to irGet(exceptionVar),
                        "durationMs" to irComputeDuration(tracer, startTimeVar)
                    )
                )
                +IrThrowImpl(
                    startOffset = UNDEFINED_OFFSET,
                    endOffset = UNDEFINED_OFFSET,
                    type = irBuiltIns.nothingType,
                    value = irGet(exceptionVar)
                )
            }

            val returnTransformer = ReturnTransformer(function, tracer, callIdVar, startTimeVar, returnType)

            val tryBlock = if (isUnitReturn) {
                irBlock {
                    when (originalBody) {
                        is IrBlockBody -> {
                            for (statement in originalBody.statements) {
                                +(statement.transform(returnTransformer, null) as IrStatement)
                            }
                        }
                        is IrExpressionBody -> +originalBody.expression.transform(returnTransformer, null)
                        else -> Unit
                    }
                    +irNotifyCompleted(tracer, callIdVar, startTimeVar, null, "Unit")
                }
            } else {
                irBlock(resultType = returnType) {
                    when (originalBody) {
                        is IrBlockBody -> {
                            val statements = originalBody.statements
                            if (statements.isEmpty()) {
                                +irNull()
                            } else {
                                for (i in 0 until statements.size - 1) {
                                    +(statements[i].transform(returnTransformer, null) as IrStatement)
                                }

                                val lastStatement = statements.last()
                                val transformedLast = lastStatement.transform(returnTransformer, null)

                                if (lastStatement is IrReturn) {
                                    +(transformedLast as IrExpression)
                                } else if (transformedLast is IrExpression) {
                                    val resultTmp = irTemporary(
                                        value = transformedLast,
                                        nameHint = "tracing_implicitResult"
                                    )
                                    +irNotifyCompleted(tracer, callIdVar, startTimeVar, resultTmp, returnType.traceName())
                                    +irGet(resultTmp)
                                } else {
                                    messageCollector.warn {
                                        "ReaktivTracing: $methodName - last statement is not an expression " +
                                            "(${transformedLast::class.simpleName}), no completion call added"
                                    }
                                    +(transformedLast as IrStatement)
                                }
                            }
                        }
                        is IrExpressionBody -> {
                            val resultTmp = irTemporary(
                                value = originalBody.expression.transform(returnTransformer, null),
                                nameHint = "tracing_result"
                            )
                            +irNotifyCompleted(tracer, callIdVar, startTimeVar, resultTmp, returnType.traceName())
                            +irGet(resultTmp)
                        }
                        else -> Unit
                    }
                }
            }

            val catchClause = IrCatchImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                catchParameter = exceptionVar,
                result = catchBlock
            )

            +IrTryImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = if (isUnitReturn) irBuiltIns.unitType else returnType,
                tryResult = tryBlock,
                catches = listOf(catchClause),
                finallyExpression = null
            )
        }
    }

    private fun IrBuilderWithScope.irComputeDuration(tracer: RuntimeSymbols.Tracer, startTimeVar: IrVariable): IrExpression =
        irCall(symbols.longMinus).apply {
            arguments[0] = irCall(tracer.currentTimeMillis)
            arguments[1] = irGet(startTimeVar)
        }

    private fun IrBuilderWithScope.buildEmptyParamsMap(): IrExpression =
        irCall(symbols.emptyMap).also {
            it.typeArguments[0] = irBuiltIns.stringType
            it.typeArguments[1] = irBuiltIns.stringType
        }

    private fun IrBuilderWithScope.buildParamsMap(function: IrFunction): IrExpression {
        val valueParams = function.regularParameters()
        if (valueParams.isEmpty()) {
            return buildEmptyParamsMap()
        }
        return buildStringMap("tracing_paramsMap", valueParams.map { it.name.asString() to irToStringSafe(irGet(it)) })
    }

    private fun IrBuilderWithScope.buildStringMap(nameHint: String, entries: List<Pair<String, IrExpression>>): IrExpression =
        irBlock {
            val mapVar = irTemporary(
                value = irCall(symbols.mutableMapOf).also {
                    it.typeArguments[0] = irBuiltIns.stringType
                    it.typeArguments[1] = irBuiltIns.stringType
                },
                nameHint = nameHint
            )
            for ((key, value) in entries) {
                +irCall(symbols.mapPut).apply {
                    arguments[0] = irGet(mapVar)
                    arguments[1] = irString(key)
                    arguments[2] = value
                }
            }
            +irGet(mapVar)
        }

    private fun IrType.traceName(): String = classFqName?.shortName()?.asString() ?: "Unknown"

    private fun IrBuilderWithScope.irNotifyCompleted(
        tracer: RuntimeSymbols.Tracer,
        callIdVar: IrVariable,
        startTimeVar: IrVariable,
        result: IrVariable?,
        resultTypeName: String
    ): IrExpression = irCallNamed(
        tracer.completed,
        irGetObject(tracer.owner),
        mapOf(
            "callId" to irGet(callIdVar),
            "result" to (result?.let {
                irIfTracerActive(
                    tracer = tracer,
                    type = irBuiltIns.stringType.makeNullable(),
                    thenPart = irToStringSafe(irGet(it)),
                    elsePart = irNull()
                )
            } ?: irNull()),
            "resultType" to irString(resultTypeName),
            "durationMs" to irComputeDuration(tracer, startTimeVar)
        )
    )

    private fun IrValueParameter.redactionName(): String? = when {
        hasAnnotation(sensitiveFqName) -> "Sensitive"
        hasAnnotation(piiFqName) -> "Pii"
        else -> null
    }

    private fun IrBuilderWithScope.buildRedactionsMap(function: IrFunction): IrExpression? {
        val annotated = function.regularParameters()
            .mapNotNull { param -> param.redactionName()?.let { param.name.asString() to irString(it) } }
        if (annotated.isEmpty()) return null
        return buildStringMap("tracing_redactions", annotated)
    }

    private fun IrBuilderWithScope.irToStringSafe(value: IrExpression): IrExpression {
        if ((value.type as? IrSimpleType)?.isMarkedNullable() == true) {
            return irBlock(resultType = irBuiltIns.stringType) {
                val tmp = irTemporary(value, nameHint = "tracing_nullCheck")
                +irIfThenElse(
                    type = irBuiltIns.stringType,
                    condition = irNotEquals(irGet(tmp), irNull()),
                    thenPart = irCall(symbols.anyToString).apply { arguments[0] = irGet(tmp) },
                    elsePart = irString("null")
                )
            }
        }
        return irCall(symbols.anyToString).apply { arguments[0] = value }
    }

    private fun IrBuilderWithScope.irIfTracerActive(
        tracer: RuntimeSymbols.Tracer,
        type: IrType,
        thenPart: IrExpression,
        elsePart: IrExpression
    ): IrExpression = irIfThenElse(
        type = type,
        condition = irCall(tracer.active).apply { arguments[0] = irGetObject(tracer.owner) },
        thenPart = thenPart,
        elsePart = elsePart
    )

    private fun shouldTrace(function: IrFunction): Boolean {
        val funcName = "${(function.parent as? IrClass)?.name?.asString() ?: "?"}.${function.name.asString()}"
        val isAnnotatedTrace = function.hasAnnotation(traceFqName)

        if (!function.isSuspend) {
            if (isAnnotatedTrace) {
                messageCollector.warn { "ReaktivTracing: $funcName has @Trace but is not suspend, skipping" }
            }
            return false
        }
        if (function.hasAnnotation(noTraceFqName)) {
            return false
        }
        if (function.origin != IrDeclarationOrigin.DEFINED) {
            return false
        }
        if (isAnnotatedTrace) {
            return true
        }

        val parentClass = function.parent as? IrClass ?: return false
        val moduleLogic = symbols.moduleLogic ?: return false
        if (!parentClass.isSubclassOf(moduleLogic.owner)) {
            return false
        }

        return when (function.visibility) {
            DescriptorVisibilities.PUBLIC -> true
            DescriptorVisibilities.PRIVATE -> tracePrivateMethods
            else -> false
        }
    }

    private fun computeRelativeFilePath(absolutePath: String?): String? {
        if (absolutePath == null) return null
        val projDir = projectDir ?: return absolutePath.substringAfterLast('/').substringAfterLast('\\')

        val normalizedAbsolute = absolutePath.replace('\\', '/')
        val normalizedProjDir = projDir.replace('\\', '/').removeSuffix("/")

        return if (normalizedAbsolute.startsWith(normalizedProjDir)) {
            normalizedAbsolute.removePrefix(normalizedProjDir).removePrefix("/")
        } else {
            absolutePath.substringAfterLast('/').substringAfterLast('\\')
        }
    }

    private fun buildGitHubSourceUrl(relativeFilePath: String?, lineNumber: Int?): String? {
        if (githubRepoUrl.isNullOrEmpty() || relativeFilePath == null || lineNumber == null) {
            return null
        }
        return "$githubRepoUrl/blob/$githubBranch/$relativeFilePath#L$lineNumber"
    }

    private inner class ReturnTransformer(
        private val targetFunction: IrFunction,
        private val tracer: RuntimeSymbols.Tracer,
        private val callIdVar: IrVariable,
        private val startTimeVar: IrVariable,
        private val returnType: IrType
    ) : IrElementTransformerVoid() {

        override fun visitReturn(expression: IrReturn): IrExpression {
            if (expression.returnTargetSymbol != targetFunction.symbol) {
                return super.visitReturn(expression)
            }

            val builder = DeclarationIrBuilder(pluginContext, targetFunction.symbol)

            if (returnType.isUnit()) {
                return builder.irBlock(resultType = irBuiltIns.nothingType) {
                    +irNotifyCompleted(tracer, callIdVar, startTimeVar, null, "Unit")
                    +IrReturnImpl(
                        startOffset = expression.startOffset,
                        endOffset = expression.endOffset,
                        type = irBuiltIns.nothingType,
                        returnTargetSymbol = expression.returnTargetSymbol,
                        value = expression.value.transform(this@ReturnTransformer, null)
                    )
                }
            }

            return builder.irBlock(resultType = irBuiltIns.nothingType) {
                val resultTmp = irTemporary(
                    value = expression.value.transform(this@ReturnTransformer, null),
                    nameHint = "tracing_returnResult"
                )
                +irNotifyCompleted(tracer, callIdVar, startTimeVar, resultTmp, returnType.traceName())
                +IrReturnImpl(
                    startOffset = expression.startOffset,
                    endOffset = expression.endOffset,
                    type = irBuiltIns.nothingType,
                    returnTargetSymbol = expression.returnTargetSymbol,
                    value = irGet(resultTmp)
                )
            }
        }
    }
}
