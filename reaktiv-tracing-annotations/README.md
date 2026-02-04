# Reaktiv Tracing Annotations

Marker annotations for controlling automatic logic method tracing in Reaktiv.

## Installation

```kotlin
dependencies {
    implementation("io.github.syrou:reaktiv-tracing-annotations:<version>")
}
```

Note: This dependency is automatically added when using the `reaktiv-tracing` Gradle plugin.

## Annotations

### @NoTrace

Excludes a method from automatic tracing.

```kotlin
class MyLogic(storeAccessor: StoreAccessor) : ModuleLogic<MyAction>() {

    @NoTrace
    suspend fun internalHelper() {
        // This method won't be traced
    }
}
```

### @Sensitive

Marks a parameter as sensitive. The value will be obfuscated in trace output.

```kotlin
suspend fun login(
    username: String,
    @Sensitive password: String
) {
    // password will appear as "***" in DevTools
}
```

### @PII

Marks a parameter as Personally Identifiable Information. The value will be obfuscated in trace output.

```kotlin
suspend fun updateProfile(
    @PII email: String,
    @PII phoneNumber: String,
    displayName: String
) {
    // email and phoneNumber will be obfuscated
}
```

## See Also

- [Tracing Gradle Plugin](../reaktiv-tracing-gradle/README.md) - Setup and configuration
- [DevTools](../reaktiv-devtools/README.md) - View traces in the DevTools UI
