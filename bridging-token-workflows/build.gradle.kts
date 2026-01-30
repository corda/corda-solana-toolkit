plugins {
    id("default-kotlin")
    alias(libs.plugins.cordapp)
    alias(libs.plugins.quasar.utils)
    id("r3-artifactory")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.sava.rpc)
    implementation(libs.caffeine.cache)

    cordaProvided(libs.corda.core)
    cordaProvided(libs.ent.corda.solana.sdk)

    cordapp(project(":bridging-token-contracts"))
    cordapp(libs.tokens.contracts)
    cordapp(libs.tokens.workflows)

    detektPlugins(libs.detekt.ktlint.wrapper)
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
