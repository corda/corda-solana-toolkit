import net.corda.plugins.Cordform
import net.corda.plugins.Node
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.kotlin.dsl.register
import org.gradle.api.provider.Provider
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

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
val solanaNotaryKeyPath = "${layout.buildDirectory.get()}/solana-keys/dev-key/$solanaNotaryKeyFileName"
val custodiedKeysDirectory = "${layout.buildDirectory.get()}/custodied-keys"

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
        cordapp(project(":bridging-token-workflows"))
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

// To allow installDevKey task to get JAR file from cordapp dependency with dev key, this sevres 2 purposes:
// - the configuration can point to single dependency avoiding scanning other jars
// - 'cordapp' dependency cannot be resolved, it needs to be wrapped
val cordappResolvable: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    cordappResolvable(project(":bridging-token-workflows"))
}

tasks.register("installSolanaNotaryDevKey") {
    dependsOn(tasks.named("build"))
    doLast {
        val outputDir = File(projectDir, "build/solana-keys/dev-key")

        if (outputDir.exists()) {
            outputDir.setWritable(true, true)
            outputDir.deleteRecursively()
        }
        outputDir.mkdirs()

        cordappResolvable.resolve().forEach { jar ->
            zipTree(jar).matching {
                include("dev-key/$solanaNotaryKeyFileName")
            }.files.forEach { sourceFile ->
                val destFile = File(outputDir, sourceFile.name)
                sourceFile.copyTo(destFile, overwrite = true)
            }
        }

        println("Dev Key Extracted to: ${outputDir.absolutePath}")
    }
}

abstract class GenerateMockSolanaKeys : DefaultTask() {
    @get:OutputFile
    abstract val bigBankKeyFile: RegularFileProperty //TODO a list

    @get:OutputFile
    abstract val tokenMintKeyFile: RegularFileProperty

    @get:OutputFile
    abstract val bridgeAuthorityKeyFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val bigBankKey = generateMockSolanaKey()
        val tokenMintKey = generateMockSolanaKey()
        val bridgeAuthorityKey = generateMockSolanaKey()

        bigBankKeyFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(bigBankKey)
        }

        tokenMintKeyFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(tokenMintKey)
        }

        bridgeAuthorityKeyFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(bridgeAuthorityKey)
        }

        println("Generated mock Solana keys:")
        println("  BigBank:          $bigBankKey")
        println("  TokenMint:        $tokenMintKey")
        println("  BridgeAuthority:  $bridgeAuthorityKey")
    }

    private fun generateMockSolanaKey(): String {
        val base58Chars = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        return (1..44)
            .map { base58Chars.random() }
            .joinToString("")
    }
}
val generateKeys = tasks.register<GenerateMockSolanaKeys>("generateMockSolanaKeys") {
    bigBankKeyFile.set(layout.buildDirectory.file("solana-keys/bigbank.pub"))
    tokenMintKeyFile.set(layout.buildDirectory.file("solana-keys/tokenmint.pub"))
    bridgeAuthorityKeyFile.set(layout.buildDirectory.file("solana-keys/bridge.pub"))
    dependsOn("build")
}

abstract class InstallSolanaBridgeConfig : DefaultTask() {
    @get:Input
    abstract val bigBankPubkey: Property<String>

    @get:Input
    abstract val tokenMintPubkey: Property<String>

    @get:Input
    abstract val bridgeAuthorityPubkey: Property<String>

    @get:Input
    abstract val cordaTokenTypeId: Property<String>

    @get:Input
    abstract val bigBankCordaIdentity: Property<String>

    @get:Input
    @get:Optional
    abstract val nodeName: Property<String>

    @get:OutputFile
    abstract val configFile: RegularFileProperty

    @TaskAction
    fun install() {
        val node = nodeName.getOrElse("BridgingAuthority")

        val configContent = """
            participants = {"${bigBankCordaIdentity.get()}" = "${bigBankPubkey.get()}"}
            mints = {"${cordaTokenTypeId.get()}" = "${tokenMintPubkey.get()}"}
            mintAuthorities = {"${cordaTokenTypeId.get()}" = "${bridgeAuthorityPubkey.get()}"}
        """.trimIndent()

        val outputFile = configFile.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(configContent)

        println("Generated config for $node at: ${outputFile.absolutePath}")
    }
}

tasks.register<InstallSolanaBridgeConfig>("installSolanaBridgeConfig") {
    val node = project.findProperty("nodeName") as String? ?: "BridgingAuthority"

    bigBankPubkey.set(generateKeys.flatMap {
        providers.fileContents(it.bigBankKeyFile).asText.map { it.trim() }
    })
    tokenMintPubkey.set(generateKeys.flatMap {
        providers.fileContents(it.tokenMintKeyFile).asText.map { it.trim() }
    })
    bridgeAuthorityPubkey.set(generateKeys.flatMap {
        providers.fileContents(it.bridgeAuthorityKeyFile).asText.map { it.trim() }
    })

    cordaTokenTypeId.set(project.findProperty("cordaTokenTypeId") as String? ?: "TEST")
    bigBankCordaIdentity.set(
        project.findProperty("bigBankIdentity") as String? ?: "O=WayneCo,L=SF,C=US" //TODO this will be a list
    )
    nodeName.set(node)
    configFile.set(layout.buildDirectory.file("nodes/$node/cordapps/config/bridging-flows-1.0.conf"))

    dependsOn(generateKeys)
    dependsOn("deployNodes")
}


// Adds passing TOML references for Cordform.nodeDefaults.cordapp property
fun Node.cordapp(dep: Provider<MinimalExternalModuleDependency>) {
    val value : MinimalExternalModuleDependency = dep.get()
    cordapp("${value.module.group}:${value.module.name}:${value.versionConstraint.requiredVersion}")
}
