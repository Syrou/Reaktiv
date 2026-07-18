# Module reaktiv-test-navigation

Navigation assertions for the Reaktiv test kit. A companion to reaktiv-test
that adds route and back stack checks plus a guard harness, packaged separately
because reaktiv-navigation only targets the Compose platforms while
reaktiv-test covers every core target.

## Setup

```kotlin
kotlin {
    sourceSets {
        commonTest {
            dependencies {
                implementation("io.github.syrou:reaktiv-test-navigation:<version>")
            }
        }
    }
}
```

## Usage

```kotlin
@Test
fun `login flow lands on home`() = reaktivTest(AuthModule, navigationModule) {
    store.navigation { navigateTo("workspace/home") }
    assertCurrentRoute("home")
    assertBackStack("start", "home")
}

@Test
fun `auth guard rejects logged out users`() = reaktivTest(AuthModule, navigationModule) {
    assertEquals(GuardResult.Reject, evaluateGuard(requireAuth))
    dispatch(AuthAction.Login)
    assertEquals(GuardResult.Allow, evaluateGuard(requireAuth))
}
```

Provides `assertCurrentRoute`, `assertCurrentPath`, `assertBackStack`,
`awaitRoute` (suspends until navigation lands) and `evaluateGuard` (runs a
`NavigationGuard` directly against the test store).
