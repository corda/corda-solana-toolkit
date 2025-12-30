plugins {
    alias(libs.plugins.axion.release)
    alias(libs.plugins.ben.manes.versions)
}

scmVersion {
    tag {
        prefix.set("v")
    }
}

// It is discouraged to configure modules via the root build file, but this how the axion-release plugin advises module
// versions be set.
allprojects {
    project.version = rootProject.scmVersion.version
}
