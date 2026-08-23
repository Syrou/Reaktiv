package io.github.syrou.reaktiv.tracing.gradle

/**
 * What to do when one invocation requests both a tracing build and a non-tracing one.
 *
 * A module with a single compilation per target produces one artifact per invocation, so it cannot
 * hand instrumented bytecode to one consumer and clean bytecode to another. `./gradlew
 * assembleProduction assembleStaging` is exactly that situation, and it is a normal thing for CI to
 * do, so the plugin has to resolve it rather than merely detect it.
 */
enum class TracingConflictPolicy {
    /**
     * Compile without instrumentation, so the non-tracing artifact is correct and the tracing one
     * simply lacks traces. The safe resolution, and the default: a release build never ships
     * instrumentation because of how someone invoked Gradle.
     */
    DISABLE,

    /**
     * Fail the build and name both sides. Use when every staging artifact must carry traces and you
     * would rather split the invocation than get one without them.
     */
    FAIL,

    /**
     * Instrument anyway, so both artifacts carry traces. Only for builds where the non-tracing
     * artifact is not shipped, since it puts instrumentation into a release binary.
     */
    INSTRUMENT_ALL
}
