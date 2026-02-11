import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.nio.file.Files

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.adarshr.test-logger")
    id("dev.detekt")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testImplementation("org.assertj:assertj-core:3.27.6")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    val kotlinVersion = "1.9.25"
    coreLibrariesVersion = kotlinVersion
    compilerOptions {
        val kotlinMinorVersion = KotlinVersion.fromVersion(kotlinVersion.split(".").take(2).joinToString("."))
        languageVersion.set(kotlinMinorVersion)
        apiVersion.set(kotlinMinorVersion)
        // Make sure Java 17 bytecode is produced, even if the java.toolchain uses a newer JDK
        jvmTarget.set(JvmTarget.JVM_17)
        javaParameters.set(true)
        // Make sure only JDK 17 APIs are used.
        freeCompilerArgs.addAll("-Xjdk-release=17")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
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

detekt {
    config.setFrom(rootProject.file("detekt.yml"))
    buildUponDefaultConfig.set(true)
}
