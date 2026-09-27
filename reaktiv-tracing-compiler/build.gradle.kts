plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
    alias(libs.plugins.dokka)
    id("io.github.syrou.central-publisher-plugin")
    id("io.github.syrou.version")
}

group = "io.github.syrou"
version = project.findProperty("version") ?: "0.0.1-SNAPSHOT"

centralPublisher {
    projectName = "Reaktiv Tracing Compiler"
    projectDescription = "Kotlin compiler plugin for automatic logic method tracing in Reaktiv"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    compileOnly(libs.kotlin.compiler.embeddable)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlin.compiler.embeddable)
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    val pluginJar = tasks.jar.flatMap { it.archiveFile }
    dependsOn(tasks.jar)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dreaktiv.tracing.pluginJar=${pluginJar.get().asFile.absolutePath}")
    })
}
