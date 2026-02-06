package com.r3.corda.lib.solana.bridging.token.test

import com.lmax.solana4j.Solana
import com.r3.corda.lib.solana.bridging.token.flows.globalCommitmentLevel
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.getOrThrow
import net.corda.node.utilities.solana.AccountManagement
import net.corda.node.utilities.solana.SolanaClient
import net.corda.node.utilities.solana.SolanaUtils
import net.corda.node.utilities.solana.SolanaUtils.toSolana4j
import net.corda.node.utilities.solana.TokenManagement
import net.corda.solana.notary.client.CordaNotary
import net.corda.solana.notary.common.AccountMeta
import net.corda.solana.notary.common.AnchorInstruction
import net.corda.solana.notary.common.addAnchorInstruction
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
                CordaNotary.PROGRAM_ID.base58(),
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
        sendAndConfirm(CordaNotary.Initialize(notaryProgramAdmin.toSolana4j()))
    }

    fun createNewCordaNetwork(): Short {
        val networkId = nextCordaNetworkId++
        sendAndConfirm(CordaNotary.CreateNetwork(notaryProgramAdmin.toSolana4j(), networkId))
        return networkId
    }

    fun addNotary(networkId: Short, notary: PublicKey) {
        sendAndConfirm(
            CordaNotary.AuthorizeNotary(
                addressToAuthorize = Solana.account(notary.toByteArray()),
                admin = notaryProgramAdmin.toSolana4j(),
                networkId = networkId
            )
        )
    }

    private fun sendAndConfirm(instruction: AnchorInstruction, remainingAccounts: List<AccountMeta> = emptyList()) {
        val txFeePayer = requireNotNull(instruction.txFeePayer) {
            "Instruction has not specified the transaction fee payer"
        }
        val transaction = client.serialiseTransaction(
            { txBuilder -> txBuilder.addAnchorInstruction(instruction, remainingAccounts) },
            txFeePayer,
            instruction.signers
        )
        client.asyncSendAndConfirm(transaction).getOrThrow()
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
