plugins {
    id("default-kotlin")
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

    testImplementation(project(":corda-solana-testing"))

    testRuntimeOnly(libs.slf4j.simple)

    detektPlugins(libs.detekt.ktlint.wrapper)
}

java {
    withSourcesJar()
}

publishing {
    publications {
        getByName<MavenPublication>("mainPublication") {
            from(components["java"])
        }
    }
}
