import org.gradle.kotlin.dsl.support.kotlinCompilerOptions

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlinx.atomicfu")
    kotlin("plugin.serialization")
    id("convention.publication")
    id("io.github.syrou.version")
}

group = "io.github.syrou"

repositories {
    mavenCentral()
}

kotlin {
    androidTarget {
        publishLibraryVariants("release")
    }
    jvm()
    /*js {
    }*/
    macosArm64()
    macosX64()
    iosArm64()
    iosSimulatorArm64()
    applyDefaultHierarchyTemplate()
    linuxX64()
    mingwX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
    }

    jvmToolchain(17)
}

android {
    namespace = "io.github.syrou"
    compileSdk = 35

    sourceSets {
        named("main") {
            res.srcDir("src/commonMain/resources")
        }
    }

    lint {
        disable.addAll(listOf("MissingTranslation", "ExtraTranslation"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}