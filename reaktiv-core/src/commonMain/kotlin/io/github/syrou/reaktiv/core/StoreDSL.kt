@file:JvmName("StoreKt")

package io.github.syrou.reaktiv.core

import io.github.syrou.reaktiv.core.persistance.PersistenceManager
import io.github.syrou.reaktiv.core.persistance.PersistenceStrategy
import io.github.syrou.reaktiv.core.util.CustomTypeRegistrar
import io.github.syrou.reaktiv.core.util.reaktivJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.modules.PolymorphicModuleBuilder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.serializer
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmName
import kotlin.reflect.KClass

public class StoreDSL {
    private var coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val middlewares = mutableListOf<Middleware>()
    private val modules: MutableList<Module<ModuleState, ModuleAction>> = mutableListOf()
    private var persistenceStrategy: PersistenceStrategy? = null
    private val moduleStateRegistrations = mutableMapOf<String, (PolymorphicModuleBuilder<ModuleState>) -> Unit>()
    private val customTypeRegistrars = mutableListOf<CustomTypeRegistrar>()

    @OptIn(InternalSerializationApi::class)

    public fun <S : ModuleState, A : ModuleAction> module(
        stateClass: KClass<S>,
        module: Module<S, A>
    ) {
        val stateClassName = module.initialState::class.qualifiedName
            ?: throw IllegalArgumentException("Module state class must have a qualified name")

        if (moduleStateRegistrations.containsKey(stateClassName)) {
            throw IllegalArgumentException(
                "Duplicate module state registration detected: $stateClassName. " +
                        "Each state class can only be registered once. " +
                        "Check that you're not adding the same module multiple times or using the same state class in different modules."
            )
        }

        @Suppress("UNCHECKED_CAST")
        modules.add(module as Module<ModuleState, ModuleAction>)
        moduleStateRegistrations[stateClassName] = { builder ->
            @Suppress("UNCHECKED_CAST")
            val actualStateClass = module.initialState::class as KClass<S>
            builder.subclass(actualStateClass, actualStateClass.serializer())
        }

        if (module is CustomTypeRegistrar) {
            customTypeRegistrars.add(module)
        }
    }


    public inline fun <reified S : ModuleState, A : ModuleAction> module(module: Module<S, A>) {
        module(S::class, module)
    }

    @JvmName("moduleErased")
    public fun module(module: Module<*, *>) {
        @Suppress("UNCHECKED_CAST")
        module(module.initialState::class as KClass<ModuleState>, module as Module<ModuleState, ModuleAction>)
    }


    public fun middlewares(vararg newMiddlewares: Middleware) {
        middlewares.addAll(newMiddlewares)
    }


    public fun coroutineContext(context: CoroutineContext) {
        coroutineScope = CoroutineScope(SupervisorJob() + context.minusKey(Job))
    }


    public fun persistenceManager(persistenceStrategy: PersistenceStrategy) {
        this.persistenceStrategy = persistenceStrategy
    }

    internal fun build(): Store {
        val serializersModule = SerializersModule {
            polymorphic(ModuleState::class) {
                moduleStateRegistrations.values.forEach { it(this) }
            }
            customTypeRegistrars.forEach { registrar ->
                registrar.registerAdditionalSerializers(this)
            }
        }

        val persistenceManager = persistenceStrategy?.let {
            PersistenceManager(
                json = reaktivJson(serializersModule),
                persistenceStrategy = it
            )
        }

        // Combine explicit middlewares with module-provided middlewares
        // Explicit middlewares run first (outer), module middlewares run after (inner/closer to reducer)
        val moduleMiddlewares = modules.mapNotNull { it.createMiddleware?.invoke() }
        val allMiddlewares = middlewares + moduleMiddlewares

        return Store.create(coroutineScope, allMiddlewares, modules, persistenceManager, serializersModule)
    }
}


/**
 * Creates a new Store instance using the DSL builder.
 *
 * The store is the central piece of the Reaktiv architecture. It manages state,
 * handles actions, and coordinates between different modules.
 *
 * Example:
 * ```kotlin
 * val store = createStore {
 *     module(CounterModule)
 *     module(UserModule)
 *     module(navigationModule)
 *
 *     middlewares(loggingMiddleware, analyticsMiddleware)
 *     coroutineContext(Dispatchers.Default)
 *     persistenceManager(PlatformPersistenceStrategy())
 * }
 * ```
 *
 * @param block DSL block for configuring the store
 * @return The configured Store instance
 */
public fun createStore(block: StoreDSL.() -> Unit): Store {
    val dsl = StoreDSL().apply(block)
    return dsl.build()
}
