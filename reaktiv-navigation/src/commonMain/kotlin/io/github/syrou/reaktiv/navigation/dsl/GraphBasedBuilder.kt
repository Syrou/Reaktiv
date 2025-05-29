package io.github.syrou.reaktiv.navigation.dsl

import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GraphBasedBuilder {
    private var rootGraph: NavigationGraph? = null
    private var coroutineContext = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun rootGraph(block: NavigationGraphBuilder.() -> Unit) {
        val builder = NavigationGraphBuilder("root")
        builder.apply(block)
        rootGraph = builder.build()
    }

    fun coroutineContext(dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
        coroutineContext = CoroutineScope(dispatcher)
    }

    

    fun build(): NavigationModule {
        requireNotNull(rootGraph) { "Root graph must be defined" }
        return NavigationModule(
            coroutineScope = coroutineContext,
            rootGraph = rootGraph!!
        )
    }

    
    fun getRootGraph(): NavigationGraph? = rootGraph
}