plugins {
    id("default-kotlin")
    id("r3-artifactory")
}

// This module must not have a depedency to Corda Core as it's meant to be a general purpose utility library.
dependencies {
    api(libs.sava.programs)

    implementation(libs.sava.core)
    implementation(libs.sava.rpc)
    implementation(libs.bucket4j)
    implementation(libs.slf4j.api)
    implementation(libs.kotlin.reflect)

    testImplementation(project(":testing"))

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
