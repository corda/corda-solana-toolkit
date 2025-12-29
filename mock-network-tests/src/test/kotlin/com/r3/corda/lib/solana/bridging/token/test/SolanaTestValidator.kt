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
import net.corda.solana.notary.common.rpc.SolanaTransactionException
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

    val notaryProgramAdmin: Signer = Signer.random()
    lateinit var rpcClient: SolanaJsonRpcClient
    lateinit var rpcUrl: String
    lateinit var wsUrl: String

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
        rpcClient = SolanaJsonRpcClient(
            HttpClient.newHttpClient(),
            rpcUrl,
        )
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
        logger.info("Started listening on port $rpcPort")
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
        try {
            initialiseNotaryProgram()
        } catch (e: SolanaTransactionException) {
            if (e.message?.contains("already in use") == false) {
                throw e
            }
        }
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

    private fun getProgramFactory(isToken2022: Boolean, txBuilder: TransactionBuilder) =
        if (isToken2022) Token2022Program.factory(txBuilder) else TokenProgram.factory(txBuilder)

    fun stopIfRunning() {
        if (::process.isInitialized) {
            logger.info("Shutting down...")
            process.destroyForcibly().waitFor()
        }
    }

    override fun close() {
        stopIfRunning()
    }
}
