plugins {
    id("default-kotlin")
    id("r3-artifactory")
}

dependencies {
    implementation(libs.corda.core)

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
