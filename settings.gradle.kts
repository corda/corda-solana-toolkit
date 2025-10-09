rootProject.name = "corda-solana-toolkit"

include(
    "token-bridging-contracts",
    "token-bridging-workflows",
)

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenLocal()
        mavenCentral()
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
