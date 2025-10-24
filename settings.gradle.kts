rootProject.name = "corda-solana-toolkit"

include(
    "bridging-token-contracts",
    "bridging-token-workflows",
    "samples:stockpaydividend-solana-bridge"
)

pluginManagement {
    repositories {
        gradlePluginPortal() // For jetbrains.kotlin.jvm plugin used by Cordapp plugin
        maven { url = uri("https://download.corda.net/maven/corda-releases") } //For Cordapp plugin
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenLocal()
        mavenCentral()
        maven {
            url = uri("https://software.r3.com/artifactory/corda-dev")
            credentials { artifactory(this) }
        }
        maven {
            url = uri("https://software.r3.com/artifactory/corda-releases")
            credentials { artifactory(this) }
        }
        maven {
            url = uri("https://software.r3.com/artifactory/corda-lib")
            credentials { artifactory(this) }
        }
        maven {
            url = uri("https://software.r3.com/artifactory/corda-dependencies")
            credentials { artifactory(this) }
        }
        maven { url = uri("https://jitpack.io") } // For compiling Cordapps from Github samples-kotlin to use in samples/stockpaydividend-solana-bridge
        // Corda Enterprise snapshot versions
        maven {
            url = uri("https://software.r3.com/artifactory/r3-corda-dev")
            credentials { artifactory(this) }
        }
        // com.r3.libs:r3-libs-obfuscator required by corda-node-driver
        maven {
            url = uri("https://software.r3.com/artifactory/r3-corda-releases")
            credentials { artifactory(this) }
        }
    }
}

fun artifactory(credentials: PasswordCredentials) {
    credentials.username = System.getenv("CORDA_ARTIFACTORY_USERNAME")
    credentials.password = System.getenv("CORDA_ARTIFACTORY_PASSWORD")
}
