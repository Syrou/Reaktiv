plugins {
    kotlin("jvm")
    `maven-publish`
    id("org.jetbrains.dokka")
    id("io.github.syrou.central-publisher-plugin")
    id("io.github.syrou.version")
}

group = "io.github.syrou"
version = project.findProperty("version") ?: "0.0.1-SNAPSHOT"

centralPublisher {
    username = CentralPublisherCredentials.getRequiredCredential(project, "CENTRAL_TOKEN")
    password = CentralPublisherCredentials.getRequiredCredential(project, "CENTRAL_PASSWORD")
    publishingType = PublishingType.AUTOMATIC

    signingPassword = CentralPublisherCredentials.getRequiredCredential(project, "SIGNING_PASSWORD")
    signingSecretKey = CentralPublisherCredentials.getRequiredCredential(project, "SIGNING_SECRET_KEY")

    projectName = "Reaktiv Tracing Compiler"
    projectDescription = "Kotlin compiler plugin for automatic logic method tracing in Reaktiv"
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
}

dependencies {
    implementation(kotlin("stdlib"))
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.10")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.10")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
