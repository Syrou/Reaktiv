@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package io.github.syrou.reaktiv.tracing.compiler.ir

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrCatchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrThrowImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTryImpl
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

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
class LogicMethodTransformer(
    private val pluginContext: IrPluginContext,
    private val tracePrivateMethods: Boolean,
    private val githubRepoUrl: String?,
    private val githubBranch: String,
    private val projectDir: String?,
    private val messageCollector: org.jetbrains.kotlin.cli.common.messages.MessageCollector
) : IrElementTransformerVoidWithContext() {

    private val moduleLogicFqName = FqName("io.github.syrou.reaktiv.core.ModuleLogic")
    private val noTraceFqName = FqName("io.github.syrou.reaktiv.tracing.annotations.NoTrace")

    private val irBuiltIns get() = pluginContext.irBuiltIns

    // Lazy references to LogicTracer methods
    private val logicTracerClass: IrClassSymbol? by lazy {
        pluginContext.referenceClass(
            ClassId(FqName("io.github.syrou.reaktiv.core.tracing"), Name.identifier("LogicTracer"))
        )
    }

    private val notifyMethodStartFun: IrSimpleFunctionSymbol? by lazy {
        logicTracerClass?.owner?.functions?.find { it.name.asString() == "notifyMethodStart" }?.symbol
    }

    private val notifyMethodCompletedFun: IrSimpleFunctionSymbol? by lazy {
        logicTracerClass?.owner?.functions?.find { it.name.asString() == "notifyMethodCompleted" }?.symbol
    }

    private val notifyMethodFailedFun: IrSimpleFunctionSymbol? by lazy {
        logicTracerClass?.owner?.functions?.find { it.name.asString() == "notifyMethodFailed" }?.symbol
    }

    // Reference to kotlin.system.getTimeMillis() for timing
    private val getTimeMillisFun: IrSimpleFunctionSymbol? by lazy {
        val funRef = pluginContext.referenceFunctions(
            CallableId(FqName("kotlin.system"), Name.identifier("getTimeMillis"))
        ).firstOrNull()
        messageCollector.report(
            org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.INFO,
            "ReaktivTracing: getTimeMillis resolved: ${funRef != null}"
        )
        funRef
    }

    // Map building references
    private val mutableMapOfFun: IrSimpleFunctionSymbol? by lazy {
        pluginContext.referenceFunctions(
            CallableId(FqName("kotlin.collections"), Name.identifier("mutableMapOf"))
        ).firstOrNull { fn ->
            // Find the no-arg mutableMapOf function (only has type parameters, no value params)
            fn.owner.parameters.none { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular }
        }
    }

    private val mapPutFun: IrSimpleFunctionSymbol? by lazy {
        pluginContext.referenceClass(
            ClassId(FqName("kotlin.collections"), Name.identifier("MutableMap"))
        )?.owner?.functions?.find { fn ->
            fn.name.asString() == "put" &&
            fn.parameters.count { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular } == 2
        }?.symbol
    }

    // Empty map reference (fallback)
    private val emptyMapFun: IrSimpleFunctionSymbol? by lazy {
        pluginContext.referenceFunctions(
            CallableId(FqName("kotlin.collections"), Name.identifier("emptyMap"))
        ).firstOrNull()
    }

    override fun visitFunctionNew(declaration: IrFunction): IrStatement {
        if (!shouldTrace(declaration)) {
            return super.visitFunctionNew(declaration)
        }

        // Check if we have all required references
        val tracerClass = logicTracerClass ?: run {
            messageCollector.report(org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.WARNING, "ReaktivTracing: LogicTracer class not found, skipping transformation for \${declaration.name}")
            return super.visitFunctionNew(declaration)
        }
        val startFun = notifyMethodStartFun ?: run {
            messageCollector.report(org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.WARNING, "ReaktivTracing: notifyMethodStart not found, skipping transformation")
            return super.visitFunctionNew(declaration)
        }
        val completedFun = notifyMethodCompletedFun ?: run {
            messageCollector.report(org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.WARNING, "ReaktivTracing: notifyMethodCompleted not found, skipping transformation")
            return super.visitFunctionNew(declaration)
        }
        val failedFun = notifyMethodFailedFun ?: run {
            messageCollector.report(org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.WARNING, "ReaktivTracing: notifyMethodFailed not found, skipping transformation")
            return super.visitFunctionNew(declaration)
        }

        val originalBody = declaration.body ?: return super.visitFunctionNew(declaration)

        val parentClass = declaration.parent as? IrClass ?: return super.visitFunctionNew(declaration)
        val className = parentClass.fqNameWhenAvailable?.asString() ?: parentClass.name.asString()
        val methodName = declaration.name.asString()

        messageCollector.report(
            org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.INFO,
            "ReaktivTracing: Transforming method $className.$methodName"
        )

        // Transform the function body
        declaration.body = transformBody(
            declaration,
            originalBody,
            tracerClass,
            startFun,
            completedFun,
            failedFun,
            className,
            methodName
        )

        return super.visitFunctionNew(declaration)
    }

    private fun transformBody(
        function: IrFunction,
        originalBody: IrBody,
        tracerClass: IrClassSymbol,
        startFun: IrSimpleFunctionSymbol,
        completedFun: IrSimpleFunctionSymbol,
        failedFun: IrSimpleFunctionSymbol,
        className: String,
        methodName: String
    ): IrBody {
        val builder = DeclarationIrBuilder(pluginContext, function.symbol)

        return builder.irBlockBody {
            // val startTime = getTimeMillis()
            val startTimeVar = irTemporary(
                value = irGetTimeMillis(),
                nameHint = "tracing_startTime"
            )

            // Build params map with actual parameter values
            val paramsVar = irTemporary(
                value = buildParamsMap(function),
                nameHint = "tracing_params"
            )

            // Get source file and line number from the function declaration
            val absoluteFilePath = function.fileEntry?.name
            val lineNumber = if (function.startOffset >= 0) {
                function.fileEntry?.getLineNumber(function.startOffset)?.plus(1) // Line numbers are 0-based
            } else null

            // Compute relative file path for source linking
            val relativeFilePath = computeRelativeFilePath(absoluteFilePath)

            // Build GitHub source URL if we have the required info
            val githubSourceUrl = buildGitHubSourceUrl(relativeFilePath, lineNumber)

            // val callId = LogicTracer.notifyMethodStart(className, methodName, params, sourceFile, lineNumber, githubSourceUrl)
            val callIdVar = irTemporary(
                value = irCall(startFun).apply {
                    dispatchReceiver = irGetObject(tracerClass)
                    val valueParamOffset = if (startFun.owner.dispatchReceiverParameter != null) 1 else 0
                    arguments[valueParamOffset + 0] = irString(className)
                    arguments[valueParamOffset + 1] = irString(methodName)
                    arguments[valueParamOffset + 2] = irGet(paramsVar)
                    arguments[valueParamOffset + 3] = relativeFilePath?.let { irString(it) } ?: irNull()
                    arguments[valueParamOffset + 4] = lineNumber?.let { irInt(it) } ?: irNull()
                    arguments[valueParamOffset + 5] = githubSourceUrl?.let { irString(it) } ?: irNull()
                },
                nameHint = "tracing_callId"
            )

            // Build the wrapped body with try-catch
            val returnType = function.returnType
            val isUnitReturn = returnType.isUnit()

            // Create the exception variable for the catch block using scope
            val exceptionVar = scope.createTemporaryVariable(
                irExpression = irNull(irBuiltIns.throwableType),
                nameHint = "tracing_exception",
                isMutable = false,
                origin = IrDeclarationOrigin.CATCH_PARAMETER
            ).symbol.owner

            // Calculate offset for value parameters (dispatch receiver takes slot 0)
            val failedValueParamOffset = if (failedFun.owner.dispatchReceiverParameter != null) 1 else 0

            // Build catch block
            val catchBlock = irBlock {
                // LogicTracer.notifyMethodFailed(callId, exception, duration)
                +irCall(failedFun).apply {
                    dispatchReceiver = irGetObject(tracerClass)
                    arguments[failedValueParamOffset + 0] = irGet(callIdVar)
                    arguments[failedValueParamOffset + 1] = irGet(exceptionVar)
                    arguments[failedValueParamOffset + 2] = irComputeDuration(startTimeVar)
                }
                // throw exception
                +IrThrowImpl(
                    startOffset = UNDEFINED_OFFSET,
                    endOffset = UNDEFINED_OFFSET,
                    type = irBuiltIns.nothingType,
                    value = irGet(exceptionVar)
                )
            }

            // Calculate offset for value parameters for completedFun
            val completedValueParamOffset = if (completedFun.owner.dispatchReceiverParameter != null) 1 else 0

            // Build try block (original body + success notification)
            val tryBlock = if (isUnitReturn) {
                irBlock {
                    // Execute original body
                    when (originalBody) {
                        is IrBlockBody -> {
                            for (statement in originalBody.statements) {
                                +statement
                            }
                        }
                        is IrExpressionBody -> +originalBody.expression
                        else -> { /* IrSyntheticBody - skip */ }
                    }
                    // On success: LogicTracer.notifyMethodCompleted(callId, null, "Unit", duration)
                    +irCall(completedFun).apply {
                        dispatchReceiver = irGetObject(tracerClass)
                        arguments[completedValueParamOffset + 0] = irGet(callIdVar)
                        arguments[completedValueParamOffset + 1] = irNull()
                        arguments[completedValueParamOffset + 2] = irString("Unit")
                        arguments[completedValueParamOffset + 3] = irComputeDuration(startTimeVar)
                    }
                }
            } else {
                // For non-Unit return, we need to transform all return statements
                // to inject completion notification before returning
                val returnTransformer = ReturnTransformer(
                    pluginContext = pluginContext,
                    targetFunction = function,
                    tracerClass = tracerClass,
                    completedFun = completedFun,
                    callIdVar = callIdVar,
                    startTimeVar = startTimeVar,
                    returnType = returnType,
                    methodName = methodName
                )

                irBlock(resultType = returnType) {
                    when (originalBody) {
                        is IrBlockBody -> {
                            val statements = originalBody.statements
                            if (statements.isEmpty()) {
                                // Empty body - shouldn't happen for non-Unit return, but handle gracefully
                                +irNull()
                            } else {
                                // Process all but the last statement
                                for (i in 0 until statements.size - 1) {
                                    val transformed = statements[i].transform(returnTransformer, null) as IrStatement
                                    +transformed
                                }

                                // Handle the last statement specially
                                val lastStatement = statements.last()
                                val transformedLast = lastStatement.transform(returnTransformer, null)

                                val lastStatementDetail = when (lastStatement) {
                                    is IrTry -> "IrTry (hasFinally=${lastStatement.finallyExpression != null})"
                                    is IrReturn -> "IrReturn"
                                    is IrCall -> "IrCall (${(lastStatement as IrCall).symbol.owner.name})"
                                    is IrBlock -> "IrBlock (size=${(lastStatement as IrBlock).statements.size})"
                                    else -> lastStatement::class.simpleName ?: "unknown"
                                }
                                messageCollector.report(
                                    org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.INFO,
                                    "ReaktivTracing: $methodName last statement: $lastStatementDetail, transformed: ${transformedLast::class.simpleName}"
                                )

                                // Check if last statement is already a return (handled by transformer)
                                // or if it's an expression that should be the implicit return value
                                if (lastStatement is IrReturn) {
                                    messageCollector.report(
                                        org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.INFO,
                                        "ReaktivTracing: $methodName - explicit return path"
                                    )
                                    // Already transformed by ReturnTransformer - it returns a block expression
                                    +(transformedLast as IrExpression)
                                } else if (transformedLast is IrExpression) {
                                    messageCollector.report(
                                        org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.INFO,
                                        "ReaktivTracing: $methodName - implicit return expression path, adding completion call"
                                    )
                                    // Implicit return - capture result and notify
                                    val resultTmp = irTemporary(
                                        value = transformedLast,
                                        nameHint = "tracing_implicitResult"
                                    )
                                    +irCall(completedFun).apply {
                                        dispatchReceiver = irGetObject(tracerClass)
                                        arguments[completedValueParamOffset + 0] = irGet(callIdVar)
                                        arguments[completedValueParamOffset + 1] = irToStringSafe(irGet(resultTmp))
                                        arguments[completedValueParamOffset + 2] = irString(returnType.classFqName?.shortName()?.asString() ?: "Unknown")
                                        arguments[completedValueParamOffset + 3] = irComputeDuration(startTimeVar)
                                    }
                                    +irGet(resultTmp)
                                } else {
                                    messageCollector.report(
                                        org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.WARNING,
                                        "ReaktivTracing: $methodName - last statement is not expression (${transformedLast::class.simpleName}), NO completion call added!"
                                    )
                                    // Not an expression - add as statement
                                    +(transformedLast as IrStatement)
                                }
                            }
                        }
                        is IrExpressionBody -> {
                            // Expression body - capture result and notify
                            val resultTmp = irTemporary(
                                value = originalBody.expression.transform(returnTransformer, null),
                                nameHint = "tracing_result"
                            )
                            +irCall(completedFun).apply {
                                dispatchReceiver = irGetObject(tracerClass)
                                arguments[completedValueParamOffset + 0] = irGet(callIdVar)
                                arguments[completedValueParamOffset + 1] = irToStringSafe(irGet(resultTmp))
                                arguments[completedValueParamOffset + 2] = irString(returnType.classFqName?.shortName()?.asString() ?: "Unknown")
                                arguments[completedValueParamOffset + 3] = irComputeDuration(startTimeVar)
                            }
                            +irGet(resultTmp)
                        }
                        else -> { /* IrSyntheticBody - skip */ }
                    }
                }
            }

            // Build the catch clause
            val catchClause = IrCatchImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                catchParameter = exceptionVar,
                result = catchBlock
            )

            // Build the try expression
            val tryExpression = IrTryImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = if (isUnitReturn) irBuiltIns.unitType else returnType,
                tryResult = tryBlock,
                catches = listOf(catchClause),
                finallyExpression = null
            )

            +tryExpression
        }
    }

    private fun IrBuilderWithScope.irGetTimeMillis(): IrExpression {
        val timeFun = getTimeMillisFun
        return if (timeFun != null) {
            irCall(timeFun)
        } else {
            irLong(0L)
        }
    }

    private fun IrBuilderWithScope.irComputeDuration(startTimeVar: IrVariable): IrExpression {
        val timeFun = getTimeMillisFun
        if (timeFun == null) {
            messageCollector.report(
                org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.WARNING,
                "ReaktivTracing: getTimeMillis not available, duration will be 0"
            )
            return irLong(0L)
        }

        // currentTime - startTime
        val currentTime = irCall(timeFun)
        val startTime = irGet(startTimeVar)

        // Find the minus operator on Long: Long.minus(other: Long)
        // dispatchReceiverParameter is separate from parameters, so we look for 1 regular parameter
        val minusFun = irBuiltIns.longClass.owner.functions.firstOrNull { fn ->
            fn.name.asString() == "minus" &&
            fn.parameters.count { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular } == 1 &&
            fn.parameters.any { it.type.isLong() }
        }

        return if (minusFun != null) {
            irCall(minusFun.symbol).apply {
                dispatchReceiver = currentTime
                // Value parameter is at index 0 in arguments (dispatch receiver is separate)
                val paramOffset = if (minusFun.dispatchReceiverParameter != null) 1 else 0
                arguments[paramOffset] = startTime
            }
        } else {
            messageCollector.report(
                org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.WARNING,
                "ReaktivTracing: Long.minus not found, duration will be 0"
            )
            irLong(0L)
        }
    }

    private fun IrBuilderWithScope.buildEmptyParamsMap(): IrExpression {
        val mapFun = emptyMapFun
        return if (mapFun != null) {
            irCall(mapFun).also {
                // Use typeArguments array instead of putTypeArgument
                it.typeArguments[0] = irBuiltIns.stringType
                it.typeArguments[1] = irBuiltIns.stringType
            }
        } else {
            irNull()
        }
    }

    private fun IrBuilderWithScope.buildParamsMap(function: IrFunction): IrExpression {
        // Get only regular value parameters (not dispatch receiver, extension receiver, or context receivers)
        val valueParams = function.parameters.filter {
            it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular
        }
        if (valueParams.isEmpty()) {
            return buildEmptyParamsMap()
        }

        val mutableMapOf = mutableMapOfFun ?: return buildEmptyParamsMap()
        val mapPut = mapPutFun ?: return buildEmptyParamsMap()

        return irBlock {
            // val map = mutableMapOf<String, String>()
            val mapVar = irTemporary(
                value = irCall(mutableMapOf).also {
                    it.typeArguments[0] = irBuiltIns.stringType
                    it.typeArguments[1] = irBuiltIns.stringType
                },
                nameHint = "tracing_paramsMap"
            )

            // For each parameter: map.put(name, value.toString())
            for (param in valueParams) {
                val paramName = param.name.asString()
                val paramValue = irGet(param)
                val valueAsString = irToStringSafe(paramValue)

                +irCall(mapPut).apply {
                    dispatchReceiver = irGet(mapVar)
                    val putOffset = if (mapPut.owner.dispatchReceiverParameter != null) 1 else 0
                    arguments[putOffset + 0] = irString(paramName)
                    arguments[putOffset + 1] = valueAsString
                }
            }

            // Return the map (cast to Map<String, String>)
            +irGet(mapVar)
        }
    }

    private fun IrBuilderWithScope.irToStringSafe(value: IrExpression): IrExpression {
        // Find toString() on Any (no regular value parameters)
        val toStringFun = irBuiltIns.anyClass.owner.functions.find { fn ->
            fn.name.asString() == "toString" &&
            fn.parameters.none { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular }
        }?.symbol

        return if (toStringFun != null) {
            irCall(toStringFun).apply {
                dispatchReceiver = value
            }
        } else {
            irString("<unknown>")
        }
    }

    private fun shouldTrace(function: IrFunction): Boolean {
        val funcName = "${(function.parent as? IrClass)?.name?.asString() ?: "?"}.${function.name.asString()}"

        // Must be a suspend function
        if (!function.isSuspend) {
            return false
        }

        // Must not have @NoTrace annotation
        if (function.hasAnnotation(noTraceFqName)) {
            messageCollector.report(
                org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.INFO,
                "ReaktivTracing: $funcName has @NoTrace, skipping"
            )
            return false
        }

        // Must be a user-defined function (not generated)
        if (function.origin != IrDeclarationOrigin.DEFINED) {
            return false
        }

        // Parent must be a class
        val parentClass = function.parent as? IrClass ?: return false

        // Parent must extend ModuleLogic
        if (!isModuleLogicSubclass(parentClass)) {
            return false
        }

        // Check visibility
        val visibility = function.visibility
        if (visibility == DescriptorVisibilities.PUBLIC) {
            messageCollector.report(
                org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.INFO,
                "ReaktivTracing: Will trace public method $funcName"
            )
            return true
        }
        if (tracePrivateMethods && visibility == DescriptorVisibilities.PRIVATE) {
            messageCollector.report(
                org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.INFO,
                "ReaktivTracing: Will trace private method $funcName"
            )
            return true
        }

        messageCollector.report(
            org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.INFO,
            "ReaktivTracing: $funcName visibility $visibility not traced"
        )
        return false
    }

    private fun isModuleLogicSubclass(irClass: IrClass): Boolean {
        val result = isModuleLogicSubclassRecursive(irClass, mutableSetOf())
        messageCollector.report(
            org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.INFO,
            "ReaktivTracing: Checking ${irClass.fqNameWhenAvailable} extends ModuleLogic: $result"
        )
        return result
    }

    /**
     * Computes the relative file path from the project directory.
     * This is used for source linking in DevTools.
     */
    private fun computeRelativeFilePath(absolutePath: String?): String? {
        if (absolutePath == null) return null
        val projDir = projectDir ?: return absolutePath.substringAfterLast('/').substringAfterLast('\\')

        // Normalize path separators
        val normalizedAbsolute = absolutePath.replace('\\', '/')
        val normalizedProjDir = projDir.replace('\\', '/').removeSuffix("/")

        return if (normalizedAbsolute.startsWith(normalizedProjDir)) {
            normalizedAbsolute.removePrefix(normalizedProjDir).removePrefix("/")
        } else {
            // Fallback to just the filename if not under project dir
            absolutePath.substringAfterLast('/').substringAfterLast('\\')
        }
    }

    /**
     * Builds the full GitHub URL for source linking.
     */
    private fun buildGitHubSourceUrl(relativeFilePath: String?, lineNumber: Int?): String? {
        if (githubRepoUrl.isNullOrEmpty() || relativeFilePath == null || lineNumber == null) {
            return null
        }
        return "$githubRepoUrl/blob/$githubBranch/$relativeFilePath#L$lineNumber"
    }

    private fun isModuleLogicSubclassRecursive(irClass: IrClass, visited: MutableSet<FqName>): Boolean {
        val classFqName = irClass.fqNameWhenAvailable
        if (classFqName != null && !visited.add(classFqName)) {
            return false // Already visited, avoid cycles
        }

        // Check direct match
        if (classFqName == moduleLogicFqName) {
            return true
        }

        // Check supertypes - look for ModuleLogic anywhere in hierarchy
        for (superType in irClass.superTypes) {
            val superFqName = superType.classFqName

            // Direct match with ModuleLogic
            if (superFqName == moduleLogicFqName) {
                return true
            }

            // Check if supertype name contains "ModuleLogic" (fallback for generics)
            if (superFqName?.asString()?.contains("ModuleLogic") == true) {
                return true
            }

            // Recurse into superclass
            val superClass = superType.classOrNull?.owner
            if (superClass != null && isModuleLogicSubclassRecursive(superClass, visited)) {
                return true
            }
        }

        return false
    }

    /**
     * Transformer that intercepts return statements and injects completion notification.
     */
    private inner class ReturnTransformer(
        private val pluginContext: IrPluginContext,
        private val targetFunction: IrFunction,
        private val tracerClass: IrClassSymbol,
        private val completedFun: IrSimpleFunctionSymbol,
        private val callIdVar: IrVariable,
        private val startTimeVar: IrVariable,
        private val returnType: IrType,
        private val methodName: String
    ) : IrElementTransformerVoid() {

        override fun visitTry(expression: IrTry): IrExpression {
            messageCollector.report(
                org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.INFO,
                "ReaktivTracing: $methodName - visiting IrTry block"
            )
            return super.visitTry(expression)
        }

        override fun visitReturn(expression: IrReturn): IrExpression {
            messageCollector.report(
                org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.INFO,
                "ReaktivTracing: $methodName - visitReturn called, target: ${expression.returnTargetSymbol}, our target: ${targetFunction.symbol}"
            )

            // Only transform returns from our target function
            if (expression.returnTargetSymbol != targetFunction.symbol) {
                messageCollector.report(
                    org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.INFO,
                    "ReaktivTracing: $methodName - return target mismatch, not transforming"
                )
                return super.visitReturn(expression)
            }

            messageCollector.report(
                org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.INFO,
                "ReaktivTracing: $methodName - TRANSFORMING return statement!"
            )

            val builder = DeclarationIrBuilder(pluginContext, targetFunction.symbol)
            val completedValueParamOffset = if (completedFun.owner.dispatchReceiverParameter != null) 1 else 0

            return builder.irBlock(resultType = irBuiltIns.nothingType) {
                // Capture the return value
                val returnValue = expression.value.transform(this@ReturnTransformer, null)
                val resultTmp = irTemporary(
                    value = returnValue,
                    nameHint = "tracing_returnResult"
                )

                // Call notifyMethodCompleted
                +irCall(completedFun).apply {
                    dispatchReceiver = irGetObject(tracerClass)
                    arguments[completedValueParamOffset + 0] = irGet(callIdVar)
                    arguments[completedValueParamOffset + 1] = irToStringSafe(irGet(resultTmp))
                    arguments[completedValueParamOffset + 2] = irString(returnType.classFqName?.shortName()?.asString() ?: "Unknown")
                    arguments[completedValueParamOffset + 3] = irComputeDuration(startTimeVar)
                }

                // Return the captured value
                +IrReturnImpl(
                    startOffset = expression.startOffset,
                    endOffset = expression.endOffset,
                    type = irBuiltIns.nothingType,
                    returnTargetSymbol = expression.returnTargetSymbol,
                    value = irGet(resultTmp)
                )
            }
        }

        private fun IrBuilderWithScope.irToStringSafe(value: IrExpression): IrExpression {
            val toStringFun = irBuiltIns.anyClass.owner.functions.find { fn ->
                fn.name.asString() == "toString" &&
                fn.parameters.none { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular }
            }?.symbol

            return if (toStringFun != null) {
                irCall(toStringFun).apply {
                    dispatchReceiver = value
                }
            } else {
                irString("<unknown>")
            }
        }

        private fun IrBuilderWithScope.irComputeDuration(startTimeVar: IrVariable): IrExpression {
            val timeFun = getTimeMillisFun
            if (timeFun == null) {
                return irLong(0L)
            }

            val currentTime = irCall(timeFun)
            val startTime = irGet(startTimeVar)

            // Find Long.minus(other: Long) - 1 regular parameter, dispatch receiver is separate
            val minusFun = irBuiltIns.longClass.owner.functions.firstOrNull { fn ->
                fn.name.asString() == "minus" &&
                fn.parameters.count { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.Regular } == 1 &&
                fn.parameters.any { it.type.isLong() }
            }

            return if (minusFun != null) {
                irCall(minusFun.symbol).apply {
                    dispatchReceiver = currentTime
                    val paramOffset = if (minusFun.dispatchReceiverParameter != null) 1 else 0
                    arguments[paramOffset] = startTime
                }
            } else {
                irLong(0L)
            }
        }
    }
}
