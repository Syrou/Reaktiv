# Module reaktiv-test

Test kit for Reaktiv stores. Wires a store to the kotlinx-coroutines test
scheduler so dispatches settle deterministically, records every dispatched
action for assertions, and exposes state helpers that never race side effects.

## Setup

```kotlin
kotlin {
    sourceSets {
        commonTest {
            dependencies {
                implementation("io.github.syrou:reaktiv-test:<version>")
            }
        }
    }
}
```

## Usage

```kotlin
@Test
fun `login updates auth state`() = reaktivTest(AuthModule) {
    dispatch(AuthAction.Login)
    assertTrue(currentState<AuthState>().isAuthenticated)
}

@Test
fun `sync schedules a follow-up save`() = reaktivTest(AuthModule, SyncModule) {
    dispatch(SyncAction.Start)
    assertDispatched<SyncAction.Persist>()
}

@Test
fun `debounce fires only after the window`() = reaktivTest(SearchModule) {
    store.dispatch(SearchAction.Query("rea"))
    advanceTimeBy(200.milliseconds)
    assertNotDispatched<SearchAction.Execute>()
    advanceTimeBy(300.milliseconds)
    assertDispatched<SearchAction.Execute>()
}
```

`reaktivTest` accepts modules as varargs, an optional `configure` block for the
full store DSL (middlewares, persistence), and runs the body in a
`ReaktivTestScope` with `dispatch` (settles automatically), `settle`,
`advanceTimeBy`, `currentState`, `awaitState`, `assertDispatched`,
`assertNotDispatched` and direct `store` access.
