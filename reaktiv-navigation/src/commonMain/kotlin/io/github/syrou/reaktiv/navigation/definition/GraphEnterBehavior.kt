package io.github.syrou.reaktiv.navigation.definition

import io.github.syrou.reaktiv.navigation.GraphState

sealed class GraphEnterBehavior {
    object StartAtRoot : GraphEnterBehavior()
    object ResumeOrStart : GraphEnterBehavior()
    data class Custom(val determineScreen: (GraphState?) -> Screen) : GraphEnterBehavior()
}
