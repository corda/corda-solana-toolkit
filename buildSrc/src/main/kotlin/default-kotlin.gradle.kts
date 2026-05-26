plugins {
    id("default-java")
    id("org.jetbrains.kotlin.jvm")
    id("dev.detekt")
}

dependencies {
    val libs = versionCatalogs.named("libs")

    detektPlugins("dev.detekt:detekt-rules-ktlint-wrapper:${libs.findVersion("detekt").get()}")
}

kotlin {
    compilerOptions {
        javaParameters = true
    }
}

detekt {
    config.setFrom(rootProject.file("detekt.yml"))
    buildUponDefaultConfig = true
}
