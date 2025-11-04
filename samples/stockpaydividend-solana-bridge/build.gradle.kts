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
                "rpcUrl" to "http://localhost:8899"
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
// set the original notary as the default one for the cordapp for WayneCo node.
tasks.register("writeCordappConfig") {// TODO should work for list of nodes
    dependsOn("deployNodes")
    doLast {
            val dir = layout.buildDirectory.dir("nodes/WayneCo/cordapps/config").get().asFile
            dir.mkdirs()
            val cfgFile = File(dir, "tokens-workflows-1.3.2.conf")
            cfgFile.writeText("\"notary\" = \"O=Notary Service,L=London,C=GB\"\n")
    }
}
tasks.named("deployNodes") { finalizedBy("writeCordappConfig") }

abstract class InstallSolanaNotaryDevKeyTask : DefaultTask() {
    @get:Classpath
    abstract val cordapps: ConfigurableFileCollection

    @get:Input
    abstract val keyFileName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun install() {
        val outDirFile = outputDir.get().asFile
        if (outDirFile.exists()) {
            outDirFile.setWritable(true, true)
            outDirFile.deleteRecursively()
        }
        outDirFile.mkdirs()
        cordapps.forEach { jar ->
            project.zipTree(jar).matching {
                include("dev-key/${keyFileName.get()}")
            }.files.forEach { sourceFile ->
                val destFile = outDirFile.resolve(sourceFile.name)
                sourceFile.copyTo(destFile, overwrite = true)
            }
        }
        println("Dev Key Extracted to: ${outDirFile.absolutePath}")
    }
}

tasks.register<InstallSolanaNotaryDevKeyTask>("installSolanaNotaryDevKey") {
    //dependsOn("deployNodes")
    val detached = configurations.detachedConfiguration(
        //dependencies.create("com.r3.corda.lib.solana:bridging-token-workflows:0.1.0-SNAPSHOT") // for project outside this repo
        dependencies.create(project(":bridging-token-workflows"))
    )
    cordapps.from(detached)
    keyFileName.set(solanaNotaryKeyFileName)
    outputDir.set(layout.buildDirectory.dir("solana-keys/dev-key"))
}

abstract class SetupAccounts : DefaultTask() {
    @get:Input
    abstract val notaryKeyPath: Property<String>

    @get:Input
    abstract val participants: ListProperty<String>

    @get:OutputFiles
    abstract val participantKeyFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val tokenMintKeyFile: RegularFileProperty

    @get:OutputFile
    abstract val bridgeAuthorityKeyFile: RegularFileProperty

    @TaskAction
    fun runScript() {
        project.exec {
            commandLine(
                "bash", "-x",
                project.layout.projectDirectory.file("setupSolanaAccounts.sh").asFile.absolutePath,
                notaryKeyPath.get()
            )
        }
        println("Generated Solana keys:")
        println("  Participants:     ${participantKeyFiles.files.map { it.absolutePath }}")
        println("  TokenMint:        ${tokenMintKeyFile.get().asFile.absolutePath}")
        println("  BridgeAuthority:  ${bridgeAuthorityKeyFile.get().asFile.absolutePath}")
    }
}

val generateKeys = tasks.register<SetupAccounts>("setupSolanaAccounts") {
    //dependsOn("deployNodes")
    notaryKeyPath.set(solanaNotaryKeyPath)
    participants.set(listOf("O=WayneCo,L=SF,C=US"))
    println("Tesla ${layout.buildDirectory.asFile.get().absolutePath}")
    val outputDir = layout.buildDirectory.dir("solana-keys")
    println("Tesla2 ${outputDir.get().asFile.absolutePath}")

    participantKeyFiles.setFrom(
        participants.map { names ->
            names.map { name -> outputDir.map { it.file("$name.pub") } }
        }
    )

    tokenMintKeyFile.set(layout.buildDirectory.file("solana-keys/token-mint.pub"))
    bridgeAuthorityKeyFile.set(layout.buildDirectory.file("solana-keys/bridge-authority.pub"))
}

abstract class InstallSolanaBridgeConfig : DefaultTask() {
    @get:Internal
    abstract val inputDir: DirectoryProperty

    @get:Input
    abstract val participants: ListProperty<String>

    @get:InputFiles
    abstract val participantKeyFiles: ConfigurableFileCollection

    @get:Input
    abstract val tokenMintPubkey: Property<String>

    @get:Input
    abstract val bridgeAuthorityPubkey: Property<String>

    @get:Input
    abstract val cordaTokenTypeId: Property<String> //TODO this will be remove, in the future generated at runtime

    @get:OutputFile
    abstract val configFile: RegularFileProperty

    @TaskAction
    fun install() {
        val keys = participantKeyFiles.map { it.readText().trim() }
        val text = participants.get()
            .zip(keys) { participant, key -> "\"$participant\" = \"$key\"" }
            .joinToString(", ")
        val configContent = """
            participants = { $text }
            mints = { "${cordaTokenTypeId.get()}" = "${tokenMintPubkey.get()}" }
            mintAuthorities = { "${cordaTokenTypeId.get()}" = "${bridgeAuthorityPubkey.get()}" }
        """.trimIndent()
        val outputFile = configFile.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(configContent)

        println("Generated Solana config for ${outputFile.absolutePath}")
    }
}

tasks.register<InstallSolanaBridgeConfig>("installSolanaBridgeConfig") {
    //dependsOn(generateKeys)

    val node = project.findProperty("nodeName") as String? ?: "BridgingAuthority"
    inputDir.set(layout.buildDirectory.dir("solana-keys"))
    participants.set(listOf("O=WayneCo,L=SF,C=US"))

    participantKeyFiles.from(
        generateKeys.map { it.participantKeyFiles }
    )
    tokenMintPubkey.set(generateKeys.flatMap {
        providers.fileContents(it.tokenMintKeyFile).asText.map { it.trim() }
    })
    bridgeAuthorityPubkey.set(generateKeys.flatMap {
        providers.fileContents(it.bridgeAuthorityKeyFile).asText.map { it.trim() }
    })

    cordaTokenTypeId.set(project.findProperty("cordaTokenTypeId") as String? ?: "TEST")
    configFile.set(layout.buildDirectory.file("nodes/$node/cordapps/config/bridging-flows-1.0.conf"))
}

// Adds passing TOML references for Cordform.nodeDefaults.cordapp property
fun Node.cordapp(dep: Provider<MinimalExternalModuleDependency>) {
    val value : MinimalExternalModuleDependency = dep.get()
    cordapp("${value.module.group}:${value.module.name}:${value.versionConstraint.requiredVersion}")
}
