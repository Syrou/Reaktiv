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
    username = CentralPublisherCredentials.getRequiredCredential(project, "CENTRAL_TOKEN")
    password = CentralPublisherCredentials.getRequiredCredential(project, "CENTRAL_PASSWORD")
    publishingType = PublishingType.AUTOMATIC

    signingPassword = CentralPublisherCredentials.getRequiredCredential(project, "SIGNING_PASSWORD")
    signingSecretKey = CentralPublisherCredentials.getRequiredCredential(project, "SIGNING_SECRET_KEY")

    projectName = "Reaktiv Tracing Gradle Plugin"
    projectDescription = "Gradle plugin for automatic logic method tracing in Reaktiv"
    projectUrl = "https://github.com/Syrou/Reaktiv"

    licenseName = "Apache License 2.0"
    licenseUrl = "https://opensource.org/license/apache-2-0"

    developerId = "Syrou"
    developerName = "Syrou"
    developerEmail = "me@syrou.eu"

    scmUrl = "https://github.com/Syrou/Reaktiv"
    scmConnection = "scm:git:https://github.com/Syrou/Reaktiv.git"
    scmDeveloperConnection = "scm:git:ssh://github.com/Syrou/Reaktiv.git"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.2.10")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")

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
