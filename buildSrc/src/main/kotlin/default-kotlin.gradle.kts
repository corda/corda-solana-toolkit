import org.gradle.kotlin.dsl.resolver.buildSrcSourceRootsFilePath
import java.nio.file.Files

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

tasks.processTestResources {
    from(rootProject.layout.projectDirectory.file("buildSrc/src/main/resources/log4j2-test.xml"))
}

tasks.withType<Test> {
    useJUnitPlatform()
    val testLogLevel = when (gradle.startParameter.logLevel) {
        LogLevel.LIFECYCLE -> "WARN"
        LogLevel.QUIET -> "FATAL"
        else -> gradle.startParameter.logLevel.name
    }
    systemProperty("log4j2TestLoggingLevel", testLogLevel)
    doFirst {
        val tempDir = layout.buildDirectory.dir("junit-temp").get().asFile.toPath()
        Files.createDirectories(tempDir)
        systemProperty("java.io.tmpdir", tempDir)
    }
}

detekt {
    config.setFrom(rootProject.file("detekt.yml"))
    buildUponDefaultConfig.set(true)
}
