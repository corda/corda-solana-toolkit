plugins {
    id("default-kotlin")
    alias(libs.plugins.cordapp)
    id("r3-artifactory")
}

// Don't add any Corda dependencies as this module is meant to be a general-purpose library. Use :corda-utils for that.
dependencies {
    api(libs.sava.programs)

    implementation(libs.sava.core)
    implementation(libs.sava.rpc)
    implementation(libs.bucket4j)
    implementation(libs.slf4j.api)
    implementation(libs.kotlin.reflect)

    testImplementation(project(":testing"))

    testRuntimeOnly(libs.slf4j.simple)

    detektPlugins(libs.detekt.ktlint.wrapper)
}

cordapp {
    val platformVersion = properties["cordaPlatformVersion"].toString().toInt()
    targetPlatformVersion.set(platformVersion)
    minimumPlatformVersion.set(platformVersion)

    workflow {
        name.set("Solana Core")
        versionId.set(properties["cordaVersionId"].toString().toInt())
        vendor.set("R3")
    }
}

tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
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
