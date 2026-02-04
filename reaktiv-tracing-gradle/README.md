# Reaktiv Tracing Gradle Plugin

Gradle plugin for automatic logic method tracing in Reaktiv applications.

## Installation

```kotlin
plugins {
    id("io.github.syrou.reaktiv.tracing") version "<version>"
}
```

The plugin automatically adds the required dependencies (`reaktiv-tracing-annotations` and `reaktiv-tracing-compiler`).

## Configuration

```kotlin
reaktivTracing {
    // Enable or disable tracing (default: true)
    enabled.set(true)

    // Trace private methods in addition to public (default: false)
    tracePrivateMethods.set(false)

    // Limit to specific build types (default: empty = all build types)
    buildTypes.set(setOf("staging", "debug"))

    // GitHub source linking (auto-detected from git)
    // Override if needed:
    // githubRepoUrl.set("https://github.com/owner/repo")
    // githubBranch.set("main")
}
```

## Build Type Filtering

For Android projects, you can limit tracing to specific build types:

```kotlin
reaktivTracing {
    enabled.set(true)
    buildTypes.set(setOf("staging"))  // Only trace staging builds
}
```

This keeps release builds clean without any tracing overhead.

## GitHub Source Linking

The plugin automatically detects your GitHub repository URL and current branch from git. When viewing traces in DevTools, source locations become clickable links to GitHub.

Auto-detection works when:
- Your project is a git repository
- The remote `origin` points to GitHub

To override or set manually:

```kotlin
reaktivTracing {
    githubRepoUrl.set("https://github.com/your-org/your-repo")
    githubBranch.set("develop")
}
```

## What Gets Traced

By default, the plugin traces:
- All `public` and `internal` suspend methods in `ModuleLogic` subclasses
- Methods not annotated with `@NoTrace`

With `tracePrivateMethods.set(true)`:
- Also traces `private` suspend methods

## Viewing Traces

Traces appear in the Reaktiv DevTools UI:
1. Connect your app to DevTools
2. Open the "Logic" tab
3. See method calls with parameters, return values, and timing

## Example Output in DevTools

```
MyLogic.fetchUser(id="123")
  -> User(name="John") [45ms]

AuthLogic.login(username="john", password="***")
  -> LoginResult.Success [230ms]
```

## See Also

- [Tracing Annotations](../reaktiv-tracing-annotations/README.md) - `@NoTrace`, `@Sensitive`, `@PII`
- [Tracing Compiler](../reaktiv-tracing-compiler/README.md) - How the transformation works
- [DevTools](../reaktiv-devtools/README.md) - Viewing traces
