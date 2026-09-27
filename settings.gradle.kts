pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        google()
    }

    includeBuild("convention-plugins")
    includeBuild("reaktiv-tracing-gradle")
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "reaktiv"
include("reaktiv-core")
include(":androidexample")
include(":webexample")
include(":example-shared")
include(":example-tooling")
include("reaktiv-compose")
include("reaktiv-navigation")
include("reaktiv-introspection")
include("reaktiv-devtools")
include("reaktiv-devtools-ui")
include("reaktiv-network-ktor")
include("reaktiv-navigation-tooling")
include("reaktiv-tracing-annotations")
include("reaktiv-tracing-runtime")
include("reaktiv-test")
include("reaktiv-test-navigation")
includeBuild("reaktiv-tracing-compiler")

gradle.allprojects {
    configurations.all {
        resolutionStrategy.dependencySubstitution {
            substitute(module("io.github.syrou:reaktiv-tracing-annotations"))
                .using(project(":reaktiv-tracing-annotations"))
            substitute(module("io.github.syrou:reaktiv-tracing-runtime"))
                .using(project(":reaktiv-tracing-runtime"))
        }
    }
}
