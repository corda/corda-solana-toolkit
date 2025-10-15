plugins {
    alias(libs.plugins.cordapp)
    `maven-publish`
    id("r3-artifactory")
    alias(libs.plugins.detekt)
}

dependencies {
    implementation(libs.corda.core)
    implementation(libs.tokens.workflows)
    implementation(libs.tokens.contracts)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    detektPlugins(libs.detekt.ktlint.wrapper)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    withSourcesJar()
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>(project.name) {
            from(components["java"])
        }
    }
}
