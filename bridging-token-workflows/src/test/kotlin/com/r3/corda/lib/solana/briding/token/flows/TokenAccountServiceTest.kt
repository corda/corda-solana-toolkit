package com.r3.corda.lib.solana.briding.token.flows

import com.lmax.solana4j.api.PublicKey
import com.lmax.solana4j.programs.AssociatedTokenProgram
import com.r3.corda.lib.solana.bridging.token.flows.BoundedExistingAtaCache
import com.r3.corda.lib.solana.bridging.token.flows.ExistingAtaCache
import com.r3.corda.lib.solana.bridging.token.flows.TokenAccountService
import com.r3.corda.lib.solana.bridging.token.flows.toPublicKey
import net.corda.solana.notary.common.Signer
import net.corda.solana.notary.common.rpc.SolanaClientException
import net.corda.solana.notary.common.rpc.checkResponse
import net.corda.solana.sdk.internal.Token2022
import net.corda.testing.solana.SolanaTestValidator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Named
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.lang.IllegalStateException
import java.math.BigDecimal
import java.util.stream.Stream

class TokenAccountServiceTest {
    companion object {
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
    }

    lateinit var testValidator: SolanaTestValidator
    lateinit var mintAuthoritySigner: Signer
    lateinit var tokenMint: PublicKey
    lateinit var wallet: Signer

    @BeforeEach
    fun setup() {
        testValidator = SolanaTestValidator()
        mintAuthoritySigner = Signer.random()
        try {
            testValidator.start()
        } catch (e: IllegalStateException) {
            if (e.message == "Another solana-test-validator instance is already running") {
                // for these tests error is fine, tests create random new accounts
            } else {
                throw e
            }
        }
        testValidator.fundAccount(10, mintAuthoritySigner)
        tokenMint = testValidator.createToken(mintAuthoritySigner, decimals = 3.toByte())
        wallet = Signer.random()
    }

    @AfterEach
    fun tearDown() {
        if (::testValidator.isInitialized) {
            testValidator.close()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cacheImplementations")
    fun `repeated call to  create ATA should not throw exception`(cache: ExistingAtaCache) {
        val sut = TokenAccountService(testValidator.client, mintAuthoritySigner, cache)

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
            .client
            .getTokenAccountBalance(publicKey.base58(), testValidator.rpcParams)
            .checkResponse("getTokenAccountBalance")!!
            .uiAmountString
            .toBigDecimal()
    }
}
