plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    `maven-publish`
    id("org.jetbrains.dokka")
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
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.4.10")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")

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

tasks.jar {
    manifest {
        attributes(
            "Implementation-Version" to project.version
        )
    }
}

tasks.test {
    useJUnitPlatform()
}
