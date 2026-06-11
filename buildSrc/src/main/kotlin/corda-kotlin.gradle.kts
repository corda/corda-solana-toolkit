import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("default-kotlin")
    id("corda-java")
}

// No need for the tests to be restricted to the older versions of Java and Kotlin
tasks.compileTestKotlin {
    val jdkVersion = java.toolchain.languageVersion.get()
    compilerOptions {
        languageVersion = null
        apiVersion = null
        jvmTarget = JvmTarget.fromTarget(jdkVersion.toString())
        freeCompilerArgs.set(listOf("-Xjdk-release=$jdkVersion"))
    }
}

kotlin {
    val kotlinVersion = versionCatalogs.named("libs").findVersion("kotlin.corda").get().toString()
    coreLibrariesVersion = kotlinVersion
    compilerOptions {
        val kotlinMinorVersion = KotlinVersion.fromVersion(kotlinVersion.split(".").take(2).joinToString("."))
        languageVersion = kotlinMinorVersion
        apiVersion = kotlinMinorVersion
        // Make sure Java 17 bytecode is produced, even if the java.toolchain uses a newer JDK
        jvmTarget = JvmTarget.JVM_17
        // Make sure only JDK 17 APIs are used.
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}
