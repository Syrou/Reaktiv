@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package io.github.syrou.reaktiv.tracing.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.isLong
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal class RuntimeSymbols(
    private val context: IrPluginContext,
    private val messageCollector: MessageCollector
) {

    class Tracer(
        val owner: IrClassSymbol,
        val active: IrSimpleFunctionSymbol,
        val start: IrSimpleFunctionSymbol,
        val completed: IrSimpleFunctionSymbol,
        val failed: IrSimpleFunctionSymbol,
        val currentTimeMillis: IrSimpleFunctionSymbol
    )

    class Tracker(val owner: IrClassSymbol, val function: IrSimpleFunctionSymbol)

    private val reported = mutableSetOf<String>()

    val moduleLogic: IrClassSymbol? by lazy { findClass(CORE_PACKAGE, "ModuleLogic") }

    val moduleAction: IrClassSymbol? by lazy { findClass(CORE_PACKAGE, "ModuleAction") }

    val tracer: Tracer? by lazy {
        val owner = requireClass(TRACING_PACKAGE, "LogicTracer") ?: return@lazy null
        val active = owner.owner.properties.firstOrNull { it.name.asString() == "active" }?.getter?.symbol
            ?: return@lazy mismatch("LogicTracer.active")
        Tracer(
            owner = owner,
            active = active,
            start = requireFunction(
                owner, "notifyMethodStart",
                listOf("logicClass", "methodName", "params", "sourceFile", "lineNumber", "githubSourceUrl", "redactions")
            ) ?: return@lazy null,
            completed = requireFunction(owner, "notifyMethodCompleted", listOf("callId", "result", "resultType", "durationMs"))
                ?: return@lazy null,
            failed = requireFunction(owner, "notifyMethodFailed", listOf("callId", "exception", "durationMs"))
                ?: return@lazy null,
            currentTimeMillis = context.finderForBuiltins()
                .findFunctions(CallableId(UTIL_PACKAGE, Name.identifier("currentTimeMillis")))
                .firstOrNull { it.owner.regularParameters().isEmpty() }
                ?: return@lazy missing("${UTIL_PACKAGE}.currentTimeMillis", "reaktiv-core")
        )
    }

    val dispatchOrigin: Tracker? by lazy { tracker("DispatchOriginTracker", "record", listOf("action", "origin")) }

    val stateRead: Tracker? by lazy { tracker("StateReadTracker", "notifyStateRead", listOf("stateClass", "composable")) }

    val mutableMapOf: IrSimpleFunctionSymbol by lazy {
        context.finderForBuiltins().findFunctions(CallableId(COLLECTIONS_PACKAGE, Name.identifier("mutableMapOf")))
            .first { it.owner.regularParameters().isEmpty() }
    }

    val emptyMap: IrSimpleFunctionSymbol by lazy {
        context.finderForBuiltins().findFunctions(CallableId(COLLECTIONS_PACKAGE, Name.identifier("emptyMap"))).first()
    }

    val mapPut: IrSimpleFunctionSymbol by lazy {
        context.finderForBuiltins().findClass(ClassId(COLLECTIONS_PACKAGE, Name.identifier("MutableMap")))!!
            .owner.functions.first { it.name.asString() == "put" && it.regularParameters().size == 2 }.symbol
    }

    val longMinus: IrSimpleFunctionSymbol by lazy {
        context.irBuiltIns.longClass.owner.functions
            .first { it.name.asString() == "minus" && it.regularParameters().singleOrNull()?.type?.isLong() == true }
            .symbol
    }

    val anyToString: IrSimpleFunctionSymbol by lazy {
        context.irBuiltIns.anyClass.owner.functions
            .first { it.name.asString() == "toString" && it.regularParameters().isEmpty() }
            .symbol
    }

    private fun tracker(className: String, functionName: String, parameters: List<String>): Tracker? {
        val owner = requireClass(TRACING_PACKAGE, className) ?: return null
        val function = requireFunction(owner, functionName, parameters) ?: return null
        return Tracker(owner, function)
    }

    private fun findClass(packageName: FqName, name: String): IrClassSymbol? =
        context.finderForBuiltins().findClass(ClassId(packageName, Name.identifier(name)))

    private fun requireClass(packageName: FqName, name: String): IrClassSymbol? =
        findClass(packageName, name) ?: missing("$packageName.$name", "reaktiv-tracing-runtime")

    private fun requireFunction(owner: IrClassSymbol, name: String, parameters: List<String>): IrSimpleFunctionSymbol? {
        val function = owner.owner.functions.firstOrNull { it.name.asString() == name }
            ?: return mismatch("${owner.owner.name}.$name")
        return function.symbol.takeIf { function.accepts(parameters) }
            ?: mismatch("${owner.owner.name}.$name(${parameters.joinToString()})")
    }

    private fun IrSimpleFunction.accepts(parameters: List<String>): Boolean {
        val declared = regularParameters()
        val names = declared.map { it.name.asString() }
        return names.containsAll(parameters) &&
            declared.all { it.name.asString() in parameters || it.defaultValue != null }
    }

    private fun <T> missing(symbol: String, artifact: String): T? {
        report(
            symbol,
            "ReaktivTracing: $symbol is not on the compile classpath, so nothing in this module can be traced. " +
                "Add io.github.syrou:$artifact at the version of the tracing plugin (the Gradle plugin adds it)."
        )
        return null
    }

    private fun <T> mismatch(symbol: String): T? {
        report(
            symbol,
            "ReaktivTracing: the tracing runtime on the classpath has no $symbol, so it does not match this " +
                "compiler plugin. Use io.github.syrou:reaktiv-tracing-runtime at the version of the tracing plugin."
        )
        return null
    }

    private fun report(key: String, message: String) {
        if (reported.add(key)) messageCollector.error { message }
    }

    private companion object {
        val CORE_PACKAGE = FqName("io.github.syrou.reaktiv.core")
        val UTIL_PACKAGE = FqName("io.github.syrou.reaktiv.core.util")
        val TRACING_PACKAGE = FqName("io.github.syrou.reaktiv.core.tracing")
        val COLLECTIONS_PACKAGE = FqName("kotlin.collections")
    }
}
