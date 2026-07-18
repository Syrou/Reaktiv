# Attaching Reaktiv tooling on iOS (Debug configuration only)

KMP has no Android-style variant source sets, so the compile-time switch is a
Gradle property bound to the Xcode CONFIGURATION, selecting both the
dependencies and which of two seam source dirs compiles.

## Shared module build.gradle.kts

```kotlin
val withTooling = providers.gradleProperty("reaktiv.tooling").orNull == "true"

kotlin.sourceSets.getByName("iosMain") {
    kotlin.srcDir(if (withTooling) "src/iosToolingOn/kotlin" else "src/iosToolingOff/kotlin")
    dependencies {
        if (withTooling) {
            implementation("io.github.syrou:reaktiv-introspection:$reaktivVersion")
            implementation("io.github.syrou:reaktiv-devtools:$reaktivVersion")
        }
    }
}
```

## Xcode build phase

```bash
./gradlew :shared:embedAndSignAppleFrameworkForXcode \
  -Preaktiv.tooling=$([ "$CONFIGURATION" = "Debug" ] && echo true || echo false)
```

Both source dirs define the same seam, `fun toolingModule(): Module<*, *>?`
(real implementation vs `= null`). Swift `#if DEBUG` is only a usage guard for
debug-only UI affordances; absence from the release framework is guaranteed by
the per-configuration build.

## Crash file access for users

Session files are written to the app's Documents directory. For users to reach
them in the Files app, Info.plist must declare:

- `UIFileSharingEnabled` = YES
- `LSSupportsOpeningDocumentsInPlace` = YES

The recommended primary route is a share-sheet button in the app calling
`exportSessionToDownloads()` and handing the returned path to
`UIActivityViewController`, which needs no plist keys.
