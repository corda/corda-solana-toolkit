plugins {
    alias(libs.plugins.kotlin.jvm) // Intellij may complain about 'libs' due to using Gradle 7, but it works fine.
    alias(libs.plugins.cordapp)
    `maven-publish`
    id("r3-artifactory")
    alias(libs.plugins.detekt)
}

dependencies {
    cordaProvided(libs.corda.core)
    cordapp(libs.tokens.workflows)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    detektPlugins(libs.detekt.ktlint.wrapper)
}

cordapp {
    targetPlatformVersion.set(140) //TODO externalise
    minimumPlatformVersion.set(1) //TODO externalise

    workflow {
        name.set("Corda Bridging Tokens Workflows")
        versionId.set(1)
        vendor.set("R3")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>(project.name) {
            artifactId = "corda-bridging-token-workflows"
            from(components["cordapp"])
        }
    }
}
