import java.nio.file.Files

plugins {
    java
    id("com.adarshr.test-logger")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    val libs = versionCatalogs.named("libs")

    testImplementation(libs.findLibrary("junit.core").get())
    testImplementation(libs.findLibrary("assertj.core").get())

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.findLibrary("slf4j.simple").get())
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

tasks.processTestResources {
    from(rootProject.layout.projectDirectory.file("buildSrc/src/main/resources/log4j2-test.xml"))
}

tasks.withType<Test> {
    useJUnitPlatform()
    val testLogLevel = when (gradle.startParameter.logLevel) {
        LogLevel.LIFECYCLE -> "FATAL"
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
