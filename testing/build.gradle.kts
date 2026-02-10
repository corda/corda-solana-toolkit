plugins {
    id("default-kotlin")
    id("r3-artifactory")
}

// This module must not have a depedency to Corda Core as it's meant to be a general purpose utility library.
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
