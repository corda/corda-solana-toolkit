plugins {
    id("default-kotlin")
    alias(libs.plugins.cordapp)
    alias(libs.plugins.quasar.utils)
    id("r3-artifactory")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":corda-utils"))

    cordaProvided(libs.corda.core)

    cordapp(project(":bridge-authority-contracts"))
    cordapp(libs.tokens.contracts)
    cordapp(libs.tokens.workflows)

    detektPlugins(libs.detekt.ktlint.wrapper)
}

cordapp {
    val platformVersion = properties["cordaPlatformVersion"].toString().toInt()
    targetPlatformVersion.set(platformVersion)
    minimumPlatformVersion.set(platformVersion)

    workflow {
        name.set("Solana Bridge Authority Workflows")
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
            from(components["cordapp"])
        }
    }
}
