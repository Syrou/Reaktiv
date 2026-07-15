import org.gradle.api.Plugin
import org.gradle.api.Project

class VersionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.version = getVersionName(project)
    }

    private fun getVersionName(project: Project): String {
        return try {
            val output = project.providers.exec {
                commandLine("git", "describe", "--tags", "--abbrev=0")
            }.standardOutput.asText.get().trim()
            output.ifEmpty { DEFAULT_VERSION }
        } catch (e: Exception) {
            project.logger.warn("Failed to get version from Git tag, using default version", e)
            DEFAULT_VERSION
        }
    }

    private companion object {
        const val DEFAULT_VERSION = "0.7.6-SNAPSHOT"
    }
}
