# Reaktiv Tracing Compiler

Kotlin compiler plugin that automatically instruments `ModuleLogic` methods with tracing calls.

## Overview

This compiler plugin transforms `ModuleLogic` subclasses at compile time to add tracing instrumentation. When a traced method is called, it reports:

- Method name and class
- Parameter values (with obfuscation for `@Sensitive`/`@PII` parameters)
- Return value and type
- Execution duration
- Source file location with GitHub link support

## How It Works

The plugin uses Kotlin's IR (Intermediate Representation) transformation API to wrap method bodies with tracing calls:

```kotlin
// Original code
suspend fun fetchUser(id: String): User {
    return api.getUser(id)
}

// Transformed (conceptually)
suspend fun fetchUser(id: String): User {
    val callId = LogicTracer.notifyMethodStart("MyLogic", "fetchUser", mapOf("id" to id), ...)
    val startTime = Clock.System.now()
    try {
        val result = api.getUser(id)
        LogicTracer.notifyMethodCompleted(callId, result, "User", duration)
        return result
    } catch (e: Throwable) {
        LogicTracer.notifyMethodFailed(callId, e, duration)
        throw e
    }
}
```

## Installation

This module is not used directly. Use the [Gradle plugin](../reaktiv-tracing-gradle/README.md) instead:

```kotlin
plugins {
    id("io.github.syrou.reaktiv.tracing") version "<version>"
}
```

## Configuration Options

Options are passed via the Gradle plugin:

| Option | Description | Default |
|--------|-------------|---------|
| `enabled` | Enable/disable tracing | `true` |
| `tracePrivateMethods` | Include private methods | `false` |
| `githubRepoUrl` | GitHub URL for source links | Auto-detected |
| `githubBranch` | Git branch for source links | Auto-detected |

## See Also

- [Tracing Gradle Plugin](../reaktiv-tracing-gradle/README.md) - Setup and configuration
- [Tracing Annotations](../reaktiv-tracing-annotations/README.md) - Control tracing behavior
