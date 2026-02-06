plugins {
    id("default-kotlin")
    alias(libs.plugins.cordapp)
    alias(libs.plugins.quasar.utils)
    id("r3-artifactory")
}

dependencies {
    implementation(libs.sava.core)
    implementation(libs.sava.rpc)
    implementation(libs.sava.programs)
    implementation(libs.caffeine.cache)

    cordaProvided(libs.corda.core)
    cordaProvided(libs.ent.corda.node)  // TODO Remove once SolanaClient is moved to this repo
    cordaProvided(libs.ent.corda.solana.sdk)
    cordaProvided(libs.ent.corda.solana.notary.common)

    cordapp(project(":bridging-token-contracts"))
    cordapp(libs.tokens.contracts)
    cordapp(libs.tokens.workflows)

    detektPlugins(libs.detekt.ktlint.wrapper)

    testImplementation(libs.ent.corda.test.common)
    testImplementation(libs.ent.corda.test.utils)
    testImplementation(libs.ent.corda.core.test.utils)
    testImplementation(libs.ent.corda.node.driver)

    // When using SNAPSHOT node-driver, make sure we are using the same build of the Enterprise Corda node
    testRuntimeOnly(libs.ent.corda.node.api)
    testRuntimeOnly(libs.ent.corda.node)
}

cordapp {
    val platformVersion = properties["cordaPlatformVersion"].toString().toInt()
    targetPlatformVersion.set(platformVersion)
    minimumPlatformVersion.set(platformVersion)

    workflow {
        name.set("Corda Bridging Tokens Workflows")
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

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

publishing {
    publications {
        create<MavenPublication>(project.name) {
            artifactId = "corda-bridging-token-workflows"
            from(components["cordapp"])
        }
    }
}
