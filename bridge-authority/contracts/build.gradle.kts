plugins {
    id("default-kotlin")
    alias(libs.plugins.cordapp)
    id("r3-artifactory")
}

dependencies {
    implementation(project(":corda-solana-cordapp-utils"))

    cordaProvided(libs.corda.core)

    cordapp(libs.tokens.contracts)

    testImplementation(libs.corda.ent.node.driver)
    testImplementation(libs.corda.ent.core.test.utils)
    testImplementation(libs.corda.ent.test.utils)
    testImplementation(libs.mockito.core)

    // When using SNAPSHOT node-driver, make sure we are using the same build of the Enterprise Corda node
    testRuntimeOnly(libs.corda.ent.node.api)
    testRuntimeOnly(libs.corda.ent.node)

    detektPlugins(libs.detekt.ktlint.wrapper)
}

cordapp {
    val platformVersion = properties["cordaPlatformVersion"].toString().toInt()
    targetPlatformVersion.set(platformVersion)
    minimumPlatformVersion.set(platformVersion)

    workflow {
        name.set("Solana Bridge Authority Contracts")
        versionId.set(properties["cordaVersionId"].toString().toInt())
        vendor.set("R3")
    }
}

java {
    withSourcesJar()
}

publishing {
    publications {
        getByName<MavenPublication>("mainPublication") {
            artifactId = "bridge-authority-contracts"
            from(components["cordapp"])
        }
    }
}
