plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    `maven-publish`
    alias(libs.plugins.dokka)
    id("io.github.syrou.central-publisher-plugin")
    id("io.github.syrou.version")
}

group = "io.github.syrou"

centralPublisher {
    projectName = "Reaktiv Tracing Gradle Plugin"
    projectDescription = "Gradle plugin for automatic logic method tracing in Reaktiv"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.kotlin.gradle.plugin.api)
    compileOnly(libs.kotlin.gradle.plugin)

    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("reaktivTracing") {
            id = "io.github.syrou.reaktiv.tracing"
            displayName = "Reaktiv Tracing Plugin"
            description = "Gradle plugin for automatic logic method tracing in Reaktiv"
            implementationClass = "io.github.syrou.reaktiv.tracing.gradle.ReaktivTracingGradlePlugin"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

val generateTracingVersion = tasks.register("generateTracingVersion") {
    val version = project.version.toString()
    val outputDir = layout.buildDirectory.dir("generated/tracingVersion")
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        check(version.isNotBlank() && version != "unspecified") { "The tracing plugin needs a project version" }
        val file = outputDir.get().file("io/github/syrou/reaktiv/tracing/gradle/TracingVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package io.github.syrou.reaktiv.tracing.gradle\n\ninternal const val TRACING_VERSION: String = \"$version\"\n"
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateTracingVersion)
}

tasks.test {
    useJUnitPlatform()
}
