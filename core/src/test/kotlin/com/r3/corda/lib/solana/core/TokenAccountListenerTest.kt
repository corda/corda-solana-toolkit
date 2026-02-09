package com.r3.corda.lib.solana.core

import com.r3.corda.lib.solana.testing.SolanaTestClass
import com.r3.corda.lib.solana.testing.SolanaTestValidator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import software.sava.core.accounts.PublicKey
import software.sava.core.accounts.Signer
import software.sava.core.accounts.token.TokenAccount
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS

@SolanaTestClass(waitForReadiness = false)
class TokenAccountListenerTest {
    companion object {
        private lateinit var mintAuthority: Signer
        private lateinit var owner1: Signer
        private lateinit var owner2: Signer
        private lateinit var tokenMint1: PublicKey
        private lateinit var tokenMint2: PublicKey

        @BeforeAll
        @JvmStatic
        fun start(testValidator: SolanaTestValidator) {
            mintAuthority = SolanaUtils.randomSigner()
            owner1 = SolanaUtils.randomSigner()
            owner2 = SolanaUtils.randomSigner()
            testValidator.accounts().airdropSol(mintAuthority.publicKey(), 10)
            testValidator.accounts().airdropSol(owner1.publicKey(), 10)
            testValidator.accounts().airdropSol(owner2.publicKey(), 10)
            tokenMint1 = testValidator.tokens().createToken(mintAuthority)
            tokenMint2 = testValidator.tokens().createToken(mintAuthority)
        }
    }

    private lateinit var tokenAccountListener: TokenAccountListener

    @BeforeEach
    fun create(client: SolanaClient) {
        tokenAccountListener = TokenAccountListener(client)
    }

    @AfterEach
    fun close() {
        if (::tokenAccountListener.isInitialized) {
            tokenAccountListener.close()
        }
    }

    @Test
    fun `mint to new token account`(testValidator: SolanaTestValidator) {
        val updates = listenTo(owner1.publicKey())
        val tokenAccountAddress = testValidator.tokens().createTokenAccount(owner1, tokenMint1)
        updates.assertLatestUpdate(tokenAccountAddress, tokenMint1, 0)
        testValidator.tokens().mintTo(tokenAccountAddress, tokenMint1, mintAuthority, 10_000)
        updates.assertLatestUpdate(tokenAccountAddress, tokenMint1, 10_000)
    }

    @Test
    fun transfer(testValidator: SolanaTestValidator) {
        val owner1Token = testValidator.tokens().createTokenAccount(owner1, tokenMint1)
        val owner2Token = testValidator.tokens().createTokenAccount(owner2, tokenMint1)
        testValidator.tokens().mintTo(owner1Token, tokenMint1, mintAuthority, 1_000)

        val owner1Updates = listenTo(owner1.publicKey())
        val owner2Updates = listenTo(owner2.publicKey())
        testValidator.tokens().transfer(owner1, owner1Token, owner2Token, 400)
        owner1Updates.assertLatestUpdate(owner1Token, tokenMint1, 600) // Owner 1 balance is now 600
        owner2Updates.assertLatestUpdate(owner2Token, tokenMint1, 400)
    }

    @Test
    fun `does not receive prior updates`(testValidator: SolanaTestValidator) {
        val tokenAccountAddress = testValidator.tokens().createTokenAccount(owner1, tokenMint1)
        val updates = listenTo(owner1.publicKey())
        updates.assertNoUpdates()
        testValidator.tokens().mintTo(tokenAccountAddress, tokenMint1, mintAuthority, 10_000)
        updates.assertLatestUpdate(tokenAccountAddress, tokenMint1, 10_000)
    }

    @Test
    fun `does not receive update for another owner`(testValidator: SolanaTestValidator) {
        val owner1Updates = listenTo(owner1.publicKey())
        val owner2Updates = listenTo(owner2.publicKey())

        val owner1Token = testValidator.tokens().createTokenAccount(owner1, tokenMint1)
        owner2Updates.assertNoUpdates()
        owner1Updates.assertLatestUpdate(owner1Token, tokenMint1, 0)

        val owner2Token = testValidator.tokens().createTokenAccount(owner2, tokenMint1)
        owner1Updates.assertNoUpdates()
        owner2Updates.assertLatestUpdate(owner2Token, tokenMint1, 0)
    }

    @Test
    fun unsubscribe(testValidator: SolanaTestValidator) {
        val updates = listenTo(owner1.publicKey())
        val tokenAccountAddress = testValidator.tokens().createTokenAccount(owner1, tokenMint1)
        updates.assertLatestUpdate(tokenAccountAddress, tokenMint1, 0)
        tokenAccountListener.unsubscribe(owner1.publicKey())
        testValidator.tokens().mintTo(tokenAccountAddress, tokenMint1, mintAuthority, 10_000)
        updates.assertNoUpdates()
    }

    private fun listenTo(owner: PublicKey): TokenUpdates {
        val tokenUpdates = TokenUpdates(owner)
        tokenAccountListener.listenToOwner(owner, tokenUpdates.queue::add)
        return tokenUpdates
    }

    private class TokenUpdates(private val owner: PublicKey) {
        val queue = LinkedBlockingQueue<TokenAccount>()

        fun assertLatestUpdate(address: PublicKey, tokenMint: PublicKey, amount: Long) {
            val tokenAccount = queue.poll(3, SECONDS)
            checkNotNull(tokenAccount) { "Did not receive new TokenAccount update" }
            assertThat(tokenAccount.address).isEqualTo(address)
            assertThat(tokenAccount.owner).isEqualTo(owner)
            assertThat(tokenAccount.mint).isEqualTo(tokenMint)
            assertThat(tokenAccount.amount).isEqualTo(amount)
        }

        fun assertNoUpdates() {
            assertThat(queue.poll(3, SECONDS)).isNull()
        }
    }
}
