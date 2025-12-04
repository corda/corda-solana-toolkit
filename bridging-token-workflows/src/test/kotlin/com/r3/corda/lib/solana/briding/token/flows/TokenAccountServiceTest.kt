package com.r3.corda.lib.solana.briding.token.flows

import com.lmax.solana4j.api.PublicKey
import com.lmax.solana4j.api.TransactionBuilder
import com.lmax.solana4j.client.api.SolanaApi
import com.lmax.solana4j.programs.AssociatedTokenProgram
import com.lmax.solana4j.programs.SystemProgram
import com.lmax.solana4j.programs.SystemProgram.MINT_ACCOUNT_LENGTH
import com.lmax.solana4j.programs.Token2022Program
import com.lmax.solana4j.programs.TokenProgram.ACCOUNT_LAYOUT_SPAN
import com.r3.corda.lib.solana.bridging.token.flows.BoundedExistingAtaCache
import com.r3.corda.lib.solana.bridging.token.flows.ExistingAtaCache
import com.r3.corda.lib.solana.bridging.token.flows.TokenAccountService
import com.r3.corda.lib.solana.bridging.token.flows.toPublicKey
import net.corda.solana.notary.common.Signer
import net.corda.solana.notary.common.rpc.DefaultRpcParams
import net.corda.solana.notary.common.rpc.SolanaClientException
import net.corda.solana.notary.common.rpc.checkResponse
import net.corda.solana.notary.common.rpc.sendAndConfirm
import net.corda.solana.notary.test.SolanaTestValidator
import net.corda.solana.sdk.internal.Token2022
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Named
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal
import java.nio.file.Path
import java.util.*
import java.util.stream.Stream

class TokenAccountServiceTest {
    companion object {
        lateinit var testValidator: SolanaTestValidator
        private var closeTestValidator = true

        @JvmStatic
        fun cacheImplementations(): Stream<Named<ExistingAtaCache>> = Stream.of(
            Named.of(
                "first ATA create operation is cached, second operation doesn't reach chain",
                BoundedExistingAtaCache()
            ),
            Named.of(
                "no cache, ATA creation requested twice on chain",
                object : ExistingAtaCache {
                    override fun put(mintAccount: PublicKey, ownerAccount: PublicKey) = Unit

                    override fun contains(mintAccount: PublicKey, ownerAccount: PublicKey) = false
                }
            )
        )

        @BeforeAll
        @JvmStatic
        fun setup() {
            testValidator = SolanaTestValidator.builder().start()
            testValidator.waitForReadiness()
//            try {
//
//            } catch (e: IllegalStateException) {
//                if (e.message == "Another solana-test-validator instance is already running") {
//                    // for these tests error is fine, tests create random new accounts
//                    closeTestValidator = false // let the test which started it close it
//                    println("Re-using another solana-test-validator instance that is already running")
//                } else {
//                    throw e
//                }
//            }
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            if (::testValidator.isInitialized && closeTestValidator) {
                testValidator.close()
            }
        }
    }

    @BeforeEach
    fun setupAccounts() {
        mintAuthoritySigner = Signer.random()
        val client = testValidator.connectRpcClient()
        fundAccount(10, mintAuthoritySigner)
        tokenMint = testValidator.createToken(mintAuthoritySigner, decimals = 3.toByte())
        wallet = Signer.random()
    }

    lateinit var mintAuthoritySigner: Signer
    lateinit var tokenMint: PublicKey
    lateinit var wallet: Signer

    @ParameterizedTest(name = "{0}")
    @MethodSource("cacheImplementations")
    fun `repeated call to  create ATA should not throw exception`(cache: ExistingAtaCache) {
        val sut = TokenAccountService(testValidator.connectRpcClient(), mintAuthoritySigner, cache)

        val tokenAccount = AssociatedTokenProgram
            .deriveAddress(
                wallet.account,
                Token2022.PROGRAM_ID.toPublicKey(),
                tokenMint
            ).address()

        assertThrows(
            SolanaClientException::class.java,
            { getSolanaTokenBalance(tokenAccount) },
            "ATA account should not exist yet"
        )
        sut.createAta(tokenMint, wallet.account)
        assertEquals(
            BigDecimal.ZERO,
            getSolanaTokenBalance(tokenAccount),
            "ATA should exist with 0 balance"
        )
        assertDoesNotThrow(
            { sut.createAta(tokenMint, wallet.account) },
            "The call to create ATA should be idempotent"
        )
    }

    private fun getSolanaTokenBalance(publicKey: PublicKey): BigDecimal {
        return testValidator
            .connectRpcClient()
            .getTokenAccountBalance(publicKey.base58(), rpcParams)
            .checkResponse("getTokenAccountBalance")!!
            .uiAmountString
            .toBigDecimal()
    }
}

fun SolanaTestValidator.createToken(
    payer: Signer,
    mintAuthority: PublicKey = payer.account,
    tokenMint: Signer = Signer.random(),
    decimals: Byte = 6,
): PublicKey {
    val client = this.connectRpcClient()
    client.sendAndConfirm(
        { txBuilder ->
            client.createAccount(txBuilder, payer.account, tokenMint.account, MINT_ACCOUNT_LENGTH)
            Token2022Program.factory(txBuilder).initializeMint(
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

fun SolanaApi.createTokenAccount(
    payer: Signer,
    tokenMint: PublicKey,
    accountOwner: PublicKey = payer.account,
    tokenAccount: Signer = Signer.random(),
): PublicKey {
    this.sendAndConfirm(
        { txBuilder ->
            this.createAccount(txBuilder, payer.account, tokenAccount.account, ACCOUNT_LAYOUT_SPAN)
            Token2022Program.factory(txBuilder).initializeAccount(tokenAccount.account, tokenMint, accountOwner)
        },
        payer,
        listOf(tokenAccount),
        rpcParams
    )
    return tokenAccount.account
}

fun SolanaApi.createAccount(txBuilder: TransactionBuilder, payer: PublicKey, account: PublicKey, size: Int) {
    val rentExemption = this
        .getMinimumBalanceForRentExemption(size, rpcParams)
        .checkResponse("getMinimumBalanceForRentExemption")!!
    SystemProgram.factory(txBuilder).createAccount(
        payer,
        account,
        rentExemption,
        size.toLong(),
        Token2022Program.PROGRAM_ACCOUNT
    )
}

val rpcParams: DefaultRpcParams = DefaultRpcParams()

fun fundAccount(amount: Long, account: Signer) {
    fundAccount(amount, account.account)
}

fun fundAccount(amount: Long, account: PublicKey) {
    exec(
        "solana airdrop -u localhost --commitment " +
            "${rpcParams.commitment.name.lowercase()} $amount ${account.base58()}"
    )
}

fun exec(args: String, workingDirectory: Path? = null) {
    val builder = ProcessBuilder(args.split(" ")).redirectError(ProcessBuilder.Redirect.INHERIT)
    if (workingDirectory != null) {
        builder.directory(workingDirectory.toFile())
    }
    val process = builder.start()
    val exitCode = process.waitFor()
    val output = process.inputReader().use { it.readText() }
    check(exitCode == 0) { "Process failed ($exitCode): $output" }
}
