plugins {
    id("default-kotlin")
    alias(libs.plugins.cordapp)
    id("r3-artifactory")
}

dependencies {
    compileOnly(libs.corda.core)

    detektPlugins(libs.detekt.ktlint.wrapper)
}

cordapp {
    val platformVersion = properties["cordaPlatformVersion"].toString().toInt()
    targetPlatformVersion.set(platformVersion)
    minimumPlatformVersion.set(platformVersion)

    workflow {
        name.set("Solana Corda Utils")
        versionId.set(properties["cordaVersionId"].toString().toInt())
        vendor.set("R3")
    }
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>(project.name) {
            from(components["cordapp"])
        }
    }
}
