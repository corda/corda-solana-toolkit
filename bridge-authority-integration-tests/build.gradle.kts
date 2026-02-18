plugins {
    id("default-kotlin")
    alias(libs.plugins.cordapp)
    alias(libs.plugins.quasar.utils)
}

dependencies {
    cordaProvided(libs.corda.core)

    cordapp(libs.tokens.contracts)
    cordapp(libs.tokens.workflows)

    cordapp(project(":bridge-authority-contracts"))
    cordapp(project(":bridge-authority-workflows"))

    testImplementation(project(":core"))
    testImplementation(project(":testing"))
    testImplementation(libs.solana.notary.client)
    testImplementation(libs.corda.ent.core.test.utils)
    testImplementation(libs.corda.ent.node.driver)

    // When using SNAPSHOT node-driver, make sure we are using the same build of the Enterprise Corda node
    testRuntimeOnly(libs.corda.ent.node.api)
    testRuntimeOnly(libs.corda.ent.node)
    testRuntimeOnly(libs.solana.notary.program)

    detektPlugins(libs.detekt.ktlint.wrapper)
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
