import java.util.Properties

plugins {
    id("com.android.application")
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
    buildTypes.set(setOf("debug"))
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
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }
    jvmToolchain(17)
}

dependencies {

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.components.resources)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("io.ktor:ktor-client-okhttp:3.1.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.1.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.0")
    implementation("io.ktor:ktor-client-logging:3.1.0")
    implementation("androidx.core:core-splashscreen:1.0.0")
    implementation("androidx.webkit:webkit:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("io.ktor:ktor-serialization-kotlinx-xml:2.3.1")

    implementation(project(":reaktiv-core"))
    implementation(project(":reaktiv-compose"))
    implementation(project(":reaktiv-navigation"))
    implementation(project(":reaktiv-tracing-annotations"))
    debugImplementation(project(":reaktiv-introspection"))
    debugImplementation(project(":reaktiv-devtools"))
    debugImplementation(project(":reaktiv-network-ktor"))
    debugImplementation("io.ktor:ktor-client-mock:3.1.0")
}
val androidSdkDir: Provider<String> = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orElse(
        providers.provider {
            val props = rootProject.file("local.properties")
            if (!props.exists()) return@provider null
            val loaded = Properties()
            props.inputStream().use { loaded.load(it) }
            loaded.getProperty("sdk.dir")
        }
    )

val adbExecutable: Provider<String> = androidSdkDir.zip(providers.systemProperty("os.name")) { sdk, os ->
    val binary = if (os.lowercase().contains("win")) "adb.exe" else "adb"
    File(sdk, "platform-tools/$binary").absolutePath
}

val launchComponent = "eu.syrou.androidexample/eu.syrou.androidexample.MainActivity"

tasks.register<Exec>("runDebug") {
    group = "reaktiv"
    description = "Builds and installs the debug app on the connected device, then launches it"
    dependsOn("installDebug")
    doFirst {
        val adb = adbExecutable.orNull
            ?: error("Android SDK not found. Set ANDROID_HOME or sdk.dir in local.properties.")
        commandLine(adb, "shell", "am", "start", "-n", launchComponent)
    }
}

tasks.register<Exec>("stopDebug") {
    group = "reaktiv"
    description = "Force-stops the example app on the connected device"
    doFirst {
        val adb = adbExecutable.orNull
            ?: error("Android SDK not found. Set ANDROID_HOME or sdk.dir in local.properties.")
        commandLine(adb, "shell", "am", "force-stop", "eu.syrou.androidexample")
    }
}

tasks.register<Exec>("reinstallDebug") {
    group = "reaktiv"
    description = "Uninstalls any existing install, then builds, installs and launches the debug app"
    dependsOn("assembleDebug")
    doFirst {
        val adb = adbExecutable.orNull
            ?: error("Android SDK not found. Set ANDROID_HOME or sdk.dir in local.properties.")
        commandLine(adb, "uninstall", "eu.syrou.androidexample")
        isIgnoreExitValue = true
    }
    finalizedBy("runDebug")
}
