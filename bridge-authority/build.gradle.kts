import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

plugins {
    alias(libs.plugins.axion.release)
    alias(libs.plugins.ben.manes.versions)
}

scmVersion {
    // The .git directory lives in the toolkit repo root
    repository {
        directory.set(rootProject.rootDir.parentFile.absolutePath)
    }
    tag {
        prefix.set("v")
    }
}

// It is discouraged to configure modules via the root build file, but this how the axion-release plugin advises module
// versions be set.
allprojects {
    project.version = rootProject.scmVersion.version
}

tasks.withType<DependencyUpdatesTask> {
    fun isNonStable(version: String): Boolean {
        val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.toUpperCase().contains(it) }
        return !stableKeyword && !"^[0-9,.v-]+(-r)?$".toRegex().matches(version)
    }
    rejectVersionIf {
        isNonStable(candidate.version)
    }
}
