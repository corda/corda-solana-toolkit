import org.jfrog.gradle.plugin.artifactory.dsl.ArtifactoryPluginConvention

plugins {
  id("com.jfrog.artifactory")
}

configure<ArtifactoryPluginConvention> {
  publish {
    contextUrl = "https://software.r3.com/artifactory"
    repository {
      repoKey = "corda-dependencies"
      username = System.getenv("CORDA_ARTIFACTORY_USERNAME")
      password = System.getenv("CORDA_ARTIFACTORY_PASSWORD")
    }
    defaults {
      publications("ALL_PUBLICATIONS")
    }
  }
}
