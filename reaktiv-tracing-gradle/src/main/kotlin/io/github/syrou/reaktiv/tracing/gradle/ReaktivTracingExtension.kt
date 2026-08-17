package io.github.syrou.reaktiv.tracing.gradle

import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/**
 * Extension for configuring the Reaktiv tracing compiler plugin.
 *
 * Tracing is off unless the build asks for it. Whether a given compilation is instrumented is a
 * property of the build being run, not of the module that holds the Logic classes, so a library
 * module declares which builds count as tracing builds and the plugin resolves the rest.
 *
 * A module consumed by an Android app with a staging build type, and by an iOS app that builds its
 * framework through an Xcode Run Script phase:
 * ```kotlin
 * reaktivTracing {
 *     enableForTasksMatching("staging")
 *     conflictsWithTasksMatching("production")
 *     enableForXcodeConfigurations("Debug")
 *     tracePrivateMethods.set(true)
 * }
 * ```
 *
 * An application module that has real build types of its own:
 * ```kotlin
 * reaktivTracing {
 *     buildTypes.set(setOf("debug"))
 * }
 * ```
 *
 * Taking the decision over directly, when the defaults do not fit:
 * ```kotlin
 * reaktivTracing {
 *     enabled.set(providers.gradleProperty("myproject.tracing").map { it.toBoolean() }.orElse(false))
 * }
 * ```
 *
 * @see ReaktivTracingGradlePlugin for how activation is resolved
 */
interface ReaktivTracingExtension {
    /**
     * Whether the compiler plugin instruments this project at all.
     *
     * Left alone, this resolves to true when any of [xcodeConfigurations], [activatingTaskPatterns]
     * or [buildTypes] matches the current build, and false otherwise.
     *
     * Setting it explicitly is the escape hatch: it overrides every criterion below, bypasses the
     * [conflictsWithTasksMatching] guard, and takes full control of the decision. Reach for it when
     * the declarative criteria cannot express a build, or misjudge one.
     * ```kotlin
     * reaktivTracing {
     *     enabled.set(providers.environmentVariable("MY_CI_TRACING").map { it == "on" }.orElse(false))
     * }
     * ```
     *
     * Default: derived from the declared criteria, false when none are declared
     */
    val enabled: Property<Boolean>

    /**
     * Whether to trace private methods in addition to public methods.
     * Default: false
     */
    val tracePrivateMethods: Property<Boolean>

    /**
     * Build types to apply tracing to, matched against the compilation name.
     *
     * Declaring any build type also activates tracing, so a module with real variants needs nothing
     * else. If empty, tracing is applied to all compilations once activated by another criterion.
     * Example: setOf("staging") or setOf("debug", "staging")
     *
     * Default: empty
     */
    val buildTypes: SetProperty<String>

    /**
     * Substrings that activate tracing when they appear in a requested task name.
     *
     * Prefer [enableForTasksMatching] over setting this directly.
     *
     * Default: empty
     */
    val activatingTaskPatterns: SetProperty<String>

    /**
     * Substrings that must not appear in the requested task names when tracing activates.
     *
     * Prefer [conflictsWithTasksMatching] over setting this directly.
     *
     * Default: empty
     */
    val conflictingTaskPatterns: SetProperty<String>

    /**
     * Xcode configuration names that activate tracing, compared against the `CONFIGURATION`
     * environment variable Xcode exports into a Run Script build phase.
     *
     * Prefer [enableForXcodeConfigurations] over setting this directly.
     *
     * Default: empty
     */
    val xcodeConfigurations: SetProperty<String>

    /**
     * Activates tracing when any requested task name contains one of [patterns], compared
     * case-insensitively.
     *
     * This is the Android-side signal. A module with no build types of its own cannot see which
     * variant its consumer is building, so it keys on the invocation instead.
     *
     * Usage:
     * ```kotlin
     * reaktivTracing {
     *     enableForTasksMatching("staging")
     * }
     * ```
     *
     * With that declaration, `./gradlew :app:assembleStaging` instruments this module and
     * `./gradlew :app:assembleProduction` does not.
     *
     * @param patterns Substrings to look for in the requested task names
     * @see conflictsWithTasksMatching to guard invocations that request both
     */
    fun enableForTasksMatching(vararg patterns: String) {
        activatingTaskPatterns.addAll(*patterns)
    }

    /**
     * Fails the build when tracing activates in an invocation that also requests a task matching one
     * of [patterns].
     *
     * A module with a single compilation per target produces one artifact per invocation, so it
     * cannot hand an instrumented build to one consumer and a clean one to another. Without this
     * guard, `./gradlew testStagingUnitTest assembleProduction` quietly feeds instrumented code to
     * the production consumer.
     *
     * Usage:
     * ```kotlin
     * reaktivTracing {
     *     enableForTasksMatching("staging")
     *     conflictsWithTasksMatching("production")
     * }
     * ```
     *
     * The guard reports a configuration-time failure naming both sides. It does not make the mixed
     * invocation work, which would require per-consumer variant selection.
     *
     * @param patterns Substrings that must not co-occur with an activating task
     */
    fun conflictsWithTasksMatching(vararg patterns: String) {
        conflictingTaskPatterns.addAll(*patterns)
    }

    /**
     * Activates tracing when Xcode's `CONFIGURATION` matches one of [configurations], compared
     * case-insensitively.
     *
     * This is the Apple-side signal. Xcode exports its build settings as environment variables into
     * the Run Script phase that calls Gradle, so one Xcode build is one Gradle invocation carrying
     * one configuration, and the framework is built from instrumented or clean sources accordingly.
     *
     * Usage:
     * ```kotlin
     * reaktivTracing {
     *     enableForXcodeConfigurations("Debug")
     * }
     * ```
     *
     * Names are your Xcode configuration names, so a project with a Staging configuration passes
     * `enableForXcodeConfigurations("Debug", "Staging")`. This has no effect when the iOS app
     * consumes a prebuilt XCFramework, since Xcode never invokes Gradle in that setup and activation
     * belongs to the job that publishes the framework.
     *
     * @param configurations Xcode configuration names that should be traced
     */
    fun enableForXcodeConfigurations(vararg configurations: String) {
        xcodeConfigurations.addAll(*configurations)
    }

    /**
     * GitHub repository URL for source linking in DevTools.
     * Auto-detected from git remote if not set.
     * Example: "https://github.com/owner/repo"
     */
    val githubRepoUrl: Property<String>

    /**
     * Git branch for source linking in DevTools.
     * Auto-detected from current branch if not set.
     * Example: "main" or "develop"
     */
    val githubBranch: Property<String>
}
