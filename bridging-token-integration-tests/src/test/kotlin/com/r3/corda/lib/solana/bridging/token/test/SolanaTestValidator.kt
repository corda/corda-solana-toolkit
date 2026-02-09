package com.r3.corda.lib.solana.bridging.token.test

import com.r3.corda.lib.solana.bridging.token.flows.globalCommitmentLevel
import com.r3.corda.lib.solana.core.AccountManagement
import com.r3.corda.lib.solana.core.SolanaClient
import com.r3.corda.lib.solana.core.SolanaUtils
import com.r3.corda.lib.solana.core.TokenManagement
import net.corda.core.utilities.contextLogger
import net.corda.solana.notary.client.CordaNotary
import net.corda.solana.notary.client.instructions.AuthorizeNotary
import net.corda.solana.notary.client.instructions.CreateNetwork
import net.corda.solana.notary.client.instructions.Initialize
import net.corda.testing.common.internal.isListening
import software.sava.core.accounts.PublicKey
import software.sava.core.accounts.Signer
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class SolanaTestValidator : AutoCloseable {
    companion object {
        private val logger = contextLogger()

        private val notaryProgramFile = Files.createTempFile("corda_notary", ".so").toAbsolutePath()

        const val DEFAULT_RPC_PORT = 8899

        // Alternative configuration is for testing error cases recovery.
        // The design intention here is to stop the default configuration, perform
        // some actions on the alternative set-up (if needed) and resume the default setup
        const val ALTERNATIVE_RPC_PORT = 8799

        init {
            notaryProgramFile.toFile().deleteOnExit()
            SolanaTestValidator::class.java
                .getResourceAsStream("/net/corda/solana/notary/program/corda_notary.so")!!
                .use {
                    Files.copy(it, notaryProgramFile, REPLACE_EXISTING)
                }
        }
    }

    private lateinit var process: Process
    private var nextCordaNetworkId: Short = 0
    lateinit var ledgerDir: Path

    val notaryProgramAdmin: Signer = SolanaUtils.randomSigner()
    lateinit var rpcUrl: String
    lateinit var wsUrl: String
    lateinit var client: SolanaClient
    lateinit var accounts: AccountManagement
    lateinit var tokens: TokenManagement

    @Synchronized
    fun start(
        existingLedgerDir: Path = Files.createTempDirectory("test-ledger"),
        rpcPort: Int = DEFAULT_RPC_PORT,
    ) {
        val portAvailable = try {
            ServerSocket(rpcPort).use { true }
        } catch (_: IOException) {
            false
        }
        check(portAvailable) { "Another solana-test-validator instance is already running on RPC port: $rpcPort" }
        rpcUrl = "http://127.0.0.1:$rpcPort"
        wsUrl = "ws://127.0.0.1:${rpcPort + 1}"
        ledgerDir = existingLedgerDir
        process = ProcessBuilder()
            .command(
                "solana-test-validator",
                "-ql=$ledgerDir",
                "--rpc-port=$rpcPort",
                "--bpf-program",
                CordaNotary.PROGRAM_ID.toBase58(),
                notaryProgramFile.toString()
            ).inheritIO()
            .start()
        Runtime.getRuntime().addShutdownHook(Thread(process::destroyForcibly))
        while (!isListening("127.0.0.1", rpcPort)) {
            Thread.sleep(100)
        }
        client = SolanaClient(URI(rpcUrl), URI(wsUrl), globalCommitmentLevel)
        client.start()
        accounts = AccountManagement(client)
        tokens = TokenManagement(client)
        logger.info("Started listening on port $rpcPort")
    }

    fun defaultNotaryProgramSetup(notary: PublicKey) {
        initialiseNotaryProgram()
        val networkId = createNewCordaNetwork()
        accounts.airdropSol(notary, 10)
        addNotary(networkId, notary)
    }

    fun initialiseNotaryProgram() {
        accounts.airdropSol(notaryProgramAdmin.publicKey(), 10)
        client.sendAndConfirm(
            { it.createTransaction(Initialize.instruction(notaryProgramAdmin.publicKey())) },
            notaryProgramAdmin
        )
    }

    fun createNewCordaNetwork(): Short {
        val networkId = nextCordaNetworkId++
        client.sendAndConfirm(
            { it.createTransaction(CreateNetwork.instruction(notaryProgramAdmin.publicKey(), networkId)) },
            notaryProgramAdmin
        )
        return networkId
    }

    fun addNotary(networkId: Short, notary: PublicKey) {
        client.sendAndConfirm(
            { it.createTransaction(AuthorizeNotary.instruction(notary, notaryProgramAdmin.publicKey(), networkId)) },
            notaryProgramAdmin
        )
    }

    fun stopIfRunning() {
        if (::client.isInitialized) {
            client.close()
        }
        if (::process.isInitialized) {
            logger.info("Shutting down...")
            process.destroyForcibly().waitFor()
        }
    }

    override fun close() {
        stopIfRunning()
    }
}
