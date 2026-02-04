pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }

    plugins {
        kotlin("jvm") version "2.2.10"
        id("org.jetbrains.dokka") version "2.0.0"
    }

    includeBuild("../convention-plugins")
}

rootProject.name = "reaktiv-tracing-gradle"
