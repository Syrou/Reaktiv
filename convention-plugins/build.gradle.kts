plugins {
    `kotlin-dsl` // Is needed to turn our build logic written in Kotlin into Gralde Plugin
}

gradlePlugin {
    plugins {
        create("versionPlugin") {
            id = "io.github.syrou.version"
            implementationClass = "VersionPlugin"
        }
        // Your other custom plugins...
    }
}

repositories {
    gradlePluginPortal() // To use 'maven-publish' and 'signing' plugins in our own plugin
}