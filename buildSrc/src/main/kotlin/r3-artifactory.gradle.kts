import org.jfrog.gradle.plugin.artifactory.dsl.ArtifactoryPluginConvention

plugins {
    `maven-publish`
    id("com.jfrog.artifactory")
}

configure<ArtifactoryPluginConvention> {
    publish {
        contextUrl = "https://software.r3.com/artifactory"
        repository {
            // Different repo for SNAPSHOT builds
            repoKey = if (version.toString().endsWith("-SNAPSHOT")) "corda-dependencies-dev" else "corda-dependencies"
            username = System.getenv("CORDA_ARTIFACTORY_USERNAME")
            password = System.getenv("CORDA_ARTIFACTORY_PASSWORD")
        }
        defaults {
            publications("ALL_PUBLICATIONS")
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mainPublication") {
            pom {
                organization {
                    name.set("R3 Ltd")
                    url.set("r3.com")
                }
                scm {
                    url.set("https://github.com/corda/corda-solana-toolkit")
                }
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}
