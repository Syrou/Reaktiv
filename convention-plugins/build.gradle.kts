plugins {
    `kotlin-dsl` // Is needed to turn our build logic written in Kotlin into Gralde Plugin
}

gradlePlugin {
    plugins {
        create("versionPlugin") {
            id = "io.github.syrou.version"
            implementationClass = "VersionPlugin"
        }
    }
}

repositories {
    gradlePluginPortal() // To use 'maven-publish' and 'signing' plugins in our own plugin
}

dependencies {
    implementation("org.jreleaser:jreleaser-gradle-plugin:1.18.0")
}