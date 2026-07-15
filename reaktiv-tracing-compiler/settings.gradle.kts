pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }

    plugins {
        kotlin("jvm") version "2.4.10"
        id("org.jetbrains.dokka") version "2.2.0"
    }

    includeBuild("../convention-plugins")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "reaktiv-tracing-compiler"
