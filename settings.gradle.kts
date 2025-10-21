pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        google()
    }

    plugins {
        val kotlinVersion = "2.2.10"
        val agpVersion = "8.9.1"
        val composeVersion = "1.8.2"

        kotlin("jvm").version(kotlinVersion)
        kotlin("multiplatform").version(kotlinVersion)
        kotlin("plugin.compose").version(kotlinVersion)
        kotlin("plugin.serialization").version(kotlinVersion)
        kotlin("android").version(kotlinVersion)
        id("com.android.base").version(agpVersion)
        id("com.android.application").version(agpVersion)
        id("com.android.library").version(agpVersion)
        id("org.jetbrains.compose").version(composeVersion)
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.5.0"
}


rootProject.name = "reaktiv"
include("reaktiv-core")
include(":androidexample")
include("reaktiv-compose")
include("reaktiv-navigation")
includeBuild("convention-plugins")
