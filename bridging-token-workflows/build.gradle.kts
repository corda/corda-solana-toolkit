plugins {
    alias(libs.plugins.kotlin.jvm) // Intellij may complain about 'libs' due to using Gradle 7, but it works fine.
    alias(libs.plugins.cordapp)
    alias(libs.plugins.quasar.utils)
    `maven-publish`
    id("r3-artifactory")
    alias(libs.plugins.detekt)
}

dependencies {
    cordapp(project(":bridging-token-contracts"))

    cordaProvided(libs.corda.core)
    cordaProvided(libs.tokens.contracts)
    cordaProvided(libs.tokens.workflows)
    cordaProvided(libs.corda.solana.sdk)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit)
    testImplementation(libs.assertj.core)
    testImplementation(libs.corda.solana.common)
    testImplementation(libs.corda.test.utils)
    testImplementation(libs.corda.core.test.utils)
    testImplementation(libs.corda.node.driver)

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
        licence.set("Apache License, Version 2.0")
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
