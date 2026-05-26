plugins {
    alias(libs.plugins.axion.release)
    alias(libs.plugins.ben.manes.versions)
}

scmVersion {
    // The .git directory lives in the toolkit repo root, one level up from this Gradle root.
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
