import net.corda.plugins.Cordform
import net.corda.plugins.Node
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.kotlin.dsl.register
import org.gradle.api.provider.Provider

plugins {
    id("default-kotlin")
    alias(libs.plugins.cordformation)
}

dependencies {
    corda(libs.corda)
    cordaBootstrapper(libs.corda.node.api)
    cordaDriver(libs.corda.shell)

    cordapp(project(":bridging-token-contracts"))
    cordapp(project(":bridging-token-workflows"))

    cordapp(libs.tokens.contracts)
    cordapp(libs.tokens.workflows)

    cordapp(libs.samples.kotlin.stockpaydividend.contracts)
    cordapp(libs.samples.kotlin.stockpaydividend.workflows)
}

val solanaNotaryKeyFileName = "Dev7chG99tLCAny3PNYmBdyhaKEVcZnSTp3p1mKVb5m5.json"
val solanaNotaryKeyPath = "${layout.buildDirectory}/nodes/dev-keys/$solanaNotaryKeyFileName" //TODO set under Solana
val custodiedKeysDirectory = "$${layout.buildDirectory}/nodes/custodied-keys"

tasks.register<Cordform>("deployNodes") {
    dependsOn(
        project(":bridging-token-contracts").tasks.named("jar"),
        project(":bridging-token-workflows").tasks.named("jar")
    )
    val commonRpcUser =  listOf(
        mapOf(
            "user" to "user1",
            "password" to "test",
            "permissions" to listOf("ALL")
        )
    )
    nodeDefaults {
        projectCordapp {
            deploy = false
        }
        cordapp(libs.tokens.contracts)
        cordapp(libs.tokens.workflows)

        cordapp(libs.samples.kotlin.stockpaydividend.contracts)
        cordapp(libs.samples.kotlin.stockpaydividend.workflows)

        runSchemaMigration = true
    }
    node {
        name("O=Notary,L=London,C=GB")
        notary = mapOf(
            "validating" to "false",
            "serviceLegalName" to "O=Notary Service,L=London,C=GB"
        )
        p2pPort(10002)
        rpcSettings {
            address("localhost:10003")
            adminAddress("localhost:10033")
        }
    }
    node {
        name("O=Solana Notary,L=Ashburn,ST=Virginia,C=US")
        notary = mapOf(
            "validating" to "false",
            "serviceLegalName" to "O=Solana Notary Service,L=Washington,C=US",
            "solana" to mapOf(
                "notaryKeypairFile" to file(solanaNotaryKeyPath).absolutePath,
                "custodiedKeysDir" to file(custodiedKeysDirectory).absolutePath,
                "rpcUrl" to "https://api.devnet.solana.com"
            )
        )
        p2pPort(10019)
        rpcSettings {
            address("localhost:10020")
            adminAddress("localhost:10048")
        }
    }
    node {
        name("O=WayneCo,L=SF,C=US")
        p2pPort(10005)
        rpcSettings {
            address("localhost:10006")
            adminAddress("localhost:10036")
        }
        rpcUsers = commonRpcUser
    }
    node {
        name("O=Shareholder,L=New York,C=US")
        p2pPort(10008)
        rpcSettings {
            address("localhost:10009")
            adminAddress("localhost:10039")
        }
        rpcUsers = commonRpcUser
    }
    node {
        name("O=Bank,L=Washington DC,C=US")
        p2pPort(10012)
        rpcSettings {
            address("localhost:10013")
            adminAddress("localhost:10043")
        }
        rpcUsers = commonRpcUser
    }
    node {
        name("O=Observer,L=Washington DC,C=US")
        p2pPort(10015)
        rpcSettings {
            address("localhost:10016")
            adminAddress("localhost:10046")
        }
        rpcUsers = commonRpcUser
    }
    node {
        name("O=Bridging Authority,L=New York,C=US")
        p2pPort(10017)
        rpcSettings {
            address("localhost:10018")
            adminAddress("localhost:10047")
        }
        rpcUsers = commonRpcUser
        cordapp(project(":bridging-token-contracts"))
        //cordapp(project(":bridging-token-workflows")) //TODO endable once there is a cordapp
    }
}

// After adding second Notary (Solana Notary) the stock issuance flow may use a wrong notary,
// set the original notary as the default notary for the cordapp for WayneCo node.
tasks.register("writeCordappConfig") {
    dependsOn("deployNodes")
    doLast {
            val dir = layout.buildDirectory.dir("nodes/WayneCo/cordapps/config").get().asFile
            dir.mkdirs()
            val cfgFile = File(dir, "tokens-workflows-1.3.2.conf")
            cfgFile.writeText("\"notary\" = \"O=Notary Service,L=London,C=GB\"\n")

    }
}
tasks.named("deployNodes") { finalizedBy("writeCordappConfig") }

val cordappResolvable by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    cordappResolvable(project(":bridging-token-contracts"))
}

tasks.register("installDevKey") {
    dependsOn(tasks.named("build"))
    doLast {
        val outputDir = File(projectDir, "build/nodes/dev-key")

        if (outputDir.exists()) {
            outputDir.setWritable(true, true)
            outputDir.deleteRecursively()
        }
        outputDir.mkdirs()

        cordappResolvable.resolve().forEach { jar ->
            zipTree(jar).matching {
                include("dev-keys/$solanaNotaryKeyFileName")
            }.files.forEach { sourceFile ->
                val destFile = File(outputDir, sourceFile.name)
                sourceFile.copyTo(destFile, overwrite = true)
            }
        }

        println("Dev Key Extracted to: ${outputDir.absolutePath}")
    }
}

// Adds passing TOML references for Cordform.nodeDefaults.cordapp property
fun Node.cordapp(dep: Provider<MinimalExternalModuleDependency>) {
    val value : MinimalExternalModuleDependency = dep.get()
    cordapp("${value.module.group}:${value.module.name}:${value.versionConstraint.requiredVersion}")
}
