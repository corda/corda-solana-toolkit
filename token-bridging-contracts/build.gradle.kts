plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
    id("r3-artifactory")
}

dependencies {
    implementation(libs.tokens.contracts)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
