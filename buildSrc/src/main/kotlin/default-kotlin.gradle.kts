plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.adarshr.test-logger")
    id("dev.detekt")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.0")
    testImplementation("org.assertj:assertj-core:3.27.6")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

detekt {
    config.setFrom(rootProject.file("detekt.yml"))
    buildUponDefaultConfig.set(true)
}
