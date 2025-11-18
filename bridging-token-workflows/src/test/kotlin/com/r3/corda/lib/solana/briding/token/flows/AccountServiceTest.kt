package com.r3.corda.lib.solana.briding.token.flows

import com.lmax.solana4j.api.PublicKey
import com.lmax.solana4j.programs.AssociatedTokenProgram
import com.r3.corda.lib.solana.bridging.token.flows.AccountService
import com.r3.corda.lib.solana.bridging.token.flows.toPublicKey
import net.corda.solana.notary.common.Signer
import net.corda.solana.notary.common.rpc.SolanaClientException
import net.corda.solana.notary.common.rpc.checkResponse
import net.corda.solana.sdk.internal.Token2022
import net.corda.testing.solana.SolanaTestValidator
import net.corda.testing.solana.randomKeypairFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path

class AccountServiceTest {
    lateinit var testValidator: SolanaTestValidator
    lateinit var mintAuthoritySigner: Signer
    lateinit var tokenMint: PublicKey
    lateinit var wallet: Signer

    @TempDir
    lateinit var custodiedKeysDir: Path

    @BeforeEach
    fun setup() {
        testValidator = SolanaTestValidator()
        mintAuthoritySigner = Signer.fromFile(randomKeypairFile(custodiedKeysDir))
        testValidator.start()
        testValidator.fundAccount(10, mintAuthoritySigner)
        tokenMint = testValidator.createToken(mintAuthoritySigner, decimals = 3.toByte())
        wallet = Signer.fromFile(randomKeypairFile(custodiedKeysDir))
    }

    @AfterEach
    fun tearDown() {
        if (::testValidator.isInitialized) {
            testValidator.close()
        }
    }

    @Test
    fun test() {
        val sut = AccountService(testValidator.client, mintAuthoritySigner)

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
