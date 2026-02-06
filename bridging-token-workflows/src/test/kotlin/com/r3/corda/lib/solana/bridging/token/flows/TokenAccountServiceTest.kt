package com.r3.corda.lib.solana.bridging.token.flows

import com.lmax.solana4j.api.PublicKey
import com.lmax.solana4j.programs.AssociatedTokenProgram
import net.corda.node.utilities.solana.TokenProgram.TOKEN_2022
import net.corda.solana.notary.common.Signer
import net.corda.solana.sdk.Token2022
import net.corda.testing.solana.SolanaTestValidator
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Named
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import software.sava.core.accounts.PublicKey.createPubKey
import software.sava.rpc.json.http.client.SolanaRpcClient
import software.sava.rpc.json.http.response.JsonRpcException
import java.math.BigDecimal
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
            testValidator = SolanaTestValidator()
            try {
                testValidator.startAndWait()
            } catch (e: IllegalStateException) {
                if (e.message == "Another solana-test-validator instance is already running") {
                    // for these tests error is fine, tests create random new accounts
                    closeTestValidator = false // let the test which started it close it
                } else {
                    throw e
                }
            }
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
        testValidator.accounts.airdropSol(mintAuthoritySigner.account, 10)
        tokenMint = testValidator.tokens.createToken(
            mintAuthoritySigner,
            decimals = 3.toByte(),
            tokenProgram = TOKEN_2022
        )
        wallet = Signer.random()
    }

    lateinit var mintAuthoritySigner: Signer
    lateinit var tokenMint: PublicKey
    lateinit var wallet: Signer

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

        assertThatThrownBy { getSolanaTokenBalance(tokenAccount) }
            .isInstanceOf(JsonRpcException::class.java)
            .hasMessageContaining("could not find account")
        sut.createAta(tokenMint, wallet.account)
        assertEquals(
            BigDecimal.ZERO,
            getSolanaTokenBalance(tokenAccount),
            "ATA should exist with 0 balance"
        )
        testValidator.client.getBlockhashInfo(forceFetch = true) // Make sure a different blockhash is used
        assertDoesNotThrow(
            { sut.createAta(tokenMint, wallet.account) },
            "The call to create ATA should be idempotent"
        )
    }

    private fun getSolanaTokenBalance(publicKey: PublicKey): BigDecimal {
        return testValidator
            .client
            .call(SolanaRpcClient::getTokenAccountBalance, createPubKey(publicKey.bytes()))
            .toDecimal()
    }
}
