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

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
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
        // For com.r3.libs:r3-libs-obfuscator:1.4.1 required by corda-node-driver
        maven {
            url = uri("https://software.r3.com/artifactory/r3-corda-releases")
            credentials { artifactory(this) }
            mavenContent {
                releasesOnly()
            }
        }
        // For gradle-tooling-api-7.6.4.
        maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
        // For Corda (OS and Enterprise) SNAPSHOT versions
        maven {
            url = uri("https://software.r3.com/artifactory/r3-corda-dev")
            credentials { artifactory(this) }
        }
        // For corda-node-driver required by com.r3.libs:r3-libs-obfuscator
        maven {
            url = uri("https://software.r3.com/artifactory/r3-corda-releases")
            credentials { artifactory(this) }
        }
        // For compiling Cordapp from Github samples-kotlin to use in samples/stockpaydividend-solana-bridge
        maven { url = uri("https://jitpack.io") }
    }
}

fun artifactory(credentials: PasswordCredentials) {
    credentials.username = System.getenv("CORDA_ARTIFACTORY_USERNAME")
    credentials.password = System.getenv("CORDA_ARTIFACTORY_PASSWORD")
}
