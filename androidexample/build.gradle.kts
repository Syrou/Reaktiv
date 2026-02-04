import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import org.gradle.kotlin.dsl.components
import org.gradle.kotlin.dsl.resources

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("io.github.syrou.reaktiv.tracing")
}

repositories {
    mavenCentral()
    mavenLocal()
}

reaktivTracing {
    enabled.set(true)
    tracePrivateMethods.set(true)
}

android {
    namespace = "eu.syrou.androidexample"
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.syrou.androidexample"
        minSdk = 26
        //noinspection EditedTargetSdkVersion
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }

        debug {
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/ASL2.0"
            excludes += "META-INF/*.kotlin_module"
            excludes += "kotlin/kotlin.kotlin_builtins"
            excludes += "kotlin/internal/internal.kotlin_builtins"
            excludes += "kotlin/reflect/reflect.kotlin_builtins"
            excludes += "kotlin/coroutines/coroutines.kotlin_builtins"
            excludes += "kotlin/ranges/ranges.kotlin_builtins"
            excludes += "kotlin/collections/collections.kotlin_builtins"
            excludes += "kotlin/annotation/annotation.kotlin_builtins"
        }
    }
    sourceSets {
        sourceSets {
            getByName("main") {
                java.srcDir("src/main/kotlin")
            }
        }
    }
}

kotlin {
    sourceSets.all {
        languageSettings {
            optIn("kotlin.time.ExperimentalTime")
        }
    }
    jvmToolchain(17)
}

composeCompiler {
    enableStrongSkippingMode = true
}

dependencies {

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("io.ktor:ktor-client-okhttp:3.1.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.1.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.0")
    implementation("io.ktor:ktor-client-logging:3.1.0")
    implementation("androidx.core:core-splashscreen:1.0.0")
    implementation("androidx.webkit:webkit:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("io.ktor:ktor-serialization-kotlinx-xml:2.3.1")

    implementation(project(":reaktiv-core"))
    implementation(project(":reaktiv-compose"))
    implementation(project(":reaktiv-navigation"))
    implementation(project(":reaktiv-devtools"))
    implementation(project(":reaktiv-tracing-annotations"))
}