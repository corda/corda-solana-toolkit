package com.r3.corda.lib.solana.bridging.token.test

import com.lmax.solana4j.Solana
import com.lmax.solana4j.api.PublicKey
import com.lmax.solana4j.api.TransactionBuilder
import com.lmax.solana4j.client.jsonrpc.SolanaJsonRpcClient
import com.lmax.solana4j.programs.SystemProgram
import com.lmax.solana4j.programs.SystemProgram.MINT_ACCOUNT_LENGTH
import com.lmax.solana4j.programs.Token2022Program
import com.lmax.solana4j.programs.TokenProgram
import com.lmax.solana4j.programs.TokenProgram.ACCOUNT_LAYOUT_SPAN
import net.corda.core.utilities.contextLogger
import net.corda.solana.notary.client.CordaNotary
import net.corda.solana.notary.common.Signer
import net.corda.solana.notary.common.rpc.DefaultRpcParams
import net.corda.solana.notary.common.rpc.checkResponse
import net.corda.solana.notary.common.rpc.sendAndConfirm
import net.corda.testing.common.internal.exec
import net.corda.testing.common.internal.isListening
import java.io.IOException
import java.net.ServerSocket
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Optional

@Suppress("MagicNumber")
class SolanaTestValidator(val rpcParams: DefaultRpcParams = DefaultRpcParams()) : AutoCloseable {
    companion object {
        private val logger = contextLogger()

        private val notaryProgramFile = Files.createTempFile("corda_notary", ".so").toAbsolutePath()

        private const val DEFAULT_RPC_PORT = 8899

        // Alternative configuration is for testing error cases recovery.
        // The design intention here is to stop the default configuration, perform
        // some actions on the alternative set-up (if needed) and resume the default setup
        private const val ALTERNATIVE_RPC_PORT = 8799
        private const val DEFAULT_WS_PORT = 8900

        // Solana test validator sets the WS PORT as the RPC port + 1
        private const val ALTERNATIVE_WS_PORT = 8800
        private const val DEFAULT_RPC_URL = "http://127.0.0.1:$DEFAULT_RPC_PORT"
        private const val DEFAULT_WS_URL = "ws://127.0.0.1:$DEFAULT_WS_PORT"
        private const val ALTERNATIVE_RPC_URL = "http://127.0.0.1:$ALTERNATIVE_RPC_PORT"
        private const val ALTERNATIVE_WS_URL = "ws://127.0.0.1:$ALTERNATIVE_WS_PORT"

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
    private var state = State.STOPPED
    private var nextCordaNetworkId: Short = 0
    private lateinit var ledgerStore: Path

    val notaryProgramAdmin: Signer = Signer.random()
    private val defaultRpcClient: SolanaJsonRpcClient = SolanaJsonRpcClient(
        HttpClient.newHttpClient(),
        DEFAULT_RPC_URL,
    )
    private val alternativeRpcClient: SolanaJsonRpcClient = SolanaJsonRpcClient(
        HttpClient.newHttpClient(),
        ALTERNATIVE_RPC_URL
    )

    val isStopped: Boolean get() = state == State.STOPPED
    val isDefaultConfigRunning: Boolean get() = state == State.RUNNING_DEFAULT
    val isAlternativeConfigRunning: Boolean get() = state == State.RUNNING_ALTERNATIVE

    val rpcClient: SolanaJsonRpcClient get() = if (isDefaultConfigRunning) defaultRpcClient else alternativeRpcClient
    val rpcUrl: String get() = if (state == State.RUNNING_DEFAULT) DEFAULT_RPC_URL else ALTERNATIVE_RPC_URL
    val wsUrl: String get() = if (isDefaultConfigRunning) DEFAULT_WS_URL else ALTERNATIVE_WS_URL

    @Synchronized
    fun start(alternativeConfig: Boolean = false, resetLedger: Boolean = false) {
        if (state != State.STOPPED) {
            close()
        }
        val port = ALTERNATIVE_RPC_PORT.takeIf { alternativeConfig } ?: DEFAULT_RPC_PORT
        val portAvailable = try {
            ServerSocket(port).use { true }
        } catch (_: IOException) {
            false
        }
        check(portAvailable) { "Another solana-test-validator instance is already running on RPC port: $port" }
        if (resetLedger || !::ledgerStore.isInitialized) {
            logger.info("Resetting ledger state")
            ledgerStore = Files.createTempDirectory("test-ledger")
        }
        process = ProcessBuilder()
            .command(
                "solana-test-validator",
                "-ql=$ledgerStore",
                "--bpf-program",
                CordaNotary.PROGRAM_ID.base58(),
                notaryProgramFile.toString()
            ).inheritIO()
            .start()
        Runtime.getRuntime().addShutdownHook(Thread(process::destroyForcibly))
        while (!isListening("127.0.0.1", port)) {
            Thread.sleep(100)
        }
        state = if (alternativeConfig) State.RUNNING_ALTERNATIVE else State.RUNNING_DEFAULT
        logger.info("Started wit ${if (alternativeConfig) "alternative" else "default"} configuration")
    }

    fun fundAccount(amount: Long, account: Signer) {
        fundAccount(amount, account.account)
    }

    fun fundAccount(amount: Long, account: PublicKey) {
        val commitmentString = rpcParams.commitment.name.lowercase()
        exec(
            "solana airdrop -u localhost --commitment $commitmentString $amount ${account.base58()}"
        )
    }

    fun defaultNotaryProgramSetup(notary: PublicKey) {
        initialiseNotaryProgram()
        val networkId = createNewCordaNetwork()
        fundAccount(10, notary)
        addNotary(networkId, notary)
    }

    fun initialiseNotaryProgram() {
        fundAccount(10, notaryProgramAdmin)
        rpcClient.sendAndConfirm(CordaNotary.Initialize(notaryProgramAdmin), rpcParams = rpcParams)
    }

    fun createNewCordaNetwork(): Short {
        val networkId = nextCordaNetworkId++
        rpcClient.sendAndConfirm(CordaNotary.CreateNetwork(notaryProgramAdmin, networkId), rpcParams = rpcParams)
        return networkId
    }

    fun addNotary(networkId: Short, notary: PublicKey) {
        rpcClient.sendAndConfirm(
            CordaNotary.AuthorizeNotary(
                addressToAuthorize = notary,
                admin = notaryProgramAdmin,
                networkId = networkId
            ),
            rpcParams = rpcParams
        )
    }

    fun createToken(
        payer: Signer,
        mintAuthority: PublicKey = payer.account,
        tokenMint: Signer = Signer.random(),
        decimals: Byte = 6,
        isToken2022: Boolean = true,
    ): PublicKey {
        rpcClient.sendAndConfirm(
            { txBuilder ->
                txBuilder.createAccount(payer.account, tokenMint.account, MINT_ACCOUNT_LENGTH, isToken2022)
                getProgramFactory(isToken2022, txBuilder).initializeMint(
                    tokenMint.account,
                    decimals,
                    mintAuthority,
                    Optional.empty()
                )
            },
            payer,
            listOf(tokenMint),
            rpcParams
        )
        return tokenMint.account
    }

    fun createTokenAccount(
        payer: Signer,
        tokenMint: PublicKey,
        accountOwner: PublicKey = payer.account,
        tokenAccount: Signer = Signer.random(),
        isToken2022: Boolean = true,
    ): PublicKey {
        rpcClient.sendAndConfirm(
            { txBuilder ->
                txBuilder.createAccount(payer.account, tokenAccount.account, ACCOUNT_LAYOUT_SPAN, isToken2022)
                getProgramFactory(isToken2022, txBuilder).initializeAccount(
                    tokenAccount.account,
                    tokenMint,
                    accountOwner
                )
            },
            payer,
            listOf(tokenAccount),
            rpcParams
        )
        return tokenAccount.account
    }

    fun mintTo(
        payer: Signer,
        tokenMint: PublicKey,
        tokenAccount: PublicKey,
        amount: Long,
        mintAuthority: Signer = payer,
        isToken2022: Boolean = true,
    ) {
        rpcClient.sendAndConfirm(
            { txBuilder ->
                getProgramFactory(isToken2022, txBuilder).mintTo(
                    tokenMint,
                    mintAuthority.account,
                    listOf(Solana.destination(tokenAccount, amount))
                )
            },
            payer,
            listOf(mintAuthority),
            rpcParams
        )
    }

    private fun TransactionBuilder.createAccount(
        payer: PublicKey,
        account: PublicKey,
        size: Int,
        isToken2022: Boolean,
    ) {
        val rentExemption = rpcClient
            .getMinimumBalanceForRentExemption(size, rpcParams)
            .checkResponse("getMinimumBalanceForRentExemption")!!
        SystemProgram.factory(this).createAccount(
            payer,
            account,
            rentExemption,
            size.toLong(),
            if (isToken2022) Token2022Program.PROGRAM_ACCOUNT else TokenProgram.PROGRAM_ACCOUNT,
        )
    }

    private enum class State {
        STOPPED,
        RUNNING_DEFAULT,
        RUNNING_ALTERNATIVE,
    }

    private fun getProgramFactory(isToken2022: Boolean, txBuilder: TransactionBuilder) =
        if (isToken2022) Token2022Program.factory(txBuilder) else TokenProgram.factory(txBuilder)

    @Synchronized
    override fun close() {
        if (state != State.STOPPED) {
            logger.info("Shutting down...")
            process.destroyForcibly().waitFor()
            state = State.STOPPED
        }
    }
}
