plugins {
    id("default-kotlin")
    id("r3-artifactory")
}

// Don't add any Corda dependencies as this module is meant to be a general-purpose library.
dependencies {
    implementation(project(":core"))
    implementation(libs.junit.api)
    implementation(libs.slf4j.api)

    testRuntimeOnly(libs.slf4j.simple)

    detektPlugins(libs.detekt.ktlint.wrapper)
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("mainPublication") {
            from(components["java"])
        }
    }
}
