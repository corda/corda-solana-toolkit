rootProject.name = "corda-solana-toolkit"

include(
    "bridge-authority:contracts",
    "bridge-authority:workflows",
    "bridge-authority:integration-tests",
    "corda-solana-core",
    "corda-solana-cordapp-utils",
    "corda-solana-testing",
)

pluginManagement {
    repositories {
        gradlePluginPortal() // For jetbrains.kotlin.jvm plugin used by Cordapp plugin
        maven { url = uri("https://download.corda.net/maven/corda-releases") } //For Cordapp plugin
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        maven { r3Artifactory("corda-lib") }
        maven { r3Artifactory("corda-dependencies") }
        maven { r3Artifactory("corda-releases") }
        maven { r3Artifactory("corda-dev") }
        // For com.r3.libs:r3-libs-obfuscator:1.4.1 required by corda-node-driver
        maven { r3Artifactory("r3-corda-releases") }
        // For Corda SNAPSHOT version
        maven { r3Artifactory("r3-corda-dev") }
        maven { url = uri("https://download.corda.net/maven/corda-lib") }
        maven { url = uri("https://download.corda.net/maven/corda-dependencies") }
        maven { url = uri("https://download.corda.net/maven/corda-releases") }
        // For gradle-tooling-api-7.6.4 needed by corda-node-driver.
        maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
    }
}

fun MavenArtifactRepository.r3Artifactory(repo: String) {
    url = uri("https://software.r3.com/artifactory/$repo")
    credentials {
        username = System.getenv("CORDA_ARTIFACTORY_USERNAME")
        password = System.getenv("CORDA_ARTIFACTORY_PASSWORD")
    }
}
