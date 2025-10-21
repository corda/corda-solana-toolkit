plugins {
    id("default-kotlin")
    alias(libs.plugins.cordapp)
    alias(libs.plugins.quasar.utils)
    id("r3-artifactory")
}

dependencies {
    cordaProvided(libs.corda.core)
    cordaProvided(libs.corda.solana.sdk)

    cordapp(project(":bridging-token-contracts"))
    cordapp(libs.tokens.contracts)
    cordapp(libs.tokens.workflows)

    testImplementation(libs.corda.solana.common)
    testImplementation(libs.corda.test.utils)
    testImplementation(libs.corda.core.test.utils)
    testImplementation(libs.corda.node.driver)
    testImplementation(libs.quasar.core)

    detektPlugins(libs.detekt.ktlint.wrapper)
}

cordapp {
    val platformVersion = properties["cordaPlatformVersion"].toString().toInt()
    targetPlatformVersion.set(platformVersion)
    minimumPlatformVersion.set(platformVersion)

    workflow {
        name.set("Corda Bridging Tokens Workflows")
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

tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
        "--add-opens=java.base/java.time=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
    )
    systemProperty("java.io.tmpdir", buildDir.absolutePath)
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
        "org.locationtech.jts.geom.**",
        "kotlin**",
        "org.junit.**"
    )
}

publishing {
    publications {
        create<MavenPublication>(project.name) {
            artifactId = "corda-bridging-token-workflows"
            from(components["cordapp"])
        }
    }
}
