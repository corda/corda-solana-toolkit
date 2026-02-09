plugins {
    id("default-kotlin")
    id("r3-artifactory")
}

dependencies {
    api(libs.sava.programs)

    implementation(libs.sava.core)
    implementation(libs.sava.rpc)
    implementation(libs.bucket4j)
    implementation(libs.slf4j.api)
    implementation(libs.kotlin.reflect)
//    implementation(libs.solana.notary.common)  // TODO

    testImplementation(project(":testing"))

    // DELETE THESE
    testImplementation(libs.ent.corda.node)
    testImplementation(libs.ent.corda.test.utils)

//    testRuntimeOnly(libs.slf4j.simple)

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
