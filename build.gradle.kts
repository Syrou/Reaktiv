plugins {
    kotlin("jvm") apply false
    kotlin("multiplatform") apply false
    kotlin("android") apply false
    id("com.android.application") apply false
    id("com.android.library") apply false
    id("org.jetbrains.dokka") version "2.0.0"
    id("org.jetbrains.compose") version "1.8.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
}

buildscript {

    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        google()
    }

    val kotlinVersion: String by project.extra
    val atomicFuVersion: String by project.extra

    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.kotlinx:atomicfu-gradle-plugin:$atomicFuVersion")
    }
}

subprojects {
    apply(plugin = "org.jetbrains.dokka")
    tasks {
        withType<PublishToMavenRepository> {
            dependsOn(withType<Sign>())
        }
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}