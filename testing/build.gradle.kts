plugins {
    id("default-kotlin")
    id("r3-artifactory")
}

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
