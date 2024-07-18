import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.ByteArrayOutputStream

class VersionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.version = getVersionName(project)
    }

    private fun getVersionName(project: Project): String {
        return try {
            val stdout = ByteArrayOutputStream()
            project.exec {
                commandLine("git", "describe", "--tags", "--abbrev=0")
                standardOutput = stdout
            }
            stdout.toString().trim()
        } catch (e: Exception) {
            project.logger.warn("Failed to get version from Git tag, using default version", e)
            "0.7.6-SNAPSHOT" // Default version if git command fails
        }
    }
}