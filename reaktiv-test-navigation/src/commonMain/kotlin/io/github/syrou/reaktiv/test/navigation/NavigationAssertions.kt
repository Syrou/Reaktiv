package io.github.syrou.reaktiv.test.navigation

import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.model.NavigationGuard
import io.github.syrou.reaktiv.test.ReaktivTestScope
import kotlinx.coroutines.flow.first

public suspend fun ReaktivTestScope.assertCurrentRoute(expected: String) {
    settle()
    val actual = store.selectState<NavigationState>().first().currentEntry.route
    if (actual != expected) {
        throw AssertionError("Expected current route '$expected' but was '$actual'")
    }
}

public suspend fun ReaktivTestScope.assertCurrentPath(expected: String) {
    settle()
    val actual = store.selectState<NavigationState>().first().currentEntry.path
    if (actual != expected) {
        throw AssertionError("Expected current path '$expected' but was '$actual'")
    }
}

public suspend fun ReaktivTestScope.assertBackStack(vararg routes: String) {
    settle()
    val actual = store.selectState<NavigationState>().first().backStack.map { it.route }
    if (actual != routes.toList()) {
        throw AssertionError("Expected back stack ${routes.toList()} but was $actual")
    }
}

public suspend fun ReaktivTestScope.awaitRoute(route: String): NavigationState =
    store.selectState<NavigationState>().first { it.currentEntry.route == route }

public suspend fun ReaktivTestScope.evaluateGuard(guard: NavigationGuard): GuardResult {
    settle()
    return guard(store)
}
