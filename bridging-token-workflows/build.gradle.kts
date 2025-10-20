plugins {
    id("default-kotlin")
    alias(libs.plugins.cordapp)
    alias(libs.plugins.quasar.utils)
    id("r3-artifactory")
}

dependencies {
    cordapp(project(":bridging-token-contracts"))
    cordapp(libs.tokens.workflows)

    cordaProvided(libs.corda.core)
    cordaProvided(libs.tokens.contracts)
    cordaProvided(libs.corda.solana.sdk)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit)
    testImplementation(libs.assertj.core)
    testImplementation(libs.corda.solana.common)
    testImplementation(libs.corda.test.utils)
    testImplementation(libs.corda.core.test.utils)
    testImplementation(libs.corda.node.driver)
    testImplementation("co.paralleluniverse:quasar-core:0.7.6") // for flow code TODO move to toml

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

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
        "antlr**",
        "com.codahale**",
        "com.fasterxml.**",
        "com.github.benmanes.caffeine.**",
        "com.google.**",
        "com.lmax.**",
        "com.zaxxer.**",
        "net.bytebuddy**",
        "io.github.classgraph**",
        "io.netty*",
        "liquibase**",
        "net.i2p.crypto.**",
        "nonapi.io.github.classgraph.**",
        "org.apiguardian.**",
        "org.bouncycastle**",
        "org.codehaus.**",
        "org.h2**",
        "org.hibernate**",
        "org.jboss.**",
        "org.objenesis**",
        "org.w3c.**",
        "org.xml**",
        "org.yaml**",
        "rx**",
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
