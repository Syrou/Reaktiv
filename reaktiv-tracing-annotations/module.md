# Module reaktiv-tracing-annotations

Marker annotations for controlling automatic logic method tracing in Reaktiv. These
annotations are processed by the `reaktiv-tracing-compiler` Kotlin compiler plugin, which
instruments `ModuleLogic` methods so that [DevTools] can display method calls, parameters,
execution duration, and return values in real time.

## Setup

This dependency is added automatically when you apply the `reaktiv-tracing` Gradle plugin.
You only need to declare it manually if you want to annotate code in a module that does not
apply the plugin itself.

```kotlin
// build.gradle.kts
plugins {
    id("io.github.syrou.reaktiv.tracing") version "<version>"
}

// Manual dependency (only if not using the plugin)
dependencies {
    implementation("io.github.syrou:reaktiv-tracing-annotations:<version>")
}
```

---

## @NoTrace — Exclude a Method

By default the compiler plugin instruments every public `suspend fun` in classes that
extend `ModuleLogic`. Use `@NoTrace` to exclude helpers, utility methods, or methods
that produce excessive noise in the trace view.

```kotlin
class AuthLogic(val storeAccessor: StoreAccessor) : ModuleLogic() {

    suspend fun login(username: String, password: String) {
        // This method IS traced (parameters visible in DevTools)
        val token = api.authenticate(username, password)
        storeAccessor.dispatch(AuthAction.LoggedIn(token))
    }

    @NoTrace
    suspend fun refreshTokenIfNeeded() {
        // Internal helper — excluded from the trace stream
        if (tokenExpiresSoon()) refreshToken()
    }
}
```

---

## @Sensitive — Obfuscate a Parameter Value

Apply `@Sensitive` to parameters that must not appear in plain text in the DevTools UI
(passwords, secret keys, tokens). The value is replaced with `"***"` in the trace output.

```kotlin
suspend fun login(
    username: String,
    @Sensitive password: String
) {
    api.authenticate(username, password)
}
```

Trace output in DevTools:
```
login(username="alice", password="***")
```

---

## @PII — Obfuscate Personally Identifiable Information

Apply `@PII` to parameters that contain personal data (email addresses, phone numbers,
national IDs). Like `@Sensitive`, the value is replaced with `"***"` in trace output.

```kotlin
suspend fun updateProfile(
    @PII email: String,
    @PII phoneNumber: String,
    displayName: String
) {
    api.updateProfile(email, phoneNumber, displayName)
}
```

Trace output in DevTools:
```
updateProfile(email="***", phoneNumber="***", displayName="Alice")
```

---

## Combining Annotations

```kotlin
class PaymentLogic(val storeAccessor: StoreAccessor) : ModuleLogic() {

    suspend fun processPayment(
        orderId: String,
        @Sensitive cardNumber: String,
        @PII billingEmail: String,
        amount: Double
    ) {
        // orderId and amount are visible; cardNumber and billingEmail are obfuscated
    }

    @NoTrace
    suspend fun buildPaymentPayload(cardNumber: String): Payload {
        // Never appears in the trace stream
        return Payload(cardNumber)
    }
}
```

---

## Key Annotations

- `@NoTrace` — exclude the annotated method entirely from the trace stream
- `@Sensitive` — obfuscate the annotated parameter value with `"***"` in DevTools
- `@PII` — obfuscate personally identifiable information with `"***"` in DevTools
