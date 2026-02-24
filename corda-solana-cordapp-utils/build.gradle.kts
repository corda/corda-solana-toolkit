plugins {
    id("default-kotlin")
    id("r3-artifactory")
}

dependencies {
    compileOnly(libs.corda.core)

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
