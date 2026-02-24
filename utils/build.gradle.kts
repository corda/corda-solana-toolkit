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

tasks.withType<Jar> {
    archiveBaseName.set("corda-solana-utils")
}

publishing {
    publications {
        create<MavenPublication>("mainPublication") {
            from(components["java"])
            artifactId = "corda-solana-utils"
        }
    }
}
