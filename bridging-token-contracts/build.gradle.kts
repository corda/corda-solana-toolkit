plugins {
    id("default-kotlin")
    alias(libs.plugins.cordapp)
    id("r3-artifactory")
}

dependencies {
    cordaProvided(libs.corda.core)
    cordaProvided(libs.corda.solana.common)
    cordaProvided(libs.corda.solana.sdk)

    cordapp(libs.tokens.contracts)

    detektPlugins(libs.detekt.ktlint.wrapper)

    testImplementation(libs.corda.node.driver)
    testImplementation(libs.corda.core.test.utils)
    testImplementation(libs.corda.test.utils)
    testImplementation(libs.corda.solana.notary.common)

    testImplementation(libs.mockito.core)
}

cordapp {
    val platformVersion = properties["cordaPlatformVersion"].toString().toInt()
    targetPlatformVersion.set(platformVersion)
    minimumPlatformVersion.set(platformVersion)

    workflow {
        name.set("Corda Bridging Tokens Contracts")
        versionId.set(properties["cordaVersionId"].toString().toInt())
        vendor.set("R3")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>(project.name) {
            artifactId = "corda-bridging-token-contracts"
            from(components["cordapp"])
        }
    }
}
