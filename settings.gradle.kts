rootProject.name = "corda-solana-toolkit"

include(
    "bridging-token-contracts",
    "bridging-token-workflows",
)

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
    }
}

fun artifactory(credentials: PasswordCredentials) {
    credentials.username = System.getenv("CORDA_ARTIFACTORY_USERNAME")
    credentials.password = System.getenv("CORDA_ARTIFACTORY_PASSWORD")
}
