pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        google()
    }

    plugins {
        val kotlinVersion = "2.4.10"
        val agpVersion = "9.0.0"
        val composeVersion = "1.11.1"

        kotlin("jvm").version(kotlinVersion)
        kotlin("multiplatform").version(kotlinVersion)
        kotlin("plugin.compose").version(kotlinVersion)
        kotlin("plugin.serialization").version(kotlinVersion)
        id("com.android.base").version(agpVersion)
        id("com.android.application").version(agpVersion)
        id("com.android.library").version(agpVersion)
        id("com.android.kotlin.multiplatform.library").version(agpVersion)
        id("org.jetbrains.compose").version(composeVersion)
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
include("reaktiv-compose")
include("reaktiv-navigation")
include("reaktiv-introspection")
include("reaktiv-devtools")
include("reaktiv-tracing-annotations")
include("reaktiv-test")
includeBuild("reaktiv-tracing-compiler")

gradle.allprojects {
    configurations.all {
        resolutionStrategy.dependencySubstitution {
            substitute(module("io.github.syrou:reaktiv-tracing-annotations"))
                .using(project(":reaktiv-tracing-annotations"))
        }
    }
}
