plugins {
    id("default-kotlin")
    alias(libs.plugins.cordapp)
    alias(libs.plugins.quasar.utils)
}

dependencies {
    cordaProvided(libs.corda.core)

    cordapp(libs.tokens.contracts)
    cordapp(libs.tokens.workflows)

    cordapp(project(":bridging-token-contracts"))
    cordapp(project(":bridging-token-workflows"))

    detektPlugins(libs.detekt.ktlint.wrapper)

    testImplementation(libs.corda.solana.common)
    testImplementation(libs.corda.solana.sdk)
    testImplementation(libs.corda.test.utils)
    testImplementation(libs.corda.core.test.utils)
    testImplementation(libs.corda.node.driver)
    testImplementation(libs.quasar.core)
}

cordapp {
    val platformVersion = properties["cordaPlatformVersion"].toString().toInt()
    targetPlatformVersion.set(platformVersion)
    minimumPlatformVersion.set(platformVersion)

    workflow {
        name.set("Test Cordapp")
        versionId.set(properties["cordaVersionId"].toString().toInt())
        vendor.set("R3")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
}

quasar {
    excludePackages.addAll(
        "org.apiguardian.**",
        "org.bouncycastle**",
        "org.codehaus.**",
        "org.objenesis**",
        "org.w3c.**",
        "org.xml**",
        "org.yaml**",
        "rx**",
        "org.locationtech.**",
        "kotlin**",
        "org.junit.**"
    )
}

tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
        "--add-opens=java.base/java.time=ALL-UNNAMED",
    )
}
